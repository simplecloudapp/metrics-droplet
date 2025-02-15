package app.simplecloud.droplet.metrics.runtime.metrics.query

import app.simplecloud.droplet.api.time.ProtobufTimestamp
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import build.buf.gen.simplecloud.metrics.v1.Metric
import build.buf.gen.simplecloud.metrics.v1.MetricMeta
import build.buf.gen.simplecloud.metrics.v1.metric
import build.buf.gen.simplecloud.metrics.v1.metricMeta
import org.apache.logging.log4j.LogManager
import org.jooq.Record
import org.jooq.Result
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object MetricRecordMapper {

    private val logger = LogManager.getLogger(MetricRecordMapper::class.java)

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun mapRawRecords(records: Result<out Record>): List<Metric> {
        return records.map { record ->
            buildMetricFromRecord(record, isAggregated = false)
        }
    }

    fun mapAggregatedRecords(records: Result<out Record>): List<Metric> {
        return records.map { record ->
            buildMetricFromRecord(record, isAggregated = true)
        }
    }

    private fun buildMetricFromRecord(record: Record, isAggregated: Boolean): Metric {
        return metric {
            uniqueId = if (isAggregated) {
                UUID.randomUUID().toString()
            } else {
                record.get(METRICS.UNIQUE_ID) ?: UUID.randomUUID().toString()
            }

            metricType = record.get(METRICS.METRIC_TYPE) ?: ""

            metricValue = if (isAggregated) {
                record.get("max_value", Int::class.java)?.toLong() ?: 0L
            } else {
                record.get(METRICS.METRIC_VALUE)?.toLong() ?: 0L
            }

            time = if (isAggregated) {
                val timeWindow = record.get("time_window")?.toString()
                if (timeWindow != null) {
                    val dateTime = when {
                        timeWindow.contains(":") -> try {
                            LocalDateTime.parse(timeWindow, dateTimeFormatter)
                        } catch (e: Exception) {
                            logger.debug("Failed to parse with datetime format: $timeWindow", e)
                            null
                        }
                        else -> try {
                            LocalDate.parse(timeWindow, dateFormatter).atStartOfDay()
                        } catch (e: Exception) {
                            logger.debug("Failed to parse with date format: $timeWindow", e)
                            null
                        }
                    } ?: LocalDateTime.now()

                    ProtobufTimestamp.fromLocalDateTime(dateTime)
                } else {
                    ProtobufTimestamp.fromLocalDateTime(LocalDateTime.now())
                }
            } else {
                val timestamp = record.get(METRICS.TIME)
                if (timestamp != null) {
                    ProtobufTimestamp.fromLocalDateTime(timestamp)
                } else {
                    ProtobufTimestamp.fromLocalDateTime(LocalDateTime.now())
                }
            }

            meta.addAll(
                buildMetaList(
                    record.get("meta_pairs", String::class.java),
                )
            )
        }
    }

    private fun buildMetaList(metaPairs: String?): List<MetricMeta> {
        if (metaPairs.isNullOrEmpty()) {
            return emptyList()
        }

        return try {
            metaPairs.split(",").mapNotNull { pair ->
                val parts = pair.split(":")
                if (parts.size == 2) {
                    metricMeta {
                        dataName = parts[0]
                        dataValue = parts[1]
                    }
                } else null
            }
        } catch (e: Exception) {
            logger.error("Failed to parse meta pairs: $metaPairs", e)
            MetricsRegistry.incrementCounter("meta_list_build_errors_total")
            emptyList()
        }
    }

}