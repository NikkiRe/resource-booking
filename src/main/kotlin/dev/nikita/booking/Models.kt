package dev.nikita.booking

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class Resource(val id: UUID, val name: String, val kind: String)

data class CreateBookingRequest(
    val resourceId: UUID,
    @field:NotBlank @field:Size(max = 100)
    val bookedBy: String,
    val startsAt: Instant,
    val endsAt: Instant
)

enum class BookingStatus { ACTIVE, CANCELLED }

data class Booking(
    val id: UUID,
    val resourceId: UUID,
    val bookedBy: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val status: BookingStatus,
    val createdAt: Instant,
    val cancelledAt: Instant? = null
)

data class TimeWindow(val startsAt: Instant, val endsAt: Instant)

data class Availability(
    val resourceId: UUID,
    val from: Instant,
    val to: Instant,
    val free: List<TimeWindow>
)

data class CreateBookingResult(val booking: Booking, val replayed: Boolean)

class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

fun notFound(entity: String): Nothing = throw ApiException(404, "NOT_FOUND", "$entity not found")

fun invalidRequest(message: String): Nothing = throw ApiException(400, "INVALID_REQUEST", message)
