package dev.nikita.booking

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ResourceUsageDay(
    val resourceId: UUID,
    val resourceName: String,
    val day: LocalDate,
    val bookingCount: Long,
    val bookedSeconds: BigDecimal,
    val utilizationPercent: BigDecimal
)

data class ResourceUsageReport(
    val from: LocalDate,
    val to: LocalDate,
    val asOf: Instant?,
    val refreshedAt: Instant?,
    val days: List<ResourceUsageDay>
)
