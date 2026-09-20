package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SupaSearchPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SupaSearch())
    }
}