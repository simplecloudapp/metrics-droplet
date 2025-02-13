package app.simplecloud.droplet.metrics.runtime.metrics.query

import app.simplecloud.droplet.metrics.runtime.metrics.MetricsRegistry
import app.simplecloud.droplet.metrics.runtime.metrics.time.MetricsTimer
import org.apache.logging.log4j.LogManager
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectForUpdateStep
import org.jooq.SelectSeekStep1
import java.util.Base64

class MetricsPaginationHandler {

    private val logger = LogManager.getLogger(MetricsPaginationHandler::class.java)

    suspend fun applyPagination(
        query: SelectSeekStep1<*, *>,
        limit: Int,
        pageToken: String?
    ): PaginationResult {
        val timer = MetricsTimer("pagination_apply_duration")
        try {
            val offset = decodePageToken(pageToken)
            val effectiveLimit = calculateEffectiveLimit(limit)

            MetricsRegistry.incrementCounter("pagination_requests_total")
            return when {
                effectiveLimit > 0 -> PaginationResult(
                    query = query.limit(effectiveLimit).offset(offset),
                    offset = offset,
                    limit = effectiveLimit
                )
                else -> PaginationResult(
                    query = query,
                    offset = 0,
                    limit = Int.MAX_VALUE
                )
            }
        } finally {
            timer.stop()
        }
    }

    suspend fun getTotalCount(context: DSLContext, baseQuery: SelectSeekStep1<*, *>): Long {
        val timer = MetricsTimer("pagination_count_duration")
        try {
            MetricsRegistry.incrementCounter("pagination_count_requests_total")
            return context
                .selectCount()
                .from(baseQuery)
                .fetchOne(0, Long::class.java) ?: 0L
        } catch (e: Exception) {
            MetricsRegistry.incrementCounter("pagination_count_errors_total")
            logger.error("Error getting total count", e)
            throw e
        } finally {
            timer.stop()
        }
    }

    fun getNextPageToken(offset: Int, limit: Int, totalCount: Long): String? {
        val nextOffset = offset + limit
        return if (nextOffset < totalCount) {
            encodePageToken(nextOffset)
        } else {
            null
        }
    }

    private fun calculateEffectiveLimit(requestedLimit: Int): Int {
        return when {
            requestedLimit <= 0 -> DEFAULT_PAGE_SIZE
            requestedLimit > MAX_PAGE_SIZE -> MAX_PAGE_SIZE
            else -> requestedLimit
        }
    }

    private fun encodePageToken(offset: Int): String {
        return try {
            Base64.getUrlEncoder().encodeToString(offset.toString().toByteArray())
        } catch (e: Exception) {
            logger.error("Failed to encode page token for offset: $offset", e)
            encodePageToken(0)
        }
    }

    private fun decodePageToken(pageToken: String?): Int {
        if (pageToken.isNullOrBlank()) {
            return 0
        }

        return try {
            val decoded = Base64.getUrlDecoder().decode(pageToken)
            String(decoded).toInt().coerceAtLeast(0)
        } catch (e: Exception) {
            logger.error("Failed to decode page token: $pageToken", e)
            MetricsRegistry.incrementCounter("pagination_token_decode_errors_total")
            0
        }
    }

    data class PaginationResult(
        val query: SelectForUpdateStep<out Record?>,
        val offset: Int,
        val limit: Int
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 1000
    }

}
