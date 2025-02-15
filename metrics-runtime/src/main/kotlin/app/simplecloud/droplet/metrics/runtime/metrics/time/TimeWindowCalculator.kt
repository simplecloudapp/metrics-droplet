package app.simplecloud.droplet.metrics.runtime.metrics.time

import build.buf.gen.simplecloud.metrics.v1.MetricRequestStep
import org.jooq.SQLDialect

object TimeWindowCalculator {

    fun calculateTimeWindowSQL(step: MetricRequestStep, dialect: SQLDialect): String? {
        if (step in setOf(MetricRequestStep.UNRECOGNIZED, MetricRequestStep.UNKNOWN_STEP)) {
            return null
        }

        return when (dialect) {
            SQLDialect.SQLITE -> calculateSQLiteTimeWindow(step)
            SQLDialect.POSTGRES -> calculatePostgresTimeWindow(step)
            else -> throw UnsupportedOperationException("Unsupported SQL dialect: $dialect")
        }
    }

    private fun calculateSQLiteTimeWindow(step: MetricRequestStep): String = when (step) {
        MetricRequestStep.MINUTELY ->
            "strftime('%Y-%m-%d %H:%M:00', time)"
        MetricRequestStep.HOURLY ->
            "strftime('%Y-%m-%d %H:00:00', time)"
        MetricRequestStep.DAILY ->
            "date(time)"
        MetricRequestStep.MONTHLY ->
            "strftime('%Y-%m-01', time)"
        else -> throw IllegalStateException("Unexpected step: $step")
    }

    private fun calculatePostgresTimeWindow(step: MetricRequestStep): String = when (step) {
        MetricRequestStep.MINUTELY ->
            "TO_CHAR(time, 'YYYY-MM-DD HH24:MI:00')"
        MetricRequestStep.HOURLY ->
            "TO_CHAR(time, 'YYYY-MM-DD HH24:00:00')"
        MetricRequestStep.DAILY ->
            "TO_CHAR(time, 'YYYY-MM-DD')"
        MetricRequestStep.MONTHLY ->
            "TO_CHAR(time, 'YYYY-MM-01')"
        else -> throw IllegalStateException("Unexpected step: $step")
    }

}
