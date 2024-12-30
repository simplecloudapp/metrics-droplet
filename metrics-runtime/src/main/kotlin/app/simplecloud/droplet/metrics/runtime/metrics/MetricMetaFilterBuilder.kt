package app.simplecloud.droplet.metrics.runtime.metrics

import build.buf.gen.simplecloud.metrics.v1.MetricRequestMetaFilter
import build.buf.gen.simplecloud.metrics.v1.MetricFilterType
import org.jooq.Condition
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS_META

object MetricMetaFilterBuilder {

    fun buildMetaFilterCondition(metaFilters: List<MetricRequestMetaFilter>): Condition? {
        if (metaFilters.isEmpty()) {
            return null
        }

        return metaFilters.map { filter ->
            when (filter.filterType) {
                MetricFilterType.EQUALS -> buildEqualsCondition(filter)
                MetricFilterType.STARTS_WITH -> buildStartsWithCondition(filter)
                MetricFilterType.ENDS_WITH -> buildEndsWithCondition(filter)
                MetricFilterType.CONTAINS -> buildContainsCondition(filter)
                else -> null
            }
        }.filterNotNull().reduce { acc, condition ->
            acc.and(condition)
        }
    }

    private fun buildEqualsCondition(filter: MetricRequestMetaFilter): Condition {
        val baseCondition = METRICS_META.DATA_NAME.eq(filter.key)
            .and(METRICS_META.DATA_VALUE.eq(filter.value))
        return if (filter.negate) baseCondition.not() else baseCondition
    }

    private fun buildStartsWithCondition(filter: MetricRequestMetaFilter): Condition {
        val baseCondition = METRICS_META.DATA_NAME.eq(filter.key)
            .and(METRICS_META.DATA_VALUE.startsWith(filter.value))
        return if (filter.negate) baseCondition.not() else baseCondition
    }

    private fun buildEndsWithCondition(filter: MetricRequestMetaFilter): Condition {
        val baseCondition = METRICS_META.DATA_NAME.eq(filter.key)
            .and(METRICS_META.DATA_VALUE.endsWith(filter.value))
        return if (filter.negate) baseCondition.not() else baseCondition
    }

    private fun buildContainsCondition(filter: MetricRequestMetaFilter): Condition {
        val baseCondition = METRICS_META.DATA_NAME.eq(filter.key)
            .and(METRICS_META.DATA_VALUE.contains(filter.value))
        return if (filter.negate) baseCondition.not() else baseCondition
    }
}
