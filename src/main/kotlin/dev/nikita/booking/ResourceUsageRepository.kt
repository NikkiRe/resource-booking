package dev.nikita.booking

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ResourceUsageRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun freshness(): Pair<Instant?, Instant?> = jdbc.query(
        "SELECT as_of, refreshed_at FROM resource_usage_refresh_state WHERE singleton", emptyMap<String, Any>()
    ) { rs, _ ->
        rs.getObject("as_of", OffsetDateTime::class.java)?.toInstant() to
            rs.getObject("refreshed_at", OffsetDateTime::class.java)?.toInstant()
    }.single()

    fun days(resourceId: UUID, from: LocalDate, to: LocalDate): List<ResourceUsageDay> = jdbc.query(
        """
        SELECT r.id, r.name, d.day,
               coalesce(u.booking_count, 0) AS booking_count,
               coalesce(u.booked_seconds, 0) AS booked_seconds,
               round(coalesce(u.booked_seconds, 0) * 100 / 86400, 4) AS utilization_percent
        FROM resources r
        CROSS JOIN LATERAL (
            SELECT CAST(:from AS date) + n AS day
            FROM generate_series(0, CAST(:to AS date) - CAST(:from AS date) - 1) AS g(n)
        ) d
        LEFT JOIN resource_daily_usage u ON u.resource_id = r.id AND u.day = d.day
        WHERE r.id = :resourceId
        ORDER BY d.day
        """.trimIndent(),
        mapOf("resourceId" to resourceId, "from" to from, "to" to to)
    ) { rs, _ ->
        ResourceUsageDay(
            rs.getObject("id", UUID::class.java), rs.getString("name"),
            rs.getObject("day", LocalDate::class.java), rs.getLong("booking_count"),
            rs.getBigDecimal("booked_seconds"), rs.getBigDecimal("utilization_percent")
        )
    }
}
