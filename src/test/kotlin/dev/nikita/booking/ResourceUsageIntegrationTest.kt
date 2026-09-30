package dev.nikita.booking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "grpc.port=0",
        "analytics.refresh.enabled=false",
        "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'Pacific/Honolulu'"
    ]
)
@Import(BookingApiTest.TestClock::class)
class ResourceUsageIntegrationTest {
    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var refresh: ResourceUsageRefresh
    @Autowired lateinit var dataSource: DataSource

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE bookings, idempotency_keys")
        assertThat(refresh.refresh()).isTrue()
        jdbc.update("UPDATE resource_usage_refresh_state SET as_of = NULL, refreshed_at = NULL WHERE singleton")
    }

    @Test
    fun `empty report includes every requested day before the first refresh`() {
        val body = report("2030-01-02", "2030-01-05")
        assertThat(body["from"].asText()).isEqualTo("2030-01-02")
        assertThat(body["to"].asText()).isEqualTo("2030-01-05")
        assertThat(body.has("asOf")).isTrue()
        assertThat(body["asOf"].isNull).isTrue()
        assertThat(body.has("refreshedAt")).isTrue()
        assertThat(body["refreshedAt"].isNull).isTrue()
        assertThat(body["days"].map { it["day"].asText() })
            .containsExactly("2030-01-02", "2030-01-03", "2030-01-04")
        body["days"].forEach { day ->
            assertThat(day["resourceId"].asText()).isEqualTo(ROOM)
            assertThat(day["resourceName"].asText()).isEqualTo("Переговорная на 6 человек")
            assertThat(day["bookingCount"].asInt()).isZero()
            assertThat(day["bookedSeconds"].decimalValue()).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(day["utilizationPercent"].decimalValue()).isEqualByComparingTo(BigDecimal.ZERO)
        }
    }

    @Test
    fun `cross-midnight bookings are split at UTC midnight`() {
        insert("2030-01-02T22:00:00Z", "2030-01-03T02:00:00Z")
        insert("2030-01-03T10:00:00Z", "2030-01-03T11:00:00Z")
        insert("2030-01-02T00:00:00Z", "2030-01-03T00:00:00Z", resourceId = PROJECTOR)
        assertThat(refresh.refresh()).isTrue()

        val body = report("2030-01-02", "2030-01-04")
        assertDay(body, "2030-01-02", 1, "7200", "8.3333")
        assertDay(body, "2030-01-03", 2, "10800", "12.5")
        assertThat(body["days"].map { it["resourceId"].asText() }).containsOnly(ROOM)

        val projector = report("2030-01-02", "2030-01-04", PROJECTOR)
        assertDay(projector, "2030-01-02", 1, "86400", "100")
        assertDay(projector, "2030-01-03", 0, "0", "0")
    }

    @Test
    fun `an exact-midnight end does not occupy the next day`() {
        insert("2030-01-02T23:30:00Z", "2030-01-03T00:00:00Z")
        assertThat(refresh.refresh()).isTrue()

        val body = report("2030-01-02", "2030-01-04")
        assertDay(body, "2030-01-02", 1, "1800", "2.0833")
        assertDay(body, "2030-01-03", 0, "0", "0")
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM resource_daily_usage WHERE resource_id = ? AND day = DATE '2030-01-03'",
            Int::class.java, UUID.fromString(ROOM)
        )).isZero()
    }

    @Test
    fun `cancelled bookings do not contribute to counts or occupied seconds`() {
        insert("2030-01-02T10:00:00Z", "2030-01-02T11:00:00Z", cancelled = true)
        insert("2030-01-02T10:00:00Z", "2030-01-02T10:30:00Z")
        assertThat(refresh.refresh()).isTrue()

        assertDay(report("2030-01-02", "2030-01-03"), "2030-01-02", 1, "1800", "2.0833")
    }

    @Test
    fun `daily boundaries are independent of the PostgreSQL session timezone`() {
        assertThat(jdbc.queryForObject("SHOW TIME ZONE", String::class.java)).isEqualTo("Pacific/Honolulu")
        insert("2030-01-02T23:00:00Z", "2030-01-03T01:00:00Z")
        assertThat(refresh.refresh()).isTrue()

        val body = report("2030-01-02", "2030-01-04")
        assertDay(body, "2030-01-02", 1, "3600", "4.1667")
        assertDay(body, "2030-01-03", 1, "3600", "4.1667")
    }

    @Test
    fun `fractional seconds are retained and utilization is rounded to four places`() {
        insert("2030-01-02T10:00:00Z", "2030-01-02T10:00:00.123456Z")
        assertThat(refresh.refresh()).isTrue()

        assertDay(report("2030-01-02", "2030-01-03"), "2030-01-02", 1, "0.123456", "0.0001")
    }

    @Test
    fun `create and cancel become visible only after refresh and keep freshness metadata`() {
        assertThat(refresh.refresh()).isTrue()
        val empty = report("2030-01-02", "2030-01-03")
        assertFreshness(empty)
        assertDay(empty, "2030-01-02", 0, "0", "0")

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", "usage-refresh")
        }
        val created = http.exchange(
            "/api/bookings", HttpMethod.POST, HttpEntity(
                mapOf(
                    "resourceId" to ROOM,
                    "bookedBy" to "Nikita",
                    "startsAt" to "2030-01-02T10:00:00Z",
                    "endsAt" to "2030-01-02T11:00:00Z"
                ), headers
            ), String::class.java
        )
        assertThat(created.statusCode.value()).isEqualTo(201)
        val beforeRefresh = report("2030-01-02", "2030-01-03")
        assertThat(beforeRefresh).isEqualTo(empty)

        assertThat(refresh.refresh()).isTrue()
        val occupied = report("2030-01-02", "2030-01-03")
        assertFreshness(occupied)
        assertDay(occupied, "2030-01-02", 1, "3600", "4.1667")
        assertThat(Instant.parse(occupied["asOf"].asText()))
            .isAfterOrEqualTo(Instant.parse(empty["asOf"].asText()))

        val id = mapper.readTree(created.body)["id"].asText()
        val cancelled = http.postForEntity("/api/bookings/$id/cancel", null, String::class.java)
        assertThat(cancelled.statusCode.value()).isEqualTo(200)
        assertThat(report("2030-01-02", "2030-01-03")).isEqualTo(occupied)

        assertThat(refresh.refresh()).isTrue()
        val released = report("2030-01-02", "2030-01-03")
        assertFreshness(released)
        assertDay(released, "2030-01-02", 0, "0", "0")
        assertThat(Instant.parse(released["asOf"].asText()))
            .isAfterOrEqualTo(Instant.parse(occupied["asOf"].asText()))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_daily_usage", Int::class.java)).isZero()
    }

    @Test
    fun `date range accepts one to thirty-one days and excludes the end date`() {
        assertThat(report("2030-01-01", "2030-01-02")["days"].size()).isEqualTo(1)
        val month = report("2030-01-01", "2030-02-01")
        assertThat(month["days"].size()).isEqualTo(31)
        assertThat(month["days"].last()["day"].asText()).isEqualTo("2030-01-31")
    }

    @Test
    fun `a refresh already in progress preserves the current report and metadata`() {
        assertThat(refresh.refresh()).isTrue()
        val previous = report("2030-01-02", "2030-01-03")
        insert("2030-01-02T10:00:00Z", "2030-01-02T11:00:00Z")

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(742335819001)") }
                assertThat(refresh.refresh()).isFalse()
                assertThat(report("2030-01-02", "2030-01-03")).isEqualTo(previous)
            } finally {
                connection.rollback()
            }
        }

        assertThat(refresh.refresh()).isTrue()
        assertDay(report("2030-01-02", "2030-01-03"), "2030-01-02", 1, "3600", "4.1667")
    }

    @Test
    fun `metadata failure rolls back the refreshed view and allows a later retry`() {
        assertThat(refresh.refresh()).isTrue()
        val previous = report("2030-01-02", "2030-01-03")
        insert("2030-01-02T10:00:00Z", "2030-01-02T11:00:00Z")
        jdbc.execute(
            """
            CREATE FUNCTION reject_usage_refresh() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'refresh metadata write failed';
            END;
            ${'$'}${'$'}
            """.trimIndent()
        )
        try {
            jdbc.execute(
                """
                CREATE TRIGGER reject_usage_refresh_update
                BEFORE UPDATE ON resource_usage_refresh_state
                FOR EACH ROW EXECUTE FUNCTION reject_usage_refresh()
                """.trimIndent()
            )
            assertThatThrownBy { refresh.refresh() }
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageContaining("refresh metadata write failed")
            assertThat(report("2030-01-02", "2030-01-03")).isEqualTo(previous)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_daily_usage", Int::class.java)).isZero()
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_usage_refresh_update ON resource_usage_refresh_state")
            jdbc.execute("DROP FUNCTION reject_usage_refresh()")
        }

        assertThat(refresh.refresh()).isTrue()
        val retried = report("2030-01-02", "2030-01-03")
        assertFreshness(retried)
        assertDay(retried, "2030-01-02", 1, "3600", "4.1667")
    }

    @Test
    fun `missing malformed and unknown resource ids have the expected status`() {
        assertThat(response("2030-01-02", "2030-01-03", null).statusCode.value()).isEqualTo(400)
        assertThat(response("2030-01-02", "2030-01-03", "invalid").statusCode.value()).isEqualTo(400)
        assertThat(response("2030-01-02", "2030-01-03", UUID.randomUUID().toString()).statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `missing malformed and invalid date bounds return bad request`() {
        val queries = listOf(
            "from=2030-01-02&to=2030-01-02",
            "from=2030-01-03&to=2030-01-02",
            "from=2030-01-01&to=2030-02-02",
            "from=0000-01-01&to=0000-01-02",
            "from=2030-02-30&to=2030-03-01",
            "from=invalid&to=2030-01-03",
            "from=2030-01-02&to=invalid",
            "to=2030-01-03",
            "from=2030-01-02"
        )
        queries.forEach { query ->
            val response = http.getForEntity("/api/reports/resource-usage?resourceId=$ROOM&$query", String::class.java)
            assertThat(response.statusCode.value()).describedAs(query).isEqualTo(400)
        }
    }

    private fun insert(startsAt: String, endsAt: String, resourceId: String = ROOM, cancelled: Boolean = false) {
        jdbc.update(
            """
            INSERT INTO bookings (id, resource_id, booked_by, starts_at, ends_at, status, created_at, cancelled_at)
            VALUES (?, ?, 'Nikita', ?::timestamptz, ?::timestamptz, ?, ?::timestamptz, ?::timestamptz)
            """.trimIndent(),
            UUID.randomUUID(), UUID.fromString(resourceId), startsAt, endsAt,
            if (cancelled) "CANCELLED" else "ACTIVE", "2030-01-01T00:00:00Z",
            if (cancelled) "2030-01-01T01:00:00Z" else null
        )
    }

    private fun response(from: String, to: String, resourceId: String? = ROOM): ResponseEntity<String> {
        val query = "from=$from&to=$to" + (resourceId?.let { "&resourceId=$it" } ?: "")
        return http.getForEntity("/api/reports/resource-usage?$query", String::class.java)
    }

    private fun report(from: String, to: String, resourceId: String = ROOM): JsonNode {
        val response = response(from, to, resourceId)
        assertThat(response.statusCode.value()).isEqualTo(200)
        return mapper.readTree(response.body)
    }

    private fun assertDay(body: JsonNode, date: String, count: Int, seconds: String, utilization: String) {
        val day = body["days"].single { it["day"].asText() == date }
        assertThat(day["bookingCount"].asInt()).isEqualTo(count)
        assertThat(day["bookedSeconds"].decimalValue()).isEqualByComparingTo(BigDecimal(seconds))
        assertThat(day["utilizationPercent"].decimalValue()).isEqualByComparingTo(BigDecimal(utilization))
    }

    private fun assertFreshness(body: JsonNode) {
        assertThat(body["asOf"].isTextual).isTrue()
        assertThat(body["refreshedAt"].isTextual).isTrue()
        val asOf = Instant.parse(body["asOf"].asText())
        val refreshedAt = Instant.parse(body["refreshedAt"].asText())
        assertThat(refreshedAt).isAfterOrEqualTo(asOf)
    }

    companion object {
        private const val ROOM = "11111111-1111-1111-1111-111111111111"
        private const val PROJECTOR = "22222222-2222-2222-2222-222222222222"

        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
            setCommand("postgres", "-c", "timezone=Pacific/Honolulu")
        }

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
