package app.simplecloud.droplet.metrics.runtime.metrics.query

import app.simplecloud.droplet.metrics.runtime.database.Database
import app.simplecloud.droplet.metrics.runtime.metrics.query.MetricRecordMapper
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import app.simplecloud.droplet.metrics.runtime.metrics.service.MetricsRepository
import build.buf.gen.simplecloud.metrics.v1.MetricRequestMetaFilter
import build.buf.gen.simplecloud.metrics.v1.MetricRequestStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.time.LocalDateTime

class MetricsQueryExecutor(
    private val database: Database
) {

    private val logger = LogManager.getLogger(MetricsQueryExecutor::class.java)
    private val paginationHandler = MetricsPaginationHandler()
    private val metricsAggregator = MetricsAggregator()

    suspend fun executeQuery(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep?,
        metaFilters: List<MetricRequestMetaFilter>,
        limit: Int,
        pageToken: String?
    ): MetricsRepository.MetricsPaginationResult {
        val timer = MetricsTimer("query_execution_duration")
        try {
            MetricsRegistry.incrementCounter("query_executions_total")

            return when {
                step != null && step != MetricRequestStep.UNKNOWN_STEP ->
                    metricsAggregator.getAggregatedMetrics(
                        database = database,
                        metricTypes = metricTypes,
                        from = from,
                        to = to,
                        step = step,
                        metaFilters = metaFilters,
                        limit = limit,
                        pageToken = pageToken,
                        descending = metricTypes.size == 1 && metricTypes.contains("ACTIVITY_LOG")
                    )
                else ->
                    executeRawQuery(metricTypes, from, to, metaFilters, limit, pageToken)
            }
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("query_execution_errors_total")
            logger.error("Error executing query", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    private suspend fun executeRawQuery(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        metaFilters: List<MetricRequestMetaFilter>,
        limit: Int,
        pageToken: String?
    ): MetricsRepository.MetricsPaginationResult = withContext(Dispatchers.IO) {
        val timer = MetricsTimer("raw_query_execution_duration")
        try {
            MetricsRegistry.incrementCounter("raw_query_executions_total")

            val baseQuery = QueryBuilder.buildRawMetricsQuery(
                database.context,
                metricTypes,
                from,
                to,
                metaFilters,
                metricTypes.size == 1 && metricTypes.contains("ACTIVITY_LOG")
            )

            val (query, offset) = paginationHandler.applyPagination(baseQuery, limit, pageToken)

            val result = query.fetch()
            val totalCount = paginationHandler.getTotalCount(database.context, baseQuery)

            MetricsRepository.MetricsPaginationResult(
                metrics = MetricRecordMapper.mapRawRecords(result),
                nextPageToken = paginationHandler.getNextPageToken(offset, limit, totalCount),
                totalCount = totalCount
            )
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("raw_query_execution_errors_total")
            logger.error("Error executing raw query", e)
            throw e
        } finally {
            timer.stop()
        }
    }

}
