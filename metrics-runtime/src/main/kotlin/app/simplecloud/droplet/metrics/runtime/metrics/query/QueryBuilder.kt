package app.simplecloud.droplet.metrics.runtime.metrics.query

import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS
import app.simplecloud.droplet.metrics.runtime.db.tables.references.METRICS_META
import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import build.buf.gen.simplecloud.metrics.v1.MetricRequestMetaFilter
import org.jooq.*
import org.jooq.impl.DSL
import java.time.LocalDateTime

object QueryBuilder {

    suspend fun buildRawMetricsQuery(
        context: DSLContext,
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        metaFilters: List<MetricRequestMetaFilter>,
        descending: Boolean
    ): SelectSeekStep1<*, *> {
        val timer = MetricsTimer("raw_query_build_duration")
        MetricsRegistry.incrementCounter("raw_query_builds_total")

        try {
            val conditions = buildConditions(metricTypes, from, to)

            var query = context
                .select(
                    METRICS.UNIQUE_ID,
                    METRICS.METRIC_TYPE,
                    METRICS.METRIC_VALUE,
                    METRICS.TIME,
                    DSL.field(
                        "GROUP_CONCAT({0} || ':' || {1})",
                        String::class.java,
                        METRICS_META.DATA_NAME,
                        METRICS_META.DATA_VALUE
                    ).`as`("meta_pairs")
                )
                .from(METRICS)
                .leftJoin(METRICS_META)
                .on(METRICS.UNIQUE_ID.eq(METRICS_META.METRIC_UNIQUE_ID))
                .where(conditions)

            metaFilters.forEach { filter ->
                val subQuery = context
                    .selectOne()
                    .from(METRICS_META)
                    .where(
                        METRICS_META.METRIC_UNIQUE_ID.eq(METRICS.UNIQUE_ID)
                            .and(METRICS_META.DATA_NAME.eq(filter.key))
                            .and(METRICS_META.DATA_VALUE.eq(filter.value))
                    )

                query = query.and(DSL.exists(subQuery))
            }

            return query
                .groupBy(METRICS.UNIQUE_ID)
                .orderBy(if (descending) METRICS.TIME.desc() else METRICS.TIME.asc())

        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("raw_query_build_errors_total")
            throw e
        } finally {
            timer.stop()
        }
    }

    suspend fun buildAggregatedMetricsQuery(
        context: DSLContext,
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?,
        metaFilters: List<MetricRequestMetaFilter>,
        timeWindow: String,
        descending: Boolean
    ): SelectSeekStep1<*, *> {
        val timer = MetricsTimer("aggregated_query_build_duration")
        MetricsRegistry.incrementCounter("aggregated_query_builds_total")

        try {
            val conditions = buildConditions(metricTypes, from, to)

            var query = context
                .select(
                    METRICS.METRIC_TYPE,
                    DSL.max(METRICS.METRIC_VALUE).`as`("max_value"),
                    DSL.field(timeWindow).`as`("time_window"),
                    DSL.field(
                        "GROUP_CONCAT({0} || ':' || {1})",
                        String::class.java,
                        METRICS_META.DATA_NAME,
                        METRICS_META.DATA_VALUE
                    ).`as`("meta_pairs"),
                    DSL.count().`as`("count")
                )
                .from(METRICS)
                .leftJoin(METRICS_META)
                .on(METRICS.UNIQUE_ID.eq(METRICS_META.METRIC_UNIQUE_ID))
                .where(conditions)

            metaFilters.forEach { filter ->
                val subQuery = context
                    .selectOne()
                    .from(METRICS_META)
                    .where(
                        METRICS_META.METRIC_UNIQUE_ID.eq(METRICS.UNIQUE_ID)
                            .and(METRICS_META.DATA_NAME.eq(filter.key))
                            .and(METRICS_META.DATA_VALUE.eq(filter.value))
                    )

                query = query.and(DSL.exists(subQuery))
            }

            return query
                .groupBy(
                    METRICS.METRIC_TYPE,
                    DSL.field(timeWindow)
                )
                .orderBy(if (descending) DSL.field(timeWindow).desc() else DSL.field(timeWindow).asc())

        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("aggregated_query_build_errors_total")
            throw e
        } finally {
            timer.stop()
        }
    }

    private fun buildConditions(
        metricTypes: Set<String>,
        from: LocalDateTime?,
        to: LocalDateTime?
    ): Condition {
        val conditions = mutableListOf<Condition>()

        if (metricTypes.isNotEmpty()) {
            conditions.add(METRICS.METRIC_TYPE.`in`(metricTypes))
        }

        from?.let { conditions.add(METRICS.TIME.greaterOrEqual(it)) }
        to?.let { conditions.add(METRICS.TIME.lessOrEqual(it)) }

        return if (conditions.isEmpty()) {
            DSL.noCondition()
        } else {
            conditions.reduce { acc, condition -> acc.and(condition) }
        }
    }

    fun buildCountQuery(
        context: DSLContext,
        baseQuery: SelectSeekStepN<Record>
    ): SelectJoinStep<Record1<Int>> {
        return context.selectCount().from(baseQuery)
    }

}
