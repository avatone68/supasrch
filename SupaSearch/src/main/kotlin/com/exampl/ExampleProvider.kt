package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SupaSearch
 *  - Normal search:  merges results from every installed provider into one ranked list.
 *  - "!test <query>": provider health check. For each provider it runs
 *                     search -> opens the first result -> counts playable links,
 *                     and returns one scoreboard row per provider, best first.
 */
class SupaSearch : MainAPI() {
    override var name = "SupaSearch"
    override var mainUrl = "https://localhost"
    override val hasMainPage = false
    override val supportedTypes = TvType.values().toSet()

    companion object {
        const val TEST_PREFIX = "!test"
        const val DEFAULT_TEST_QUERY = "batman"
        const val REPORT_URL = "supasearch://report/"

        const val SEARCH_TIMEOUT_MS = 10_000L
        const val LOAD_TIMEOUT_MS = 12_000L
        const val LINKS_TIMEOUT_MS = 15_000L

        // Details of the last health check, shown when a failed row is tapped.
        val lastReport = ConcurrentHashMap<String, String>()
    }

    private fun otherProviders(): List<MainAPI> =
        APIHolder.allProviders.filter { it !is SupaSearch }.toList()

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.startsWith(TEST_PREFIX, ignoreCase = true)) {
            val testQuery = q.substring(TEST_PREFIX.length).trim()
                .ifEmpty { DEFAULT_TEST_QUERY }
            return healthCheck(testQuery)
        }
        return mergedSearch(q)
    }

    private suspend fun mergedSearch(query: String): List<SearchResponse> =
        coroutineScope {
            otherProviders().map { api ->
                async(Dispatchers.IO) { safeSearch(api, query) ?: emptyList() }
            }.awaitAll().flatten().sortedBy { rank(it.name, query) }
        }

    /** null = failed / timed out, empty = worked but found nothing. */
    private suspend fun safeSearch(api: MainAPI, query: String): List<SearchResponse>? =
        try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                // Paged search is what the app itself calls; by default it
                // falls back to the provider's plain search(query).
                api.search(query, 1)?.items ?: emptyList()
            }
        } catch (t: Throwable) {
            null
        }

    private fun rank(title: String, q: String): Int {
        val t = title.lowercase()
        val k = q.lowercase()
        return when {
            t == k -> 0
            t.startsWith(k) -> 1
            t.contains(k) -> 2
            else -> 3
        }
    }

    // ---------------------------------------------------------- health check

    private data class Health(
        val api: MainAPI,
        val results: Int,        // -1 = search failed
        val links: Int,          // -1 = not checked / couldn't check
        val millis: Long,
        val note: String,
        val first: SearchResponse?
    )

    private suspend fun healthCheck(query: String): List<SearchResponse> =
        coroutineScope {
            lastReport.clear()
            val checks = otherProviders().map { api ->
                async(Dispatchers.IO) { checkOne(api, query) }
            }.awaitAll()

            checks.sortedWith(
                compareByDescending<Health> { it.links }
                    .thenByDescending { it.results }
                    .thenBy { it.millis }
            ).map { toRow(it, query) }
        }

    private suspend fun checkOne(api: MainAPI, query: String): Health {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start

        val found = safeSearch(api, query)
            ?: return Health(api, -1, -1, elapsed(), "search failed or timed out", null)
        if (found.isEmpty())
            return Health(api, 0, -1, elapsed(), "search worked, no results", null)

        val first = found.first()

        val page = try {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { api.load(first.url) }
        } catch (t: Throwable) {
            null
        } ?: return Health(api, found.size, -1, elapsed(), "title page failed to load", first)

        val data = linkData(page)
            ?: return Health(api, found.size, -1, elapsed(), "page ok, no episode to test", first)

        val count = AtomicInteger(0)
        try {
            withTimeoutOrNull(LINKS_TIMEOUT_MS) {
                api.loadLinks(data, false, { }, { count.incrementAndGet() })
            }
        } catch (t: Throwable) {
            // keep whatever links arrived before the error
        }
        val links = count.get()
        val note = if (links > 0) "ok" else "page ok, no playable links"
        return Health(api, found.size, links, elapsed(), note, first)
    }

    /** The string a provider needs for loadLinks: the movie, or the first episode. */
    private fun linkData(page: LoadResponse): String? = when (page) {
        is MovieLoadResponse -> page.dataUrl
        is LiveStreamLoadResponse -> page.dataUrl
        is TvSeriesLoadResponse -> page.episodes.firstOrNull()?.data
        is AnimeLoadResponse -> page.episodes.values.flatten().firstOrNull()?.data
        else -> null
    }

    private fun toRow(h: Health, query: String): SearchResponse {
        val secs = "%.1fs".format(h.millis / 1000.0)
        val icon = when {
            h.links > 0 -> "\u2705"      // working
            h.results > 0 -> "\u26A0\uFE0F" // results but no links confirmed
            else -> "\u274C"             // dead
        }
        val label = when {
            h.links > 0 -> "$icon ${h.api.name} | ${h.links} links | ${h.results} results | $secs"
            h.results > 0 -> "$icon ${h.api.name} | ${h.results} results | ${h.note} | $secs"
            else -> "$icon ${h.api.name} | ${h.note} | $secs"
        }
        lastReport[h.api.name] =
            "Test query: $query\nResults: ${h.results}\nLinks: ${h.links}\nTime: $secs\nStatus: ${h.note}"

        val first = h.first
        return if (first != null) {
            // Tapping the row opens this provider's first real result.
            h.api.newMovieSearchResponse(label, first.url, first.type ?: TvType.Movie, false) {
                posterUrl = first.posterUrl
                posterHeaders = first.posterHeaders
            }
        } else {
            // Tapping the row opens a small report page (see load below).
            newMovieSearchResponse(label, REPORT_URL + h.api.name, TvType.Movie, false)
        }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse {
        val provider = url.removePrefix(REPORT_URL)
        return newMovieLoadResponse("$provider - health check", url, TvType.Movie, "") {
            plot = lastReport[provider] ?: "No report stored. Run a !test search first."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}
