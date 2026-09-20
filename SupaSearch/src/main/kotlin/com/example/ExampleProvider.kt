package com.example

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*

class SupaSearch : MainAPI() {
    override var name = "SupaSearch"
    override var mainUrl = "https://localhost"
    override val hasMainPage = false
    override val supportedTypes = TvType.values().toSet()

    override suspend fun search(query: String): List<SearchResponse> =
        coroutineScope {
            val apis = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.filter { it !is SupaSearch }
            }
            apis.map { api ->
                async {
                    try {
                        withTimeoutOrNull(10_000) { api.search(query) }
                            ?: emptyList()
                    } catch (t: Throwable) { emptyList() }
                }
            }.awaitAll().flatten()
                .sortedBy { rank(it.name, query) }
        }

    private fun rank(title: String, q: String): Int {
        val t = title.lowercase(); val k = q.lowercase()
        return when {
            t == k -> 0
            t.startsWith(k) -> 1
            t.contains(k) -> 2
            else -> 3
        }
    }
}