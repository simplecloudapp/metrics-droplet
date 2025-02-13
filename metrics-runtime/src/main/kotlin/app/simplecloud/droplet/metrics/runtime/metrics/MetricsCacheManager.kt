package app.simplecloud.droplet.metrics.runtime.metrics

import app.simplecloud.droplet.metrics.runtime.metrics.service.MetricsRepository
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import build.buf.gen.simplecloud.metrics.v1.MetricRequestMetaFilter
import build.buf.gen.simplecloud.metrics.v1.MetricRequestStep
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.logging.log4j.LogManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class MetricsCacheManager {

    private val logger = LogManager.getLogger(MetricsCacheManager::class.java)
    private val cacheMutex = Mutex()

    private data class CacheKey(
        val metricTypes: Set<String>,
        val timeRange: TimeRange,
        val step: MetricRequestStep?,
        val metaFilters: List<MetricRequestMetaFilter>,
        val limit: Int,
        val pageToken: String?
    ) {
        data class TimeRange(
            val from: LocalDateTime?,
            val to: LocalDateTime?
        )
    }

    private data class CacheEntry(
        val result: MetricsRepository.MetricsPaginationResult,
        val timestamp: LocalDateTime,
        val expiresAt: LocalDateTime
    )

    private val cache = CacheBuilder.newBuilder()
        .maximumSize(MAX_CACHE_SIZE)
        .expireAfterWrite(DEFAULT_CACHE_DURATION.toMinutes(), TimeUnit.MINUTES)
        .recordStats()
        .removalListener<CacheKey, CacheEntry> { notification ->
            MetricsRegistry.incrementCounter("cache_evictions_total")
            logger.debug(
                "Cache entry removed: cause=${notification.cause}, " +
                        "key=${notification.key}, wasEvicted=${notification.wasEvicted()}"
            )
        }
        .build<CacheKey, CacheEntry>()

    suspend fun getOrLoadMetrics(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep?,
        metaFilters: List<MetricRequestMetaFilter>,
        limit: Int = -1,
        pageToken: String? = null,
        loader: suspend () -> MetricsRepository.MetricsPaginationResult
    ): MetricsRepository.MetricsPaginationResult {
        val timer = MetricsTimer("cache_operation_duration")
        try {
            if (!isCacheable(step, from, to)) {
                MetricsRegistry.incrementCounter("cache_bypass_total")
                logger.debug("Request not cacheable: step=$step, from=$from, to=$to")
                return loader()
            }

            val cacheKey = createCacheKey(metricTypes, from, to, step, metaFilters, limit, pageToken)

            cache.getIfPresent(cacheKey)?.let { entry ->
                if (!isExpired(entry)) {
                    MetricsRegistry.incrementCounter("cache_hits_total")
                    logger.debug("Cache hit for key: $cacheKey")
                    return entry.result
                }
            }

            MetricsRegistry.incrementCounter("cache_misses_total")
            return cacheMutex.withLock {
                cache.getIfPresent(cacheKey)?.let { entry ->
                    if (!isExpired(entry)) {
                        MetricsRegistry.incrementCounter("cache_hits_after_lock_total")
                        return@withLock entry.result
                    }
                }

                val result = loader()
                val entry = CacheEntry(
                    result = result,
                    timestamp = LocalDateTime.now(),
                    expiresAt = calculateExpiryTime(step)
                )

                cache.put(cacheKey, entry)
                updateCacheMetrics()

                result
            }
        } finally {
            timer.stop()
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

        return CACHEABLE_RANGES[step]?.let { maxRange ->
            duration <= maxRange && from >= LocalDateTime.now().minus(maxRange)
        } ?: false
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        return LocalDateTime.now().isAfter(entry.expiresAt)
    }

    private fun calculateExpiryTime(step: MetricRequestStep?): LocalDateTime {
        val duration = when (step) {
            MetricRequestStep.MINUTELY -> Duration.ofMinutes(1)
            MetricRequestStep.HOURLY -> Duration.ofMinutes(5)
            MetricRequestStep.DAILY -> Duration.ofMinutes(15)
            MetricRequestStep.MONTHLY -> Duration.ofMinutes(60)
            else -> DEFAULT_CACHE_DURATION
        }
        return LocalDateTime.now().plus(duration)
    }

    private fun createCacheKey(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep?,
        metaFilters: List<MetricRequestMetaFilter>,
        limit: Int,
        pageToken: String?
    ): CacheKey = CacheKey(
        metricTypes = metricTypes,
        timeRange = CacheKey.TimeRange(from, to),
        step = step,
        metaFilters = metaFilters,
        limit = limit,
        pageToken = pageToken
    )

    private fun updateCacheMetrics() {
        val stats = cache.stats()
        MetricsRegistry.setGauge("cache_size", cache.size())
        MetricsRegistry.setGauge("cache_hit_rate", (stats.hitRate() * 100).toLong())
        MetricsRegistry.setGauge("cache_miss_rate", (stats.missRate() * 100).toLong())
        MetricsRegistry.setGauge("cache_eviction_count", stats.evictionCount())
        MetricsRegistry.setGauge("cache_load_exception_count", stats.loadExceptionCount())
    }

    companion object {
        private val DEFAULT_CACHE_DURATION = Duration.ofMinutes(5)
        private const val MAX_CACHE_SIZE = 1000L

        private val CACHEABLE_STEPS = setOf(
            MetricRequestStep.MINUTELY,
            MetricRequestStep.HOURLY,
            MetricRequestStep.DAILY,
            MetricRequestStep.MONTHLY
        )

        private val CACHEABLE_RANGES = mapOf(
            MetricRequestStep.MINUTELY to Duration.ofHours(1),
            MetricRequestStep.HOURLY to Duration.ofHours(24),
            MetricRequestStep.DAILY to Duration.ofDays(7),
            MetricRequestStep.MONTHLY to Duration.ofDays(30)
        )
    }

}
