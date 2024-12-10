package app.simplecloud.droplet.metrics.runtime.metrics

import app.simplecloud.droplet.api.time.ProtobufTimestamp
import build.buf.gen.simplecloud.metrics.v1.*
import java.time.LocalDateTime

class MetricsService(
    private val repository: MetricsRepository
) : MetricsServiceGrpcKt.MetricsServiceCoroutineImplBase() {

    override suspend fun getMetrics(request: GetMetricsRequest): GetMetricsResponse {
        val metrics = repository.getMetrics(
            request.metricTypesList.toSet(),
            getFrom(request),
            getTo(request)
        )
        return getMetricsResponse {
            this.metrics.addAll(metrics)
        }
    }

    override suspend fun recordMetric(request: RecordMetricRequest): RecordMetricResponse {
        repository.saveMetric(request.metric)
        return recordMetricResponse {
            this.metric = request.metric
        }
    }

    private fun getFrom(request: GetMetricsRequest): LocalDateTime? {
        return if (request.from.seconds == 0L && request.from.nanos.toLong() == 0L) {
            null
        } else {
            ProtobufTimestamp.toLocalDateTime(request.from)
        }
    }

    private fun getTo(request: GetMetricsRequest): LocalDateTime? {
        return if (request.to.seconds == 0L && request.to.nanos.toLong() == 0L) {
            null
        } else {
            ProtobufTimestamp.toLocalDateTime(request.to)
        }
    }

}