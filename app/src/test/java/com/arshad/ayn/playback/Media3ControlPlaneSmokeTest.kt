package com.arshad.ayn.playback

import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Media3 1.11.1 session behaviors an async-resolving control plane depends on, pinned with an
 * in-process `MediaSession` + `MediaController` (no device). Resolution is a [SettableFuture]
 * the test completes by hand, standing in for the extraction a real resolve awaits.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3ControlPlaneSmokeTest {

    private val context = RuntimeEnvironment.getApplication()
    private val releaseActions = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        releaseActions.asReversed().forEach { it() }
    }

    @Test
    fun commandsSentDuringAPendingResolveReachThePlayerInOrderAfterIt() {
        val fixture = ExoFixture()
        val controller = fixture.connect()

        controller.setMediaItem(requestedItem(VIDEO_A))
        controller.prepare()
        controller.play()
        idleUntil("resolve requested") { fixture.callback.requests.isNotEmpty() }
        idleMain()
        assertEquals("nothing reaches the player while the resolve is pending", emptyList<String>(), fixture.player.calls)

        fixture.callback.complete(VIDEO_A, KEPT_POSITION_MS)
        idleUntil("queued commands applied") { fixture.player.calls.size >= 3 }

        assertEquals(listOf("setMediaItems $VIDEO_A @0/$KEPT_POSITION_MS", "prepare", "play"), fixture.player.calls)
        assertEquals(KEPT_POSITION_MS, fixture.player.currentPosition)
        assertTrue(fixture.player.playWhenReady)
    }

    @Test
    fun theResolvedManifestReachesThePlayerWideMediaSourceFactory() {
        val fixture = ExoFixture()
        val controller = fixture.connect()

        controller.setMediaItem(requestedItem(VIDEO_A))
        idleUntil("resolve requested") { fixture.callback.pending != null }
        fixture.callback.complete(VIDEO_A)
        idleUntil("source created") { fixture.manifests.isNotEmpty() }

        assertEquals(listOf(FakeManifest(VIDEO_A)), fixture.manifests)
    }

    @Test
    fun queuedCommandsStillRunAfterTheResolveIsCancelledOnLastDisconnect() {
        assertDepartedControllersCommandsRunAfter { it.cancel(false) }
    }

    @Test
    fun queuedCommandsStillRunAfterTheResolveFailsOnLastDisconnect() {
        assertDepartedControllersCommandsRunAfter { it.setException(IOException("controller left")) }
    }

    /** The abandoned resolve applies no items, yet the departed controller's queued `prepare`
     *  runs against the empty playlist (ENDED, hence the play button's seek) and its `play`
     *  follows, both with no requesting controller. */
    private fun assertDepartedControllersCommandsRunAfter(abandon: (SettableFuture<MediaItemsWithStartPosition>) -> Unit) {
        val fixture = ExoFixture(abandonOnLastDisconnect = abandon)
        val controller = fixture.connect()

        controller.setMediaItem(requestedItem(VIDEO_A))
        controller.prepare()
        controller.play()
        idleUntil("resolve requested") { fixture.callback.pending != null }
        val resolve = fixture.callback.pending!!
        controller.release()
        idleUntil("resolve abandoned") { resolve.isDone }
        idleMain()

        assertEquals(
            listOf("prepare $NO_REQUESTER", "seekToDefaultPosition $NO_REQUESTER", "play $NO_REQUESTER"),
            fixture.player.calls,
        )
        assertEquals(0, fixture.player.mediaItemCount)
    }

    /** A second item request waits behind the pending resolve, while a custom command does not. */
    @Test
    fun aSecondRequestQueuesBehindThePendingResolveButACustomCommandDoesNot() {
        val fixture = ExoFixture()
        val controller = fixture.connect()

        controller.setMediaItem(requestedItem(VIDEO_A))
        idleUntil("first resolve requested") { fixture.callback.pending != null }
        val first = fixture.callback.pending!!
        controller.setMediaItem(requestedItem(VIDEO_B))
        val custom = controller.sendCustomCommand(SessionCommand(CUSTOM_ACTION, Bundle.EMPTY), Bundle.EMPTY)
        idleUntil("custom command answered") { custom.isDone }
        assertEquals(SessionResult.RESULT_SUCCESS, custom.get().resultCode)
        assertEquals("the second request waits", listOf(VIDEO_A), fixture.callback.requests)

        first.cancel(false)
        idleUntil("second resolve requested") { fixture.callback.requests.size == 2 }
        assertEquals(listOf(VIDEO_A, VIDEO_B), fixture.callback.requests)
        assertEquals("a cancelled resolve applies nothing", emptyList<String>(), fixture.player.calls)
        assertEquals(0, fixture.player.mediaItemCount)
    }

    /** The exception is visible, but while it is set the controller loses the commands it would
     *  need to recover (set an item, prepare, play) until the session clears it. */
    @Test
    fun theResolvedTitleAndASessionPlaybackExceptionAreControllerVisible() {
        val fixture = ExoFixture()
        val controller = fixture.connect()
        controller.setMediaItem(requestedItem(VIDEO_A))
        idleUntil("resolve requested") { fixture.callback.pending != null }
        fixture.callback.complete(VIDEO_A)
        idleUntil("title visible") { controller.mediaMetadata.title != null }
        assertEquals(TITLE, controller.mediaMetadata.title.toString())

        val extras = Bundle().apply { putString(EXTRA_REASON, "Video unavailable") }
        fixture.session.setPlaybackException(
            PlaybackException("terminal", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, extras),
        )
        idleUntil("error visible") { controller.playerError != null }
        val error = controller.playerError!!
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, error.errorCode)
        assertEquals("Video unavailable", error.extras.getString(EXTRA_REASON))
        assertEquals(emptyList<Int>(), RECOVERY_COMMANDS.filter { controller.isCommandAvailable(it) })

        fixture.session.setPlaybackException(null)
        idleUntil("error cleared") { controller.playerError == null }
        assertNull(controller.playerError)
        assertEquals(RECOVERY_COMMANDS, RECOVERY_COMMANDS.filter { controller.isCommandAvailable(it) })
    }

    @Test
    fun theContentFormatIsControllerVisibleInCurrentTracks() {
        val player = TracksPlayer(Looper.getMainLooper())
        val session = MediaSession.Builder(context, player).setId("tracks").build()
        releaseActions += { session.release() }
        val controller = connect(session)

        idleUntil("tracks visible") { !controller.currentTracks.isEmpty }

        val format = controller.currentTracks.groups.single().getTrackFormat(0)
        assertEquals(CONTENT_FPS, format.frameRate)
        assertEquals(MimeTypes.VIDEO_VP9, format.sampleMimeType)
    }

    private fun connect(session: MediaSession): MediaController =
        connectController(context, session).also { controller -> releaseActions += { controller.release() } }

    /** A session over an inert ExoPlayer whose sources come from one player-wide factory
     *  (recording each item's [manifests]), behind a call recorder, resolving through [callback]. */
    private inner class ExoFixture(
        abandonOnLastDisconnect: (SettableFuture<MediaItemsWithStartPosition>) -> Unit = { it.cancel(false) },
    ) {
        val manifests = mutableListOf<FakeManifest>()
        val callback = ResolvingCallback(abandonOnLastDisconnect)
        val player: RecordingPlayer
        val session: MediaSession

        init {
            val factory = ItemKeepingSourceFactory { item -> manifests += item.localConfiguration!!.tag as FakeManifest }
            val exoPlayer = inertExoPlayer(context, factory)
            player = RecordingPlayer(exoPlayer)
            session = MediaSession.Builder(context, player).setId("exo").setCallback(callback).build()
            player.session = session
            releaseActions += {
                session.release()
                exoPlayer.release()
            }
        }

        fun connect(): MediaController = connect(session)
    }

    private class ResolvingCallback(
        private val abandonOnLastDisconnect: (SettableFuture<MediaItemsWithStartPosition>) -> Unit,
    ) : MediaSession.Callback {
        val requests = mutableListOf<String>()

        /** The latest resolve; Media3 asks for the next one only after this one completes. */
        var pending: SettableFuture<MediaItemsWithStartPosition>? = null
            private set

        fun complete(videoId: String, startPositionMs: Long = 0) {
            pending!!.set(MediaItemsWithStartPosition(listOf(resolvedItem(videoId)), 0, startPositionMs))
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.accept(
                SessionCommands.Builder().add(SessionCommand(CUSTOM_ACTION, Bundle.EMPTY)).build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            requests += mediaItems.single().mediaId
            return SettableFuture.create<MediaItemsWithStartPosition>().also { pending = it }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))

        /** The app's own policy (no resolve outlives its UI), not Media3's; the departing
         *  controller is already gone from `connectedControllers` here. */
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            val resolve = pending
            if (session.connectedControllers.isEmpty() && resolve != null && !resolve.isDone) {
                abandonOnLastDisconnect(resolve)
            }
        }
    }

    /** Records each call the session makes, marked when no controller is behind it. */
    private class RecordingPlayer(player: Player) : ForwardingPlayer(player) {
        val calls = mutableListOf<String>()
        var session: MediaSession? = null

        private fun record(call: String) {
            calls += if (session?.controllerForCurrentRequest == null) "$call $NO_REQUESTER" else call
        }

        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            record("setMediaItems ${mediaItems.joinToString { it.mediaId }} @$startIndex/$startPositionMs")
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

        override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
            record("setMediaItems ${mediaItems.joinToString { it.mediaId }} reset=$resetPosition")
            super.setMediaItems(mediaItems, resetPosition)
        }

        override fun prepare() {
            record("prepare")
            super.prepare()
        }

        override fun seekToDefaultPosition() {
            record("seekToDefaultPosition")
            super.seekToDefaultPosition()
        }

        override fun play() {
            record("play")
            super.play()
        }
    }

    private data class FakeManifest(val videoId: String)

    private class TracksPlayer(looper: Looper) : SimpleBasePlayer(looper) {
        override fun getState(): State {
            val video = Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_VP9)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(CONTENT_FPS)
                .build()
            val tracks = Tracks(listOf(Tracks.Group(TrackGroup(video), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
            val item = MediaItemData.Builder(VIDEO_A)
                .setMediaItem(MediaItem.Builder().setMediaId(VIDEO_A).build())
                .setTracks(tracks)
                .build()
            return State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaybackState(STATE_READY)
                .setPlaylist(listOf(item))
                .build()
        }
    }

    private companion object {
        const val VIDEO_A = "aaaaaaaaaaa"
        const val VIDEO_B = "bbbbbbbbbbb"
        const val TITLE = "Resolved title"
        const val KEPT_POSITION_MS = 42_000L
        const val CONTENT_FPS = 59.94f
        const val CUSTOM_ACTION = "smoke.CUSTOM"
        const val EXTRA_REASON = "reason"
        const val NO_REQUESTER = "(no requester)"
        val RECOVERY_COMMANDS = listOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_PREPARE, Player.COMMAND_PLAY_PAUSE)

        fun requestedItem(videoId: String): MediaItem = MediaItem.Builder().setMediaId(videoId).build()

        fun resolvedItem(videoId: String): MediaItem = MediaItem.Builder()
            .setMediaId(videoId)
            .setUri("ke://watch/$videoId")
            .setTag(FakeManifest(videoId))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(TITLE).build())
            .build()
    }
}
