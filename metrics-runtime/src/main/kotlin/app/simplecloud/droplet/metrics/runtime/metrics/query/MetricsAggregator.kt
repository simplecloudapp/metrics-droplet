package app.simplecloud.droplet.metrics.runtime.metrics.query

import app.simplecloud.droplet.metrics.runtime.database.Database
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import app.simplecloud.droplet.metrics.runtime.metrics.service.MetricsRepository
import app.simplecloud.droplet.metrics.runtime.metrics.time.TimeWindowCalculator
import build.buf.gen.simplecloud.metrics.v1.MetricRequestMetaFilter
import build.buf.gen.simplecloud.metrics.v1.MetricRequestStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class MetricsAggregator {

    private val logger = LogManager.getLogger(MetricsAggregator::class.java)
    private val paginationHandler = MetricsPaginationHandler()

    suspend fun getAggregatedMetrics(
        database: Database,
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep,
        metaFilters: List<MetricRequestMetaFilter>,
        limit: Int,
        pageToken: String?,
        descending: Boolean,
    ): MetricsRepository.MetricsPaginationResult = withContext(Dispatchers.IO) {
        val timer = MetricsTimer("metrics_aggregation_duration")
        try {
            if (step == MetricRequestStep.UNKNOWN_STEP || step == MetricRequestStep.UNRECOGNIZED) {
                val baseQuery = QueryBuilder.buildRawMetricsQuery(
                    database.context,
                    metricTypes,
                    from,
                    to,
                    metaFilters,
                    descending
                )

                val (query, offset) = paginationHandler.applyPagination(baseQuery, limit, pageToken)

                val result = query.fetch()
                val totalCount = paginationHandler.getTotalCount(database.context, baseQuery)

                return@withContext MetricsRepository.MetricsPaginationResult(
                    metrics = MetricRecordMapper.mapRawRecords(result),
                    nextPageToken = paginationHandler.getNextPageToken(offset, limit, totalCount),
                    totalCount = totalCount
                )
            }

            MetricsRegistry.incrementCounter("metrics_aggregation_requests_total")

            val normalizedTimeRange = normalizeTimeRange(from, to, step)

            val timeWindow = TimeWindowCalculator.calculateTimeWindowSQL(step, database.context.dialect())?: throw IllegalStateException()

            val baseQuery = QueryBuilder.buildAggregatedMetricsQuery(
                database.context,
                metricTypes,
                normalizedTimeRange.from,
                normalizedTimeRange.to,
                metaFilters,
                timeWindow,
                descending
            )

            val (query, offset) = paginationHandler.applyPagination(baseQuery, limit, pageToken)

            val queryTimer = MetricsTimer("metrics_aggregation_query_duration")
            val result = try {
                query.fetch()
            } finally {
                queryTimer.stop()
            }

            val countTimer = MetricsTimer("metrics_aggregation_count_duration")
            val totalCount = try {
                paginationHandler.getTotalCount(database.context, baseQuery)
            } finally {
                countTimer.stop()
            }

            val mappingTimer = MetricsTimer("metrics_aggregation_mapping_duration")
            try {
                MetricsRepository.MetricsPaginationResult(
                    metrics = MetricRecordMapper.mapAggregatedRecords(result),
                    nextPageToken = paginationHandler.getNextPageToken(offset, limit, totalCount),
                    totalCount = totalCount
                )
            } finally {
                mappingTimer.stop()
            }
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("metrics_aggregation_errors_total")
            logger.error("Error during metrics aggregation", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    private fun normalizeTimeRange(
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep
    ): TimeRange {
        val now = LocalDateTime.now()

        val normalizedTo = when {
            to == null -> truncateTime(now, step)
            else -> truncateTime(to, step)
        }

        val normalizedFrom = when {
            from == null -> calculateDefaultFromTime(normalizedTo, step)
            else -> truncateTime(from, step)
        }

        return TimeRange(normalizedFrom, normalizedTo)
    }

    private fun truncateTime(time: LocalDateTime, step: MetricRequestStep): LocalDateTime {
        return when (step) {
            MetricRequestStep.MINUTELY -> time.truncatedTo(ChronoUnit.MINUTES)
            MetricRequestStep.HOURLY -> time.truncatedTo(ChronoUnit.HOURS)
            MetricRequestStep.DAILY -> time.truncatedTo(ChronoUnit.DAYS)
            MetricRequestStep.MONTHLY -> time.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            else -> time
        }
    }

    private fun calculateDefaultFromTime(to: LocalDateTime, step: MetricRequestStep): LocalDateTime {
        return when (step) {
            MetricRequestStep.MINUTELY -> to.minusHours(1)
            MetricRequestStep.HOURLY -> to.minusDays(1)
            MetricRequestStep.DAILY -> to.minusDays(30)
            MetricRequestStep.MONTHLY -> to.minusMonths(12)
            else -> to.minusDays(1)
        }
    }

    data class TimeRange(
        val from: LocalDateTime,
        val to: LocalDateTime
    ) {
        init {
            require(!from.isAfter(to)) { "From date must not be after to date" }
        }
    }

}