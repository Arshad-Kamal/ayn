package com.arshad.ayn.ui.mobile.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.SessionToken
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.PlaybackView
import com.arshad.ayn.feature.player.PlayerConnection
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.profiles.ProfilePlayback
import kotlinx.serialization.Serializable

/** The slot's saved part: the open video's route, whether its watch page shows (else the mini
 *  player docks), and which opening it is ([serial]: each one gets fresh ViewModels). */
@Serializable
internal data class SlotState(val route: PlayerRoute? = null, val expanded: Boolean = false, val serial: Int = 0)

/**
 * The shell's one player (M6-D16): a card's Play [open]s its watch page over the shell; Back, ⌄
 * or a drag down [dock]s it into the mini player above the bar, which [expand]s it again; the mini
 * player's ✕ and a statement's ⌄ [close] it. Saved with the shell (recreation, process death, a
 * profile reopened).
 */
@Stable
internal class PlayerSlot(private val state: MutableState<SlotState>) {
    val route: PlayerRoute? get() = state.value.route
    val expanded: Boolean get() = state.value.expanded
    val serial: Int get() = state.value.serial

    fun open(route: PlayerRoute) {
        state.value = SlotState(route, expanded = true, serial = serial + 1)
    }

    fun dock() {
        if (route != null) state.value = state.value.copy(expanded = false)
    }

    fun expand() {
        if (route != null) state.value = state.value.copy(expanded = true)
    }

    fun close() {
        state.value = SlotState(serial = serial)
    }
}

@Composable
internal fun rememberPlayerSlot(): PlayerSlot {
    val state = rememberSerializable { mutableStateOf(SlotState()) }
    return remember(state) { PlayerSlot(state) }
}

/** What an opening needs from the app: its ViewModels (a Short's [player] gets the list it runs
 *  through), the playback service's session, and the way to end the service's video ([playback]:
 *  its place saved, its stop note cleared). */
internal class PlayerModels(
    val player: (route: PlayerRoute, shorts: ShortsList?) -> PlayerViewModel,
    val menu: () -> VideoMenuViewModel,
    val session: (Context) -> SessionToken,
    val playback: ProfilePlayback,
)

/**
 * The phone's one controller on the playback service, bound for as long as the phone UI is
 * composed (released when the activity goes, which pauses): a video plays on under the gate and
 * while Ayn is away (M6-D5, D8). [reconnect] binds again after the service went away.
 */
@Stable
internal class AppPlayer(val connection: PlayerConnection, val reconnect: () -> Unit)

@Composable
internal fun rememberAppPlayer(session: (Context) -> SessionToken): AppPlayer {
    var attempt by remember { mutableIntStateOf(0) }
    val connection = rememberPlayerConnection(session, attempt)
    return remember(connection) { AppPlayer(connection, reconnect = { attempt++ }) }
}

/**
 * Each opening's ViewModels (its [PlayerViewModel] and card menu) in a store of their own, held in
 * the shell's store: they outlive the shell's composition (the gate closing it for the picker and
 * reopening the same profile resumes them); the next opening, or the slot closing, clears them.
 */
internal class PlayerStores : ViewModel() {
    private val stores = mutableMapOf<Int, ViewModelStore>()

    /** The store of opening [serial]. */
    fun of(serial: Int): ViewModelStoreOwner {
        val store = stores.getOrPut(serial) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /** Clears every opening's store but [serial]'s (`null`: all), once the composition moved on. */
    fun keepOnly(serial: Int?) {
        stores.keys.filter { it != serial }.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() = keepOnly(null)
}

/**
 * The open video: its ViewModels, and the app's controller on the playback service
 * ([connection]). [reconnect] binds again after the service went away. [picture] is the picture
 * of the video it plays now (Up next and Play all move it on).
 */
@Stable
internal class OpenPlayer(
    val viewModel: PlayerViewModel,
    val menu: VideoMenuViewModel,
    val connection: PlayerConnection,
    val reconnect: () -> Unit,
    val picture: State<String?> = mutableStateOf(null),
)

/** The Shorts a page last opened a Short from, for the opening it starts (M7-D13): a later Short
 *  replaces it; an opening whose Short isn't in it (or a Short restored after process death, when
 *  none is) plays the Short alone. */
internal class ShortsHandOver {
    var list: ShortsList? = null
        private set

    /** Opens [short] in the Shorts player ([open]) with [run] as the list it runs through. */
    fun play(short: VideoItem, run: ShortsList, open: (PlayerRoute) -> Unit) {
        list = run
        open(PlayerRoute(short, short = true))
    }
}

/** [slot]'s open video on [app]'s controller, `null` while none is; a Short's run comes from [shorts]. */
@Composable
internal fun rememberOpenPlayer(slot: PlayerSlot, models: PlayerModels, app: AppPlayer, shorts: ShortsHandOver = remember { ShortsHandOver() }): OpenPlayer? {
    val stores = viewModel { PlayerStores() }
    val route = slot.route
    val open = route != null
    LaunchedEffect(slot.serial, open) { stores.keepOnly(slot.serial.takeIf { open }) }
    if (route == null) return null
    return key(slot.serial) {
        val owner = stores.of(slot.serial)
        val viewModel = viewModel(viewModelStoreOwner = owner) {
            models.player(route, if (route.short) shorts.list ?: ShortsList(emptyList()) else null)
        }
        val menu = viewModel(viewModelStoreOwner = owner) { models.menu() }
        val ui = viewModel.uiState.collectAsStateWithLifecycle()
        // Only the picture matters here: the rest of the state would recompose the whole shell.
        val picture = remember { derivedStateOf { ui.value.video.thumbnailUrl } }
        remember(viewModel, menu, app.connection) { OpenPlayer(viewModel, menu, app.connection, reconnect = app.reconnect, picture = picture) }
    }
}

/** A controller on the session [sessionToken] names, bound again for each [attempt]; released
 *  (pausing first) when it leaves the composition. Mirrors `:ui:tv`'s (the remember wrappers stay
 *  per UI, M6 Q33): a fix to one belongs in both. */
@Composable
private fun rememberPlayerConnection(sessionToken: (Context) -> SessionToken, attempt: Int): PlayerConnection {
    val context = LocalContext.current
    val connection = remember(attempt) { PlayerConnection() }
    DisposableEffect(connection) {
        val release = connection.bind(context, sessionToken)
        onDispose { release() }
    }
    return connection
}

/** [player]'s state for the screen, kept current by its events while composed; the screen
 *  [PlaybackView.request]s [videoId]. Mirrors `:ui:tv`'s, as [rememberPlayerConnection]. */
@Composable
internal fun rememberPlaybackView(player: Player, videoId: String): PlaybackView {
    val view = remember(player) { PlaybackView(player) }
    DisposableEffect(view) {
        view.sync()
        player.addListener(view)
        onDispose { player.removeListener(view) }
    }
    LaunchedEffect(view, videoId) { view.request(videoId) }
    return view
}
