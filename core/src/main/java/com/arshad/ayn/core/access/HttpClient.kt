package com.arshad.ayn.core.access

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The one home for the app's HTTP client settings. The app container builds one client from it
 * and shares it across every network seam; the transports' defaults (tests, smokes) use the same
 * settings.
 */
fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
