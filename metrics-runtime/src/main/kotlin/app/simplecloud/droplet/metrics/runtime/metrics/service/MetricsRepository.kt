package app.simplecloud.droplet.metrics.runtime.metrics.service

import app.simplecloud.droplet.metrics.runtime.database.Database
import app.simplecloud.droplet.metrics.runtime.metrics.query.MetricWriter
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsCacheManager
import app.simplecloud.droplet.metrics.runtime.metrics.query.MetricsQueryExecutor
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import build.buf.gen.simplecloud.metrics.v1.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.time.LocalDateTime

class MetricsRepository(
    private val database: Database
) {

    private val logger = LogManager.getLogger(MetricsRepository::class.java)
    private val cacheManager = MetricsCacheManager()
    private val queryExecutor = MetricsQueryExecutor(database)
    private val metricWriter = MetricWriter(database)

    data class MetricsPaginationResult(
        val metrics: List<Metric>,
        val nextPageToken: String?,
        val totalCount: Long
    )

    suspend fun saveMetric(metric: Metric) = withContext(Dispatchers.IO) {
        val timer = MetricsTimer("metrics_save_duration")
        try {
            metricWriter.saveMetric(metric)
            MetricsRegistry.incrementCounter("metrics_saved_total")
            logger.debug("Saved metric of type ${metric.metricType}")
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("metrics_save_errors_total")
            logger.error("Failed to save metric of type ${metric.metricType}", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    suspend fun getMetrics(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep?,
        metaFilters: List<MetricRequestMetaFilter>,
        limit: Int = -1,
        pageToken: String? = null
    ): MetricsPaginationResult {
        val timer = MetricsTimer("metrics_query_duration")
        try {
            logger.debug(
                "Getting metrics - types: ${metricTypes.joinToString()}, " +
                        "from: $from, to: $to, step: $step, " +
                        "metaFilters: ${metaFilters.size}, limit: $limit"
            )

            return cacheManager.getOrLoadMetrics(
                metricTypes = metricTypes,
                from = from,
                to = to,
                step = step,
                metaFilters = metaFilters,
                limit = limit,
                pageToken = pageToken
            ) {
                queryExecutor.executeQuery(
                    metricTypes = metricTypes,
                    from = from,
                    to = to,
                    step = step,
                    metaFilters = metaFilters,
                    limit = limit,
                    pageToken = pageToken
                )
            }
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("metrics_query_errors_total")
            logger.error("Error getting metrics", e)
            throw e
        } finally {
            timer.stop()
        }
    }

}
