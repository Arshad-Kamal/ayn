package com.arshad.ayn.playback

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.DefaultHlsDataSourceFactory
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Supplier
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * LIVE-D3's stuck rule on the tracker the live source uses ([livePlaylistTracker]): a live
 * playlist that stops changing ([SyntheticHls], one-second target duration) fails as stuck only
 * after 15 target durations. Loads run inline on the paused main looper, so the virtual clock alone
 * decides when the tracker reloads; Media3's own tracker (3.5) is the control that the harness sees
 * a stall at all.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivePlaylistStuckTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun liveTrackerCountsAnUnchangingPlaylistStuckAfterFifteenTargetDurations() {
        val stuckAtMs = stuckAfterMs(livePlaylistTracker())

        assertTrue("stuck at $stuckAtMs ms", stuckAtMs in 15_001L..16_000L)
    }

    @Test
    fun mediaThreesDefaultTrackerCountsTheSameStallStuckAfterThreeAndAHalf() {
        val stuckAtMs = stuckAfterMs(DefaultHlsPlaylistTracker.FACTORY)

        assertTrue("stuck at $stuckAtMs ms", stuckAtMs in 3_501L..4_500L)
    }

    /** Milliseconds from the start until [factory]'s tracker reports the primary playlist stuck. */
    private fun stuckAfterMs(factory: HlsPlaylistTracker.Factory): Long {
        val tracker = factory.createTracker(
            DefaultHlsDataSourceFactory(SyntheticHls()),
            DefaultLoadErrorHandlingPolicy(),
            DefaultHlsPlaylistParserFactory(),
            null,
            Supplier { ReleasableExecutor.from(Executor { it.run() }) {} },
        )
        tracker.start(
            Uri.parse(SyntheticHls.MULTIVARIANT_URL),
            MediaSourceEventListener.EventDispatcher(),
            {},
            DefaultBandwidthMeter.Builder(context).build(),
        )
        try {
            var elapsedMs = 0L
            while (elapsedMs <= LIMIT_MS) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
                elapsedMs += STEP_MS
                try {
                    tracker.maybeThrowPrimaryPlaylistRefreshError()
                } catch (_: HlsPlaylistTracker.PlaylistStuckException) {
                    return elapsedMs
                }
            }
            error("no stuck playlist within $LIMIT_MS ms")
        } finally {
            tracker.stop()
        }
    }

    private companion object {
        const val STEP_MS = 100L
        const val LIMIT_MS = 30_000L
    }
}
