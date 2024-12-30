package app.simplecloud.droplet.metrics.runtime.metrics

import app.simplecloud.droplet.api.time.ProtobufTimestamp
import app.simplecloud.droplet.metrics.runtime.database.Database
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS_META
import build.buf.gen.simplecloud.metrics.v1.Metric
import build.buf.gen.simplecloud.metrics.v1.MetricRequestStep
import build.buf.gen.simplecloud.metrics.v1.metric
import build.buf.gen.simplecloud.metrics.v1.metricMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class MetricsRepository(
    private val database: Database
) {

    private val logger = LogManager.getLogger(MetricsRepository::class.java)

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

        logger.info("Saved metric ${metric.uniqueId} of type ${metric.metricType} with value ${metric.metricValue}")
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
        step: MetricRequestStep?
    ): List<Metric> {
        val fromToMessage = "from ${from?.toString() ?: "-1"} to ${to?.toString() ?: "-1"}"
        if (metricTypes.isEmpty()) {
            logger.info("Getting all metrics $fromToMessage")
        } else {
            logger.info("Getting metrics of type ${metricTypes.joinToString()} $fromToMessage")
        }

        val whereCondition = if (metricTypes.isEmpty()) {
            METRICS.METRIC_TYPE.isNotNull()
        } else {
            METRICS.METRIC_TYPE.`in`(metricTypes)
        }

        val fromCondition = if (from == null) {
            METRICS.TIME.isNotNull()
        } else {
            METRICS.TIME.greaterOrEqual(from)
        }

        val toCondition = if (to == null) {
            METRICS.TIME.isNotNull()
        } else {
            METRICS.TIME.lessOrEqual(to)
        }

        val query = when (step) {
            MetricRequestStep.HOURLY, MetricRequestStep.DAILY, MetricRequestStep.MONTHLY -> database.context
                .select(
                    METRICS.METRIC_TYPE,
                    METRICS.METRIC_VALUE,
                    METRICS.TIME,
                    METRICS.UNIQUE_ID,
                    METRICS_META.DATA_NAME,
                    METRICS_META.DATA_VALUE
                )
                .from(METRICS)
                .join(METRICS_META)
                .on(METRICS_META.METRIC_UNIQUE_ID.eq(METRICS.UNIQUE_ID))
                .where(whereCondition)
                .and(fromCondition)
                .and(toCondition)
                .orderBy(METRICS.TIME)

            else -> database.context
                .select()
                .from(METRICS)
                .join(METRICS_META)
                .on(METRICS_META.METRIC_UNIQUE_ID.eq(METRICS.UNIQUE_ID))
                .where(whereCondition)
                .and(fromCondition)
                .and(toCondition)
        }

        println(query.sql)

        val records = query
            .asFlow()
            .toCollection(mutableListOf())

        return when (step) {
            MetricRequestStep.HOURLY -> {
                records.groupBy { record ->
                    val time = record.get(METRICS.TIME)!!
                    "${record.get(METRICS.METRIC_TYPE)}_${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"))}"
                }.map { (_, groupRecords) ->
                    val firstRecord = groupRecords.first()
                    val maxValue = groupRecords.map { it.get(METRICS.METRIC_VALUE)!!.toLong() }.max()
                    val metaData = groupRecords.associate {
                        it.get(METRICS_META.DATA_NAME) to it.get(METRICS_META.DATA_VALUE)
                    }

                    metric {
                        uniqueId = UUID.randomUUID().toString()
                        metricType = firstRecord.get(METRICS.METRIC_TYPE)!!
                        metricValue = maxValue
                        time = ProtobufTimestamp.fromLocalDateTime(
                            firstRecord.get(METRICS.TIME)!!.withMinute(0).withSecond(0).withNano(0)
                        )
                        meta.addAll(metaData.map { (dataName, dataValue) ->
                            metricMeta {
                                this.dataName = dataName!!
                                this.dataValue = dataValue!!
                            }
                        })
                    }
                }
            }

            MetricRequestStep.DAILY -> {
                records.groupBy { record ->
                    val time = record.get(METRICS.TIME)!!
                    "${record.get(METRICS.METRIC_TYPE)}_${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}"
                }.map { (_, groupRecords) ->
                    val firstRecord = groupRecords.first()
                    val maxValue = groupRecords.map { it.get(METRICS.METRIC_VALUE)!!.toLong() }.max()
                    val metaData = groupRecords.associate {
                        it.get(METRICS_META.DATA_NAME) to it.get(METRICS_META.DATA_VALUE)
                    }

                    metric {
                        uniqueId = UUID.randomUUID().toString()
                        metricType = firstRecord.get(METRICS.METRIC_TYPE)!!
                        metricValue = maxValue
                        time = ProtobufTimestamp.fromLocalDateTime(
                            firstRecord.get(METRICS.TIME)!!.withHour(0).withMinute(0).withSecond(0).withNano(0)
                        )
                        meta.addAll(metaData.map { (dataName, dataValue) ->
                            metricMeta {
                                this.dataName = dataName!!
                                this.dataValue = dataValue!!
                            }
                        })
                    }
                }
            }

            MetricRequestStep.MONTHLY -> {
                records.groupBy { record ->
                    val time = record.get(METRICS.TIME)!!
                    "${record.get(METRICS.METRIC_TYPE)}_${time.format(DateTimeFormatter.ofPattern("yyyy-MM"))}"
                }.map { (_, groupRecords) ->
                    val firstRecord = groupRecords.first()
                    val maxValue = groupRecords.map { it.get(METRICS.METRIC_VALUE)!!.toLong() }.max()
                    val metaData = groupRecords.associate {
                        it.get(METRICS_META.DATA_NAME) to it.get(METRICS_META.DATA_VALUE)
                    }

                    metric {
                        uniqueId = UUID.randomUUID().toString()
                        metricType = firstRecord.get(METRICS.METRIC_TYPE)!!
                        metricValue = maxValue
                        time = ProtobufTimestamp.fromLocalDateTime(
                            firstRecord.get(METRICS.TIME)!!.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0)
                                .withNano(0)
                        )
                        meta.addAll(metaData.map { (dataName, dataValue) ->
                            metricMeta {
                                this.dataName = dataName!!
                                this.dataValue = dataValue!!
                            }
                        })
                    }
                }
            }

            else -> records.groupBy { it.get(METRICS.UNIQUE_ID) }.map { (_, groupRecords) ->
                val firstRecord = groupRecords.first()
                val metaData = groupRecords.associate {
                    it.get(METRICS_META.DATA_NAME) to it.get(METRICS_META.DATA_VALUE)
                }

                metric {
                    uniqueId = firstRecord.get(METRICS.UNIQUE_ID)!!
                    metricType = firstRecord.get(METRICS.METRIC_TYPE)!!
                    metricValue = firstRecord.get(METRICS.METRIC_VALUE)!!.toLong()
                    time = ProtobufTimestamp.fromLocalDateTime(firstRecord.get(METRICS.TIME)!!)
                    meta.addAll(metaData.map { (dataName, dataValue) ->
                        metricMeta {
                            this.dataName = dataName!!
                            this.dataValue = dataValue!!
                        }
                    })
                }
            }

        }
    }

}