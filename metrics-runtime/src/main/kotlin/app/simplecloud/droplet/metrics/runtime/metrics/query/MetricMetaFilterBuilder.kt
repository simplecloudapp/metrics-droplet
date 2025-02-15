package app.simplecloud.droplet.metrics.runtime.metrics.query

import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS_META
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import build.buf.gen.simplecloud.metrics.v1.MetricFilterType
import build.buf.gen.simplecloud.metrics.v1.MetricRequestMetaFilter
import org.apache.logging.log4j.LogManager
import org.jooq.Condition

object MetricMetaFilterBuilder {

    private val logger = LogManager.getLogger(MetricMetaFilterBuilder::class.java)

    suspend fun buildMetaFilterCondition(metaFilters: List<MetricRequestMetaFilter>): Condition? {
        val timer = MetricsTimer("meta_filter_build_duration")
        try {
            if (metaFilters.isEmpty()) {
                return null
            }

            MetricsRegistry.incrementCounter("meta_filter_builds_total")

            val filtersByKey = metaFilters
                .filter { isValidFilter(it) }
                .groupBy { it.key }

            if (filtersByKey.isEmpty()) {
                return null
            }

            val keyConditions = filtersByKey.map { (key, filters) ->
                buildKeyConditions(key, filters)
            }

            return keyConditions.reduce { acc, condition ->
                acc.and(condition)
            }
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("meta_filter_build_errors_total")
            logger.error("Error building meta filter condition", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    private fun isValidFilter(filter: MetricRequestMetaFilter): Boolean {
        val isValid = !filter.key.isNullOrBlank() &&
                !filter.value.isNullOrBlank() &&
                filter.filterType != MetricFilterType.UNRECOGNIZED

        if (!isValid) {
            MetricsRegistry.incrementCounter("invalid_meta_filters_total")
            logger.warn(
                "Invalid meta filter: key=${filter.key}, value=${filter.value}, type=${filter.filterType}"
            )
        }

        return isValid
    }

    private fun buildKeyConditions(
        key: String,
        filters: List<MetricRequestMetaFilter>
    ): Condition {
        val baseCondition = METRICS_META.DATA_NAME.eq(key)

        val valueConditions = filters.map { filter ->
            buildValueCondition(filter)
        }

        val combinedValueConditions = valueConditions.reduce { acc, condition ->
            acc.or(condition)
        }

        return baseCondition.and(combinedValueConditions)
    }

    private fun buildValueCondition(filter: MetricRequestMetaFilter): Condition {
        val valueCondition = when (filter.filterType) {
            MetricFilterType.EQUALS -> buildEqualsCondition(filter)
            MetricFilterType.STARTS_WITH -> buildStartsWithCondition(filter)
            MetricFilterType.ENDS_WITH -> buildEndsWithCondition(filter)
            MetricFilterType.CONTAINS -> buildContainsCondition(filter)
//            MetricFilterType.REGEX -> buildRegexCondition(filter)
            else -> {
                MetricsRegistry.incrementCounter("unsupported_filter_type_total")
                throw IllegalArgumentException("Unsupported filter type: ${filter.filterType}")
            }
        }

        return if (filter.negate) {
            valueCondition.not()
        } else {
            valueCondition
        }
    }

    private fun buildEqualsCondition(filter: MetricRequestMetaFilter): Condition {
        return METRICS_META.DATA_VALUE.eq(filter.value)
    }

    private fun buildStartsWithCondition(filter: MetricRequestMetaFilter): Condition {
        return METRICS_META.DATA_VALUE.startsWith(filter.value)
    }

    private fun buildEndsWithCondition(filter: MetricRequestMetaFilter): Condition {
        return METRICS_META.DATA_VALUE.endsWith(filter.value)
    }

    private fun buildContainsCondition(filter: MetricRequestMetaFilter): Condition {
        return METRICS_META.DATA_VALUE.contains(filter.value)
    }

    private fun buildRegexCondition(filter: MetricRequestMetaFilter): Condition {
        return METRICS_META.DATA_VALUE.likeRegex(filter.value)
    }

    suspend fun optimizeFilters(filters: List<MetricRequestMetaFilter>): List<MetricRequestMetaFilter> {
        val timer = MetricsTimer("meta_filter_optimization_duration")
        try {
            if (filters.size <= 1) {
                return filters
            }

            MetricsRegistry.incrementCounter("meta_filter_optimizations_total")

            return filters
                .filter { isValidFilter(it) }
                .groupBy { it.key }
                .mapValues { (_, keyFilters) -> optimizeKeyFilters(keyFilters) }
                .values
                .flatten()
        } finally {
            timer.stop()
        }
    }

    private fun optimizeKeyFilters(
        filters: List<MetricRequestMetaFilter>
    ): List<MetricRequestMetaFilter> {
        if (filters.all { it.filterType == MetricFilterType.EQUALS && !it.negate }) {
            return listOf(filters.first())
        }

        if (filters.all { it.filterType == MetricFilterType.CONTAINS && !it.negate }) {
            return listOf(filters.first())
        }

        // For other cases, keep all filters
        return filters
    }

    suspend fun estimateFilterSelectivity(filter: MetricRequestMetaFilter): Double {
        return when (filter.filterType) {
            MetricFilterType.EQUALS -> 0.1
            MetricFilterType.STARTS_WITH -> 0.3
            MetricFilterType.ENDS_WITH -> 0.3
            MetricFilterType.CONTAINS -> 0.5
//            MetricFilterType.REGEX -> 0.7
            else -> 1.0
        }
    }

}
