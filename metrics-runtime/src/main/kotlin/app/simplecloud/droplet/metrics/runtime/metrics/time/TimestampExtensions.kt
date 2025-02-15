package app.simplecloud.droplet.metrics.runtime.metrics.time

import app.simplecloud.droplet.api.time.ProtobufTimestamp
import build.buf.gen.simplecloud.metrics.v1.GetMetricsPaginatedRequest
import build.buf.gen.simplecloud.metrics.v1.GetMetricsRequest
import com.google.protobuf.Timestamp
import java.time.LocalDateTime

object TimestampExtensions {

    fun Timestamp.toLocalDateTimeOrNull(): LocalDateTime? {
        return if (this.seconds <= 0L && this.nanos <= 0) {
            null
        } else {
            ProtobufTimestamp.toLocalDateTime(this)
        }
    }

    data class TimeRange(
        val from: LocalDateTime?,
        val to: LocalDateTime?
    )

    fun extractTimeRange(request: GetMetricsRequest): TimeRange {
        return TimeRange(
            from = request.from.toLocalDateTimeOrNull(),
            to = request.to.toLocalDateTimeOrNull()
        )
    }

    fun extractTimeRange(request: GetMetricsPaginatedRequest): TimeRange {
        return TimeRange(
            from = request.from.toLocalDateTimeOrNull(),
            to = request.to.toLocalDateTimeOrNull()
        )
    }

}