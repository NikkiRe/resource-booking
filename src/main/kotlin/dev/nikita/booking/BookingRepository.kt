package dev.nikita.booking

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.util.UUID

@Repository
class BookingRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val mapper = RowMapper { rs, _ ->
        Booking(
            rs.getObject("id", UUID::class.java),
            rs.getObject("resource_id", UUID::class.java),
            rs.getString("booked_by"),
            rs.getObject("starts_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("ends_at", OffsetDateTime::class.java).toInstant(),
            BookingStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("cancelled_at", OffsetDateTime::class.java)?.toInstant()
        )
    }

    fun find(id: UUID): Booking? = jdbc.query(
        "SELECT * FROM bookings WHERE id = :id", mapOf("id" to id), mapper
    ).firstOrNull()

    fun insert(booking: Booking): Booking = jdbc.query(
        """
        INSERT INTO bookings (id, resource_id, booked_by, starts_at, ends_at, created_at)
        VALUES (:id, :resourceId, :bookedBy, :startsAt, :endsAt, :createdAt)
        RETURNING *
        """.trimIndent(),
        mapOf(
            "id" to booking.id,
            "resourceId" to booking.resourceId,
            "bookedBy" to booking.bookedBy,
            "startsAt" to booking.startsAt.atOffset(UTC),
            "endsAt" to booking.endsAt.atOffset(UTC),
            "createdAt" to booking.createdAt.atOffset(UTC)
        ),
        mapper
    ).single()

    fun cancel(id: UUID, now: Instant): Booking? = jdbc.query(
        """
        UPDATE bookings SET status = 'CANCELLED', cancelled_at = :now
        WHERE id = :id AND status = 'ACTIVE'
        RETURNING *
        """.trimIndent(),
        mapOf("id" to id, "now" to now.atOffset(UTC)), mapper
    ).firstOrNull()

    fun overlapping(resourceId: UUID, from: Instant, to: Instant): List<Booking> = jdbc.query(
        """
        SELECT * FROM bookings
        WHERE resource_id = :resourceId AND status = 'ACTIVE'
          AND period && tstzrange(:from, :to, '[)')
        ORDER BY starts_at
        """.trimIndent(),
        mapOf("resourceId" to resourceId, "from" to from.atOffset(UTC), "to" to to.atOffset(UTC)), mapper
    )

    fun claimKey(key: String, hash: String): Boolean = jdbc.update(
        """
        INSERT INTO idempotency_keys (request_key, request_hash) VALUES (:key, :hash)
        ON CONFLICT (request_key) DO NOTHING
        """.trimIndent(), mapOf("key" to key, "hash" to hash)
    ) == 1

    fun savedResponse(key: String): Pair<String, String> = jdbc.query(
        "SELECT request_hash, response::text FROM idempotency_keys WHERE request_key = :key",
        mapOf("key" to key)
    ) { rs, _ -> rs.getString("request_hash") to checkNotNull(rs.getString("response")) }.single()

    fun saveResponse(key: String, json: String) {
        check(jdbc.update(
            "UPDATE idempotency_keys SET response = CAST(:json AS jsonb) WHERE request_key = :key",
            mapOf("key" to key, "json" to json)
        ) == 1)
    }
}
