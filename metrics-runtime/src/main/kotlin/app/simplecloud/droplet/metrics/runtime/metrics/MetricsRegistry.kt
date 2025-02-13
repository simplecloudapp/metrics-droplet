package app.simplecloud.droplet.metrics.runtime.metrics

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.drop
import kotlin.collections.set
import kotlin.time.Duration

object MetricsRegistry {

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val histograms = ConcurrentHashMap<String, MutableList<Duration>>()
    private val gauges = ConcurrentHashMap<String, AtomicLong>()
    private val histogramMutex = Mutex()

    fun incrementCounter(name: String, amount: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(amount)
    }

    suspend fun recordDuration(name: String, duration: Duration) {
        histogramMutex.withLock {
            histograms.computeIfAbsent(name) { mutableListOf() }.add(duration)
            if (histograms[name]!!.size > 1000) {
                histograms[name] = histograms[name]!!.drop(100).toMutableList()
            }
        }
    }

    fun setGauge(name: String, value: Long) {
        gauges.computeIfAbsent(name) { AtomicLong(0) }.set(value)
    }

    fun getMetricsText(): String {
        val sb = StringBuilder()

        counters.forEach { (name, value) ->
            sb.appendLine("# TYPE ${name}_total counter")
            sb.appendLine("${name}_total ${value.get()}")
        }

        histograms.forEach { (name, durations) ->
            sb.appendLine("# TYPE ${name}_duration_ms histogram")
            val sorted = durations.map { it.inWholeMilliseconds }.sorted()
            if (sorted.isNotEmpty()) {
                val count = sorted.size
                val sum = sorted.sum()
                val p50 = sorted[((count * 0.5).toInt()).coerceAtMost(count - 1)]
                val p90 = sorted[((count * 0.9).toInt()).coerceAtMost(count - 1)]
                val p99 = sorted[((count * 0.99).toInt()).coerceAtMost(count - 1)]

                sb.appendLine("${name}_duration_ms{quantile=\"0.5\"} $p50")
                sb.appendLine("${name}_duration_ms{quantile=\"0.9\"} $p90")
                sb.appendLine("${name}_duration_ms{quantile=\"0.99\"} $p99")
                sb.appendLine("${name}_duration_ms_count $count")
                sb.appendLine("${name}_duration_ms_sum $sum")
            }
        }

        gauges.forEach { (name, value) ->
            sb.appendLine("# TYPE ${name} gauge")
            sb.appendLine("$name ${value.get()}")
        }

        return sb.toString()
    }

}