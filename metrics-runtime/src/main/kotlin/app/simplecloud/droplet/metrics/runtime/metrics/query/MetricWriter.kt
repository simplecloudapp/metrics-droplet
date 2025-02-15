package app.simplecloud.droplet.metrics.runtime.metrics.query

import app.simplecloud.droplet.api.time.ProtobufTimestamp
import app.simplecloud.droplet.metrics.runtime.database.Database
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS_META
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import build.buf.gen.simplecloud.metrics.v1.Metric
import build.buf.gen.simplecloud.metrics.v1.MetricMeta
import org.apache.logging.log4j.LogManager
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

class MetricWriter(private val database: Database) {

    private val logger = LogManager.getLogger(MetricWriter::class.java)

    suspend fun saveMetric(metric: Metric) {
        val uniqueId = UUID.randomUUID().toString()
        val timer = MetricsTimer("metric_write_duration")

        try {
            val validMetaEntries = validateMetaEntries(metric.metaList)

            insertMetric(database.context, uniqueId, metric)
            if (validMetaEntries.isNotEmpty()) {
                insertMetaData(database.context, uniqueId, validMetaEntries)

                if (metric.metricType.contains("ACTIVITY", true)) {
                    logger.info("M: ${validMetaEntries.joinToString { it.dataName }}")
                }
            }

            MetricsRegistry.incrementCounter("metrics_write_success_total")
            logger.debug("Successfully saved metric $uniqueId of type ${metric.metricType}")
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("metrics_write_error_total")
            logger.error("Failed to save metric $uniqueId of type ${metric.metricType}", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    private fun insertMetric(
        ctx: DSLContext,
        uniqueId: String,
        metric: Metric
    ) {
        try {
            ctx.insertInto(
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
                .set(METRICS.TIME, ProtobufTimestamp.toLocalDateTime(metric.time))
                .execute()

            MetricsRegistry.incrementCounter("metric_inserts_total")
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("metric_insert_errors_total")
            throw e
        }
    }

    private fun insertMetaData(
        ctx: DSLContext,
        metricId: String,
        metaEntries: List<MetricMeta>
    ) {
        try {
            metaEntries.forEach { meta ->
                ctx.insertInto(
                    METRICS_META,
                    METRICS_META.METRIC_UNIQUE_ID,
                    METRICS_META.DATA_NAME,
                    METRICS_META.DATA_VALUE
                )
                    .values(metricId, meta.dataName, meta.dataValue)
                    .onDuplicateKeyUpdate()
                    .set(METRICS_META.DATA_VALUE, meta.dataValue)
                    .execute()
            }
            MetricsRegistry.incrementCounter("metric_meta_inserts_total", metaEntries.size.toLong())
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("metric_meta_insert_errors_total")
            throw e
        }
    }

    private fun validateMetaEntries(metaList: List<MetricMeta>): List<MetricMeta> {
        return metaList.filter { meta ->
            val isValid = !meta.dataName.isNullOrBlank() && !meta.dataValue.isNullOrBlank()
            if (!isValid) {
                MetricsRegistry.incrementCounter("invalid_meta_entries_total")
                logger.warn("Skipping invalid meta entry: name=${meta.dataName}, value=${meta.dataValue}")
            }
            isValid
        }
    }

}
