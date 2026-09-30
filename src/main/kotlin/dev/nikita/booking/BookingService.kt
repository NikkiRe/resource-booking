package dev.nikita.booking

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.validation.Validator
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class BookingService(
    private val bookings: BookingRepository,
    private val resources: ResourceRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val validator: Validator
) {
    fun get(id: UUID): Booking = bookings.find(id) ?: notFound("Booking")

    @Transactional
    fun create(key: String, request: CreateBookingRequest): CreateBookingResult {
        if (!key.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
            invalidRequest("Idempotency-Key must contain 1 to 128 letters, digits, dots, underscores, colons or hyphens")
        }
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            invalidRequest(violations.map { "${it.propertyPath}: ${it.message}" }.sorted().joinToString("; "))
        }
        val command = request.copy(bookedBy = request.bookedBy.trim())
        if (command.bookedBy.isBlank()) invalidRequest("bookedBy must contain a name")
        val hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(command))
        )
        if (!bookings.claimKey(key, hash)) {
            val (savedHash, json) = bookings.savedResponse(key)
            if (savedHash != hash) {
                throw ApiException(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for a different request")
            }
            return CreateBookingResult(objectMapper.readValue(json), true)
        }

        validateWindow(command.startsAt, command.endsAt, Duration.ofHours(24))
        if (!command.startsAt.isAfter(clock.instant())) invalidRequest("Booking must start in the future")
        resources.lock(command.resourceId) ?: notFound("Resource")

        val booking = try {
            bookings.insert(Booking(
                UUID.randomUUID(), command.resourceId, command.bookedBy,
                command.startsAt, command.endsAt, BookingStatus.ACTIVE,
                clock.instant().truncatedTo(ChronoUnit.MICROS)
            ))
        } catch (exception: DataIntegrityViolationException) {
            if ((exception.mostSpecificCause as? SQLException)?.sqlState == "23P01") {
                throw ApiException(409, "SLOT_UNAVAILABLE", "Resource is already booked for this interval")
            }
            throw exception
        }
        bookings.saveResponse(key, objectMapper.writeValueAsString(booking))
        return CreateBookingResult(booking, false)
    }

    @Transactional
    fun cancel(id: UUID): Booking = bookings.cancel(id, clock.instant().truncatedTo(ChronoUnit.MICROS)) ?: get(id)

    fun availability(resourceId: UUID, from: Instant, to: Instant): Availability {
        validateWindow(from, to, Duration.ofDays(31))
        resources.find(resourceId) ?: notFound("Resource")
        val free = mutableListOf<TimeWindow>()
        var cursor = from
        for (booking in bookings.overlapping(resourceId, from, to)) {
            val startsAt = maxOf(booking.startsAt, from)
            val endsAt = minOf(booking.endsAt, to)
            if (cursor < startsAt) free.add(TimeWindow(cursor, startsAt))
            cursor = maxOf(cursor, endsAt)
        }
        if (cursor < to) free.add(TimeWindow(cursor, to))
        return Availability(resourceId, from, to, free)
    }

    private fun validateWindow(from: Instant, to: Instant, maxDuration: Duration) {
        if (from.nano % 1000 != 0 || to.nano % 1000 != 0) invalidRequest("Time precision must not exceed 6 decimal places")
        if (from >= to) invalidRequest("Interval end must be after its start")
        if (from < Instant.parse("0001-01-01T00:00:00Z") || to > Instant.parse("9999-12-31T23:59:59.999999Z")) {
            invalidRequest("Date must be between years 1 and 9999")
        }
        if (Duration.between(from, to) > maxDuration) invalidRequest("Interval exceeds ${maxDuration.toHours()} hours")
    }
}
