package app.simplecloud.droplet.metrics.runtime.metrics

import app.simplecloud.droplet.api.time.ProtobufTimestamp
import app.simplecloud.droplet.metrics.runtime.database.Database
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS_META
import build.buf.gen.simplecloud.metrics.v1.Metric
import build.buf.gen.simplecloud.metrics.v1.metric
import build.buf.gen.simplecloud.metrics.v1.metricMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.jooq.impl.DSL
import java.time.LocalDateTime
import java.util.*

class MetricsRepository(
    private val database: Database
) {

    private val logger = LogManager.getLogger(MetricsRepository::class.java)

    suspend fun saveMetric(metric: Metric) = withContext(Dispatchers.IO) {
        val uniqueId = UUID.randomUUID().toString()

        database.context.transaction { config ->
            DSL.using(config)
                .insertInto(
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
                .execute()

            val validMetaEntries = metric.metaList.filter {
                !it.dataName.isNullOrBlank() && !it.dataValue.isNullOrBlank()
            }

            if (validMetaEntries.isNotEmpty()) {
                validMetaEntries.forEach { meta ->
                    DSL.using(config)
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
                        .execute()
                }
            }
        }
        logger.info("Saved metric ${metric.uniqueId} of type ${metric.metricType} with value ${metric.metricValue}")
    }


    /**
     * @param metricTypes A set of metric types to filter by. If empty, all metrics will be returned.
     * @param from The start time of the query, if empty, the start time will be the beginning of time.
     * @param to The end time of the query, if empty, the end time will be the end of time.
     * Returns a list of metrics that match the given metric types and timestamps.
     */
    suspend fun getMetrics(metricTypes: Set<String>, from: LocalDateTime?, to: LocalDateTime?): List<Metric> {
        val fromToMessage = "from ${from?.toString()?: "-1"} to ${to?.toString() ?: "-1"}"
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

        return database.context.select()
            .from(METRICS)
            .join(METRICS_META)
            .on(METRICS_META.METRIC_UNIQUE_ID.eq(METRICS.UNIQUE_ID))
            .where(whereCondition)
            .and(fromCondition)
            .and(toCondition)
            .asFlow()
            .toCollection(mutableListOf())
            .groupBy { record -> record.get(METRICS.UNIQUE_ID) }
            .map { (uniqueId, records) ->
                val firstRecord = records.first()
                val metaData = records.associate {
                    it.get(METRICS_META.DATA_NAME) to it.get(METRICS_META.DATA_VALUE)
                }

                metric {
                    this.uniqueId = uniqueId!!
                    this.metricType = firstRecord.get(METRICS.METRIC_TYPE)!!
                    this.metricValue = firstRecord.get(METRICS.METRIC_VALUE)!!.toLong()
                    this.time = ProtobufTimestamp.fromLocalDateTime(firstRecord.get(METRICS.TIME)!!)

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