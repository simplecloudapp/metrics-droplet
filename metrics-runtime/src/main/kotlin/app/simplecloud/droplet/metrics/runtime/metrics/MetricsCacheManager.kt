package app.simplecloud.droplet.metrics.runtime.metrics

import build.buf.gen.simplecloud.metrics.v1.Metric
import build.buf.gen.simplecloud.metrics.v1.MetricRequestMetaFilter
import build.buf.gen.simplecloud.metrics.v1.MetricRequestStep
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class MetricsCacheManager {
    private val logger = LogManager.getLogger(MetricsCacheManager::class.java)

    private data class CacheKey(
        val metricTypes: Set<String>,
        val timeRange: TimeRange,
        val step: MetricRequestStep?,
        val metaFilters: List<MetricRequestMetaFilter>
    )

    private data class TimeRange(
        val from: LocalDateTime?,
        val to: LocalDateTime?
    )

    private data class CacheEntry(
        val metrics: List<Metric>,
        val timestamp: LocalDateTime
    )

    private val aggregateCache = CacheBuilder.newBuilder()
        .maximumSize(50)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .recordStats()
        .build<CacheKey, CacheEntry>()

    companion object {
        private val CACHEABLE_STEPS = setOf(
            MetricRequestStep.HOURLY,
            MetricRequestStep.DAILY,
            MetricRequestStep.MONTHLY
        )

        private val CACHEABLE_RANGES = listOf(
            Duration.ofHours(24),
            Duration.ofDays(7),
            Duration.ofDays(30)
        )
    }

    private fun normalizeTime(time: LocalDateTime, step: MetricRequestStep?): LocalDateTime {
        return when (step) {
            MetricRequestStep.HOURLY -> time.truncatedTo(ChronoUnit.HOURS)
            MetricRequestStep.DAILY -> time.truncatedTo(ChronoUnit.DAYS)
            MetricRequestStep.MONTHLY -> time.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            else -> time
        }
    }

    private fun normalizeTimeRange(from: LocalDateTime?, to: LocalDateTime?, step: MetricRequestStep?): TimeRange {
        if (step !in CACHEABLE_STEPS) {
            return TimeRange(from, to)
        }

        val normalizedTo = to?.let { normalizeTime(it, step) } ?: normalizeTime(LocalDateTime.now(), step)
        val normalizedFrom = from?.let { normalizeTime(it, step) }

        return TimeRange(normalizedFrom, normalizedTo)
    }

    suspend fun getOrLoadMetrics(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep?,
        metaFilters: List<MetricRequestMetaFilter>,
        loader: suspend () -> List<Metric>
    ): List<Metric> = withContext(Dispatchers.IO) {
        val normalizedTimeRange = normalizeTimeRange(from, to, step)
        val cacheKey = CacheKey(metricTypes, normalizedTimeRange, step, metaFilters)

        if (!isCacheable(step, normalizedTimeRange.from, normalizedTimeRange.to)) {
            logger.debug("Not cacheable - step: $step, from: ${normalizedTimeRange.from}, to: ${normalizedTimeRange.to}")
            return@withContext loader()
        }

        val cachedEntry = aggregateCache.getIfPresent(cacheKey)
        when {
            cachedEntry != null &&
                    Duration.between(cachedEntry.timestamp, LocalDateTime.now()).toMinutes() < 5 -> {
                logger.info("Cache hit for metrics query: $cacheKey")
                cachedEntry.metrics
            }
            else -> {
                logger.info("Cache miss for metrics query: $cacheKey")
                val metrics = loader()
                aggregateCache.put(
                    cacheKey, CacheEntry(
                        metrics = metrics,
                        timestamp = LocalDateTime.now()
                    )
                )
                metrics
            }
        }
    }

    private fun isCacheable(
        step: MetricRequestStep?,
        from: LocalDateTime?,
        to: LocalDateTime?
    ): Boolean {
        if (step !in CACHEABLE_STEPS) return false
        if (from == null) return false

        val effectiveTo = to ?: LocalDateTime.now()
        val duration = Duration.between(from, effectiveTo)

        return CACHEABLE_RANGES.any { range ->
            duration <= range &&
                    from >= LocalDateTime.now().minus(range)
        }
    }


}
