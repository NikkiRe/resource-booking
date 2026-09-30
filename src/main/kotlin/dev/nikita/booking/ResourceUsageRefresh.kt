package dev.nikita.booking

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Component
class ResourceUsageRefresh(private val jdbc: JdbcTemplate) {
    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 30)
    fun refresh(): Boolean {
        jdbc.execute("SET LOCAL lock_timeout = '500ms'")
        jdbc.execute("SET LOCAL statement_timeout = '25s'")
        val claim = jdbc.query(
            "SELECT pg_try_advisory_xact_lock(742335819001) AS acquired, statement_timestamp() AS as_of"
        ) { rs, _ -> rs.getBoolean("acquired") to rs.getObject("as_of", OffsetDateTime::class.java) }.single()
        if (!claim.first) return false
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY resource_daily_usage")
        jdbc.update(
            "UPDATE resource_usage_refresh_state SET as_of = ?, refreshed_at = clock_timestamp() WHERE singleton",
            claim.second
        )
        return true
    }
}

@Configuration
@EnableScheduling
class ResourceUsageScheduling

@Component
@ConditionalOnProperty(prefix = "analytics.refresh", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ResourceUsageScheduler(private val refresh: ResourceUsageRefresh) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${analytics.refresh.delay:PT1M}", initialDelayString = "\${analytics.refresh.initial-delay:PT1S}")
    fun update() {
        try {
            refresh.refresh()
        } catch (error: Exception) {
            log.warn("Resource usage refresh failed ({})", error.javaClass.simpleName)
        }
    }
}
