package app.simplecloud.droplet.metrics.runtime.metrics.service

import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import app.simplecloud.droplet.metrics.runtime.metrics.time.TimestampExtensions
import build.buf.gen.simplecloud.metrics.v1.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager

class MetricsService(
    private val repository: MetricsRepository
) : MetricsServiceGrpcKt.MetricsServiceCoroutineImplBase() {

    private val logger = LogManager.getLogger(MetricsService::class.java)

    override suspend fun getMetrics(request: GetMetricsRequest): GetMetricsResponse = withContext(Dispatchers.IO) {
        val timer = MetricsTimer("get_metrics_request_duration")
        try {
            validateRequest(request)
            MetricsRegistry.incrementCounter("get_metrics_requests_total")

            val timeRange = TimestampExtensions.extractTimeRange(request)
            logRequest("getMetrics", request.metricTypesList, timeRange)

            val metrics = repository.getMetrics(
                metricTypes = request.metricTypesList.toSet(),
                from = timeRange.from,
                to = timeRange.to,
                step = request.step,
                metaFilters = request.metaFiltersList ?: emptyList()
            ).metrics

            MetricsRegistry.incrementCounter("get_metrics_success_total")
            getMetricsResponse {
                this.metrics.addAll(metrics)
            }
        } catch (e: Exception) {
            handleError("getMetrics", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    override suspend fun getMetricsPaginated(request: GetMetricsPaginatedRequest): GetMetricsPaginatedResponse =
        withContext(Dispatchers.IO) {
            val timer = MetricsTimer("get_metrics_paginated_request_duration")
            try {
                validatePaginatedRequest(request)
                MetricsRegistry.incrementCounter("get_metrics_paginated_requests_total")

                val timeRange = TimestampExtensions.extractTimeRange(request)
                logRequest("getMetricsPaginated", request.metricTypesList, timeRange)

                val result = repository.getMetrics(
                    metricTypes = request.metricTypesList.toSet(),
                    from = timeRange.from,
                    to = timeRange.to,
                    step = request.step,
                    metaFilters = request.metaFiltersList ?: emptyList(),
                    limit = request.pageSize,
                    pageToken = if (request.pageToken.isNullOrBlank()) null else request.pageToken
                )

                MetricsRegistry.incrementCounter("get_metrics_paginated_success_total")
                getMetricsPaginatedResponse {
                    this.metrics.addAll(result.metrics)
                    this.nextPageToken = result.nextPageToken ?: ""
                    this.totalCount = result.totalCount
                }
            } catch (e: Exception) {
                handleError("getMetricsPaginated", e)
                throw e
            } finally {
                timer.stop()
            }
        }

    override suspend fun recordMetric(request: RecordMetricRequest): RecordMetricResponse = withContext(Dispatchers.IO) {
        val timer = MetricsTimer("record_metric_request_duration")
        try {
            validateRecordRequest(request)
            MetricsRegistry.incrementCounter("record_metric_requests_total")

            logMetricRecord(request.metric)
            repository.saveMetric(request.metric)

            MetricsRegistry.incrementCounter("record_metric_success_total")
            recordMetricResponse {
                this.metric = request.metric
            }
        } catch (e: Exception) {
            handleError("recordMetric", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    private fun validateRequest(request: GetMetricsRequest) {
        if (request.step == MetricRequestStep.UNRECOGNIZED) {
            throw IllegalArgumentException("Unrecognized step type")
        }
    }

    private fun validatePaginatedRequest(request: GetMetricsPaginatedRequest) {
        if (request.step == MetricRequestStep.UNRECOGNIZED) {
            throw IllegalArgumentException("Unrecognized step type")
        }

        require(request.pageSize > 0) {
            "Page size must be greater than 0"
        }
        require(request.pageSize <= MAX_PAGE_SIZE) {
            "Page size must not exceed $MAX_PAGE_SIZE"
        }
    }


    private fun validateRecordRequest(request: RecordMetricRequest) {
        requireNotNull(request.metric) { "Metric must not be null" }
        require(request.metric.metricType.isNotBlank()) { "Metric type must not be blank" }
        require(request.metric.time.seconds > 0) { "Metric time must be set" }
    }

    private fun logRequest(
        operation: String,
        metricTypes: List<String>,
        timeRange: TimestampExtensions.TimeRange
    ) {
        logger.info(
            "$operation request - Types: ${metricTypes.joinToString()}, " +
                    "From: ${timeRange.from}, To: ${timeRange.to}"
        )
    }

    private fun logMetricRecord(metric: Metric) {
        logger.info(
            "Recording metric - Type: ${metric.metricType}, " +
                    "Value: ${metric.metricValue}, " +
                    "Meta: ${metric.metaList.joinToString { "${it.dataName}=${it.dataValue}" }}"
        )
    }

    private fun handleError(operation: String, error: Exception) {
        MetricsRegistry.incrementCounter("metrics_errors_total")
        logger.error("Error in $operation", error)
    }

    companion object {
        private const val MAX_PAGE_SIZE = 1000
    }

}
