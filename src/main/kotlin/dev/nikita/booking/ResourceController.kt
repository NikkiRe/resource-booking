package dev.nikita.booking

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/resources")
class ResourceController(private val resources: ResourceRepository, private val bookings: BookingService) {
    @GetMapping
    fun list(): List<Resource> = resources.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Resource = resources.find(id) ?: notFound("Resource")

    @GetMapping("/{id}/availability")
    fun availability(@PathVariable id: UUID, @RequestParam from: Instant, @RequestParam to: Instant): Availability =
        bookings.availability(id, from, to)
}
