package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.PoToken
import com.arshad.ayn.core.access.PoTokenSource
import com.arshad.ayn.core.access.VideoId
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/**
 * Bridges the app-owned [PoTokenSource] seam to the vendored extractor's provider interface.
 * Translation only; minting logic lives behind the seam (M3.4).
 */
class NewPipePoTokenBridge(private val source: PoTokenSource) : PoTokenProvider {

    override fun getWebClientPoToken(videoId: String): PoTokenResult? =
        source.webClientPoToken(VideoId(videoId))?.toResult()

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? =
        source.webEmbedClientPoToken(VideoId(videoId))?.toResult()

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? =
        source.androidClientPoToken(VideoId(videoId))?.toResult()

    override fun getIosClientPoToken(videoId: String): PoTokenResult? =
        source.iosClientPoToken(VideoId(videoId))?.toResult()

    private fun PoToken.toResult(): PoTokenResult =
        PoTokenResult(visitorData, playerRequestPoToken, streamingDataPoToken)
}
