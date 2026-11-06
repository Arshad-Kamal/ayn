package com.arshad.ayn.ui.tv

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import java.io.IOException

/** Pictures never touch the network in tests (TEST-DETERM-01): every request fails at once,
 *  its URL added to [requested] when a test watches which pictures were asked for. */
internal fun installOfflineImageLoader(context: Context, requested: MutableList<String>? = null) {
    SingletonImageLoader.setUnsafe(
        ImageLoader.Builder(context)
            .components {
                add(
                    object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                            requested?.add(chain.request.data.toString())
                            return ErrorResult(null, chain.request, IOException("no network in tests"))
                        }
                    },
                )
            }
            .build(),
    )
}

