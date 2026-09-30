package dev.nikita.booking

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/bookings")
class BookingController(private val service: BookingService) {
    @PostMapping
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: CreateBookingRequest
    ): ResponseEntity<Booking> {
        val result = service.create(key, request)
        return ResponseEntity.created(URI.create("/api/bookings/${result.booking.id}"))
            .header("Idempotency-Replayed", result.replayed.toString())
            .body(result.booking)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Booking = service.get(id)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID): Booking = service.cancel(id)
}
