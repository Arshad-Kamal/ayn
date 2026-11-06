package com.arshad.ayn.core.access.sabr

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import com.github.libretube.player.parser.SabrFailure
import java.io.IOException

/** Media3's source error around `SabrDataSource`'s `IOException` wrapper of [cause]: the chain
 *  [classifyPlaybackFailure] reads. */
@OptIn(UnstableApi::class)
fun sabrSourceError(cause: Throwable): ExoPlaybackException =
    ExoPlaybackException.createForSource(
        IOException("SABR segment fetch failed", cause),
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

/** What ExoPlayer reports when the SABR server answers `StreamProtectionStatus` 3. */
fun attestationRequiredFailure(): ExoPlaybackException = sabrSourceError(SabrFailure.AttestationRequired())
