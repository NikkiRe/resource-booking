package dev.nikita.booking

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ResourceUsageService(
    private val resources: ResourceRepository,
    private val usage: ResourceUsageRepository
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    fun report(resourceId: UUID, from: LocalDate, to: LocalDate): ResourceUsageReport {
        val days = ChronoUnit.DAYS.between(from, to)
        if (from.year !in 1..9999 || to.year !in 1..9999 || days !in 1..31) {
            invalidRequest("Report range must contain 1 to 31 UTC days within years 1 to 9999")
        }
        resources.find(resourceId) ?: notFound("Resource")
        val (asOf, refreshedAt) = usage.freshness()
        return ResourceUsageReport(from, to, asOf, refreshedAt, usage.days(resourceId, from, to))
    }
}
