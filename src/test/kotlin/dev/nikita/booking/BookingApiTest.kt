package dev.nikita.booking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BookingApiTest.TestClock::class)
class BookingApiTest {
    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE bookings, idempotency_keys")
    }

    @Test
    fun `resources and booking can be read`() {
        val resources = http.getForEntity("/api/resources", String::class.java)
        assertThat(resources.statusCode.value()).isEqualTo(200)
        assertThat(json(resources).size()).isEqualTo(2)
        val created = create("read", request())
        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(created.headers.location.toString()).isEqualTo("/api/bookings/${json(created)["id"].asText()}")
        val fetched = http.getForEntity(created.headers.location.toString(), String::class.java)
        assertThat(json(fetched)).isEqualTo(json(created))
    }

    @Test
    fun `overlapping booking returns conflict and rolls back its key`() {
        assertThat(create("first", request()).statusCode.value()).isEqualTo(201)
        val overlap = create("second", request(startsAt = "2030-01-02T10:30:00Z", endsAt = "2030-01-02T11:30:00Z"))
        assertThat(overlap.statusCode.value()).isEqualTo(409)
        assertThat(json(overlap)["code"].asText()).isEqualTo("SLOT_UNAVAILABLE")
        assertThat(count("bookings")).isEqualTo(1)
        assertThat(count("idempotency_keys")).isEqualTo(1)
    }

    @Test
    fun `key from failed conflict can be retried after cancellation`() {
        val occupied = create("occupied", request())
        assertThat(create("retry-later", request()).statusCode.value()).isEqualTo(409)
        http.postForEntity("/api/bookings/${json(occupied)["id"].asText()}/cancel", null, String::class.java)
        val retried = create("retry-later", request())
        assertThat(retried.statusCode.value()).isEqualTo(201)
        assertThat(retried.headers.getFirst("Idempotency-Replayed")).isEqualTo("false")
        assertThat(count("bookings")).isEqualTo(2)
    }

    @Test
    fun `adjacent bookings and different resources do not conflict`() {
        assertThat(create("first", request()).statusCode.value()).isEqualTo(201)
        assertThat(create("adjacent", request(startsAt = "2030-01-02T11:00:00Z", endsAt = "2030-01-02T12:00:00Z")).statusCode.value()).isEqualTo(201)
        assertThat(create("projector", request(resourceId = PROJECTOR)).statusCode.value()).isEqualTo(201)
    }

    @Test
    fun `cancellation is repeatable and frees the interval`() {
        val created = create("first", request())
        val id = json(created)["id"].asText()
        val cancelled = http.postForEntity("/api/bookings/$id/cancel", null, String::class.java)
        val again = http.postForEntity("/api/bookings/$id/cancel", null, String::class.java)
        assertThat(json(cancelled)["status"].asText()).isEqualTo("CANCELLED")
        assertThat(json(again)).isEqualTo(json(cancelled))
        assertThat(create("replacement", request()).statusCode.value()).isEqualTo(201)
    }

    @Test
    fun `same key replays original response even after cancellation`() {
        val created = create("replay", request())
        http.postForEntity("/api/bookings/${json(created)["id"].asText()}/cancel", null, String::class.java)
        val replay = create("replay", request())
        assertThat(replay.statusCode.value()).isEqualTo(201)
        assertThat(replay.headers.getFirst("Idempotency-Replayed")).isEqualTo("true")
        assertThat(json(replay)).isEqualTo(json(created))
        assertThat(count("bookings")).isEqualTo(1)
    }

    @Test
    fun `key cannot be reused with changed payload`() {
        create("same", request())
        val changed = create("same", request(bookedBy = "Another person"))
        assertThat(changed.statusCode.value()).isEqualTo(409)
        assertThat(json(changed)["code"].asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED")
    }

    @Test
    fun `equivalent offsets and trimmed names replay the same booking`() {
        val first = create("normalized", request(bookedBy = " Nikita "))
        val replay = create("normalized", request(startsAt = "2030-01-02T13:00:00+03:00", endsAt = "2030-01-02T14:00:00+03:00"))
        assertThat(json(first)["bookedBy"].asText()).isEqualTo("Nikita")
        assertThat(json(replay)).isEqualTo(json(first))
    }

    @Test
    fun `parallel competing bookings produce one success`() {
        val results = parallel(8) { index -> create("race-$index", request()) }
        assertThat(results.count { it.statusCode.value() == 201 }).isEqualTo(1)
        assertThat(results.count { it.statusCode.value() == 409 }).isEqualTo(7)
        assertThat(count("bookings")).isEqualTo(1)
        assertThat(count("idempotency_keys")).isEqualTo(1)
    }

    @Test
    fun `parallel same-key requests create one booking and replay it`() {
        val results = parallel(8) { create("same-key-race", request()) }
        assertThat(results.map { it.statusCode.value() }).containsOnly(201)
        assertThat(results.map { json(it)["id"].asText() }.distinct()).hasSize(1)
        assertThat(results.count { it.headers.getFirst("Idempotency-Replayed") == "true" }).isEqualTo(7)
        assertThat(count("bookings")).isEqualTo(1)
    }

    @Test
    fun `parallel same-key requests with different payloads reject the loser`() {
        val results = parallel(2) { index -> create("different-payload-race", request(bookedBy = "Person $index")) }
        assertThat(results.map { it.statusCode.value() }).containsExactlyInAnyOrder(201, 409)
        val conflict = results.single { it.statusCode.value() == 409 }
        assertThat(json(conflict)["code"].asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(count("bookings")).isEqualTo(1)
    }

    @Test
    fun `availability clips occupied intervals at query boundaries`() {
        create("early", request(startsAt = "2030-01-02T08:00:00Z", endsAt = "2030-01-02T10:00:00Z"))
        create("late", request(startsAt = "2030-01-02T11:00:00Z", endsAt = "2030-01-02T13:00:00Z"))
        val response = http.getForEntity(
            "/api/resources/$ROOM/availability?from=2030-01-02T09:00:00Z&to=2030-01-02T12:00:00Z", String::class.java
        )
        assertThat(response.statusCode.value()).isEqualTo(200)
        val free = json(response)["free"]
        assertThat(free.size()).isEqualTo(1)
        assertThat(free[0]["startsAt"].asText()).isEqualTo("2030-01-02T10:00:00Z")
        assertThat(free[0]["endsAt"].asText()).isEqualTo("2030-01-02T11:00:00Z")
    }

    @Test
    fun `availability ignores cancelled bookings`() {
        val created = create("cancelled", request())
        http.postForEntity("/api/bookings/${json(created)["id"].asText()}/cancel", null, String::class.java)
        val result = http.getForEntity(
            "/api/resources/$ROOM/availability?from=2030-01-02T09:00:00Z&to=2030-01-02T12:00:00Z", String::class.java
        )
        assertThat(json(result)["free"].size()).isEqualTo(1)
        assertThat(json(result)["free"][0]["startsAt"].asText()).isEqualTo("2030-01-02T09:00:00Z")
        assertThat(json(result)["free"][0]["endsAt"].asText()).isEqualTo("2030-01-02T12:00:00Z")
    }

    @Test
    fun `fully occupied window has no free intervals`() {
        create("occupied", request())
        val result = http.getForEntity(
            "/api/resources/$ROOM/availability?from=2030-01-02T10:15:00Z&to=2030-01-02T10:45:00Z", String::class.java
        )
        assertThat(result.statusCode.value()).isEqualTo(200)
        assertThat(json(result)["free"].size()).isZero()
    }

    @Test
    fun `invalid intervals are rejected without storing anything`() {
        val invalid = listOf(
            request(endsAt = "2030-01-02T10:00:00Z"),
            request(endsAt = "2030-01-02T09:00:00Z"),
            request(endsAt = "2030-01-03T11:00:00Z"),
            request(startsAt = "2029-12-31T10:00:00Z", endsAt = "2029-12-31T11:00:00Z"),
            request(startsAt = "2030-01-02T10:00:00.0000001Z")
        )
        invalid.forEachIndexed { index, request ->
            val result = create("invalid-$index", request)
            assertThat(result.statusCode.value()).isEqualTo(400)
            assertThat(result.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        }
        assertThat(count("bookings")).isZero()
        assertThat(count("idempotency_keys")).isZero()
    }

    @Test
    fun `missing fields unknown fields and blank name return bad request`() {
        assertThat(create("missing", request() - "endsAt").statusCode.value()).isEqualTo(400)
        assertThat(create("unknown", request() + ("unexpected" to true)).statusCode.value()).isEqualTo(400)
        assertThat(create("blank", request(bookedBy = "  ")).statusCode.value()).isEqualTo(400)
        assertThat(create("long", request(bookedBy = "x".repeat(101))).statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `idempotency header is required and validated`() {
        assertThat(create(null, request()).statusCode.value()).isEqualTo(400)
        assertThat(create("bad key", request()).statusCode.value()).isEqualTo(400)
        assertThat(create("x".repeat(129), request()).statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `missing resource booking and cancellation return not found`() {
        val missing = UUID.randomUUID().toString()
        assertThat(create("missing-resource", request(resourceId = missing)).statusCode.value()).isEqualTo(404)
        assertThat(http.getForEntity("/api/resources/$missing", String::class.java).statusCode.value()).isEqualTo(404)
        assertThat(http.getForEntity("/api/bookings/$missing", String::class.java).statusCode.value()).isEqualTo(404)
        assertThat(http.postForEntity("/api/bookings/$missing/cancel", null, String::class.java).statusCode.value()).isEqualTo(404)
        assertThat(count("idempotency_keys")).isZero()
    }

    @Test
    fun `availability validates its window`() {
        val same = http.getForEntity("/api/resources/$ROOM/availability?from=2030-01-02T10:00:00Z&to=2030-01-02T10:00:00Z", String::class.java)
        val tooLong = http.getForEntity("/api/resources/$ROOM/availability?from=2030-01-01T00:00:00Z&to=2030-03-01T00:00:00Z", String::class.java)
        assertThat(same.statusCode.value()).isEqualTo(400)
        assertThat(tooLong.statusCode.value()).isEqualTo(400)
    }

    private fun create(key: String?, body: Map<String, Any>): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            key?.let { set("Idempotency-Key", it) }
        }
        return http.exchange("/api/bookings", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    private fun request(
        resourceId: String = ROOM,
        bookedBy: String = "Nikita",
        startsAt: String = "2030-01-02T10:00:00Z",
        endsAt: String = "2030-01-02T11:00:00Z"
    ): Map<String, Any> = mapOf("resourceId" to resourceId, "bookedBy" to bookedBy, "startsAt" to startsAt, "endsAt" to endsAt)

    private fun json(response: ResponseEntity<String>): JsonNode = mapper.readTree(response.body)

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun parallel(count: Int, action: (Int) -> ResponseEntity<String>): List<ResponseEntity<String>> {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(count).use { executor ->
            val futures = (0 until count).map { index ->
                executor.submit(Callable {
                    start.await()
                    action(index)
                })
            }
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestClock {
        @Bean @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
    }

    companion object {
        private const val ROOM = "11111111-1111-1111-1111-111111111111"
        private const val PROJECTOR = "22222222-2222-2222-2222-222222222222"

        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
