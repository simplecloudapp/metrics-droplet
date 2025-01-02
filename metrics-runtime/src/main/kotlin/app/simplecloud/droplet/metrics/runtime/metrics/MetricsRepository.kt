package app.simplecloud.droplet.metrics.runtime.metrics

import app.simplecloud.droplet.api.time.ProtobufTimestamp
import app.simplecloud.droplet.metrics.runtime.database.Database
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS_META
import build.buf.gen.simplecloud.metrics.v1.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.jooq.impl.DSL
import java.time.Duration
import java.time.LocalDateTime
import org.jooq.Record6
import org.jooq.SelectConnectByStep
import java.util.*

class MetricsRepository(
    private val database: Database
) {

    private val logger = LogManager.getLogger(MetricsRepository::class.java)
    private val cacheManager = MetricsCacheManager()

    suspend fun saveMetric(metric: Metric) = withContext(Dispatchers.IO) {
        val uniqueId = UUID.randomUUID().toString()

        database.context.insertInto(
            METRICS,
            METRICS.UNIQUE_ID,
            METRICS.METRIC_TYPE,
            METRICS.METRIC_VALUE,
            METRICS.TIME
        )
            .values(
                uniqueId,
                metric.metricType,
                metric.metricValue.toInt(),
                ProtobufTimestamp.toLocalDateTime(metric.time)
            )
            .onDuplicateKeyUpdate()
            .set(METRICS.METRIC_TYPE, metric.metricType)
            .set(METRICS.METRIC_VALUE, metric.metricValue.toInt())
            .executeAsync()

        val validMetaEntries = metric.metaList.filter {
            !it.dataName.isNullOrBlank() && !it.dataValue.isNullOrBlank()
        }

        if (validMetaEntries.isNotEmpty()) {
            validMetaEntries.forEach { meta ->
                database.context
                    .insertInto(
                        METRICS_META,
                        METRICS_META.METRIC_UNIQUE_ID,
                        METRICS_META.DATA_NAME,
                        METRICS_META.DATA_VALUE
                    )
                    .values(uniqueId, meta.dataName, meta.dataValue)
                    .onConflict(METRICS_META.METRIC_UNIQUE_ID, METRICS_META.DATA_NAME)
                    .doUpdate()
                    .set(METRICS_META.DATA_VALUE, meta.dataValue)
                    .executeAsync()
            }
        }

        logger.info("Saved metric ${uniqueId} of type ${metric.metricType} with value ${metric.metricValue}")
    }

    /**
     * @param metricTypes A set of metric types to filter by. If empty, all metrics will be returned.
     * @param from The start time of the query, if empty, the start time will be the beginning of time.
     * @param to The end time of the query, if empty, the end time will be the end of time.
     * @param step The step size of the query, if empty, the step size will be 1 day.
     * Returns a list of metrics that match the given metric types and timestamps.
     */
    suspend fun getMetrics(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        step: MetricRequestStep?,
        metaFilters: List<MetricRequestMetaFilter>
    ): List<Metric> {
        val fromToMessage = "from ${from?.toString() ?: "-1"} to ${to?.toString() ?: "-1"}"
        logger.info("Getting metrics${if (metricTypes.isEmpty()) " all" else " of type ${metricTypes.joinToString()}"} $fromToMessage")

        return cacheManager.getOrLoadMetrics(
            metricTypes = metricTypes,
            from = from,
            to = to,
            step = step,
            metaFilters = metaFilters
        ) {
            // This is the loader function that will be called on cache miss
            val timeWindow = calculateTimeWindow(from, to, step)
            val baseQuery = buildBaseQuery(metricTypes, from, to, metaFilters)

            when {
                timeWindow != null -> aggregateMetricsWithWindow(baseQuery, timeWindow)
                else -> aggregateMetricsWithoutWindow(baseQuery)
            }
        }
    }

    private data class TimeWindow(
        val interval: String,
        val formatPattern: String
    )

    private fun calculateTimeWindow(from: LocalDateTime?, to: LocalDateTime?, step: MetricRequestStep?): TimeWindow? {
        if (step == null || step in setOf(MetricRequestStep.UNRECOGNIZED, MetricRequestStep.UNKNOWN_STEP)) {
            return null
        }

        val duration = Duration.between(from ?: LocalDateTime.MIN, to ?: LocalDateTime.now())
        return when (step) {
            MetricRequestStep.MINUTELY -> when {
                duration.toHours() > 24 -> TimeWindow("1 hour", "HH")
                duration.toHours() > 6 -> TimeWindow("30 minutes", "HH:mm")
                else -> TimeWindow("1 minute", "HH:mm")
            }
            MetricRequestStep.HOURLY -> when {
                duration.toDays() > 7 -> TimeWindow("6 hours", "yyyy-MM-dd HH")
                else -> TimeWindow("1 hour", "yyyy-MM-dd HH")
            }
            MetricRequestStep.DAILY -> TimeWindow("1 day", "yyyy-MM-dd")
            MetricRequestStep.MONTHLY -> TimeWindow("1 month", "yyyy-MM")
            else -> null
        }
    }

    private fun buildBaseQuery(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        metaFilters: List<MetricRequestMetaFilter>
    ): SelectConnectByStep<Record6<String?, Int?, LocalDateTime?, String?, String?, String?>> {
        val conditions = mutableListOf<org.jooq.Condition>()

        if (metricTypes.isNotEmpty()) {
            conditions.add(METRICS.METRIC_TYPE.`in`(metricTypes))
        }
        from?.let { conditions.add(METRICS.TIME.greaterOrEqual(it)) }
        to?.let { conditions.add(METRICS.TIME.lessOrEqual(it)) }

        return if (metaFilters.isNotEmpty()) {
            val metaFilterCondition = MetricMetaFilterBuilder.buildMetaFilterCondition(metaFilters)

            // First get the filtered metric IDs
            val filteredMetricIds = database.context
                .select(METRICS_META.METRIC_UNIQUE_ID)
                .from(METRICS_META)
                .where(metaFilterCondition)
                .asTable("filtered_metrics")

            // Main query with both filtered condition and all meta data
            database.context
                .select(
                    METRICS.METRIC_TYPE,
                    METRICS.METRIC_VALUE,
                    METRICS.TIME,
                    METRICS.UNIQUE_ID,
                    METRICS_META.DATA_NAME,
                    METRICS_META.DATA_VALUE
                )
                .from(METRICS)
                .innerJoin(filteredMetricIds)
                .on(METRICS.UNIQUE_ID.eq(filteredMetricIds.field(METRICS_META.METRIC_UNIQUE_ID)))
                .leftJoin(METRICS_META)  // Join again to get all meta data
                .on(METRICS.UNIQUE_ID.eq(METRICS_META.METRIC_UNIQUE_ID))
                .where(DSL.and(conditions))
        } else {
            // Simple query without meta filters
            database.context
                .select(
                    METRICS.METRIC_TYPE,
                    METRICS.METRIC_VALUE,
                    METRICS.TIME,
                    METRICS.UNIQUE_ID,
                    METRICS_META.DATA_NAME,
                    METRICS_META.DATA_VALUE
                )
                .from(METRICS)
                .leftJoin(METRICS_META)
                .on(METRICS_META.METRIC_UNIQUE_ID.eq(METRICS.UNIQUE_ID))
                .where(DSL.and(conditions))
        }
    }

    private suspend fun aggregateMetricsWithWindow(
        query: SelectConnectByStep<Record6<String?, Int?, LocalDateTime?, String?, String?, String?>>,
        timeWindow: TimeWindow
    ): List<Metric> = withContext(Dispatchers.IO) {
        val records = query.fetch()

        records.groupBy { record ->
            // Group by metric type and truncated time based on the window
            val time = record.get(METRICS.TIME, LocalDateTime::class.java)
            val truncatedTime = when {
                timeWindow.interval.contains("hour") -> time.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                timeWindow.interval.contains("minute") -> time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                timeWindow.interval.contains("day") -> time.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                timeWindow.interval.contains("month") -> time.withDayOfMonth(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                else -> time
            }
            record.get(METRICS.METRIC_TYPE) to truncatedTime
        }.map { (groupKey, groupRecords) ->
            val (metricType, timestamp) = groupKey

            Metric.newBuilder().apply {
                uniqueId = UUID.randomUUID().toString()
                this.metricType = metricType
                metricValue = groupRecords.maxOf {
                    it.get(METRICS.METRIC_VALUE, Int::class.java).toLong()
                }
                time = ProtobufTimestamp.fromLocalDateTime(timestamp)

                // Collect unique meta entries
                val metaEntries = groupRecords
                    .mapNotNull { record ->
                        val dataName = record.get(METRICS_META.DATA_NAME, String::class.java)
                        val dataValue = record.get(METRICS_META.DATA_VALUE, String::class.java)
                        if (!dataName.isNullOrEmpty() && !dataValue.isNullOrEmpty()) {
                            MetricMeta.newBuilder()
                                .setDataName(dataName)
                                .setDataValue(dataValue)
                                .build()
                        } else null
                    }
                    .distinctBy { it.dataName }

                addAllMeta(metaEntries)
            }.build()
        }.sortedBy { it.time.seconds }
    }

    private suspend fun aggregateMetricsWithoutWindow(
        query: SelectConnectByStep<Record6<String?, Int?, LocalDateTime?, String?, String?, String?>>
    ): List<Metric> = withContext(Dispatchers.IO) {
        query.fetch()
            .groupBy { it.get(METRICS.UNIQUE_ID) }
            .map { (_, records) ->
                val record = records.first()

                Metric.newBuilder().apply {
                    uniqueId = record.get(METRICS.UNIQUE_ID, String::class.java)
                    metricType = record.get(METRICS.METRIC_TYPE, String::class.java)
                    metricValue = record.get(METRICS.METRIC_VALUE, Int::class.java).toLong()
                    time = ProtobufTimestamp.fromLocalDateTime(
                        record.get(METRICS.TIME, LocalDateTime::class.java)
                    )

                    // Collect unique meta entries
                    val metaEntries = records
                        .mapNotNull { r ->
                            val dataName = r.get(METRICS_META.DATA_NAME, String::class.java)
                            val dataValue = r.get(METRICS_META.DATA_VALUE, String::class.java)
                            if (!dataName.isNullOrEmpty() && !dataValue.isNullOrEmpty()) {
                                MetricMeta.newBuilder()
                                    .setDataName(dataName)
                                    .setDataValue(dataValue)
                                    .build()
                            } else null
                        }
                        .distinctBy { it.dataName }

                    addAllMeta(metaEntries)
                }.build()
            }
    }

}