package app.simplecloud.droplet.metrics.runtime.metrics.time

import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class MetricsTimer(private val metricName: String) {

    private val startTime = Instant.now()

    suspend fun stop() {
        val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()
        MetricsRegistry.recordDuration(metricName, duration.milliseconds)
    }

}