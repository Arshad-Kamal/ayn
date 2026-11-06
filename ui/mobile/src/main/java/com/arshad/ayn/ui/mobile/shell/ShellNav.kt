package com.arshad.ayn.ui.mobile.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.feature.nav.SavedPlaylistRoute
import com.arshad.ayn.ui.mobile.R
import kotlinx.serialization.Serializable

/** The navigation bar's destinations (M6-D13: Search opens from the top bar's pill, Settings from
 *  the profile menu). */
internal enum class Tab(val label: String, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int = icon) {
    Home("Home", R.drawable.ic_nav_home, R.drawable.ic_nav_home_filled),
    Subscriptions("Subscriptions", R.drawable.ic_nav_subscriptions),
    Library("Library", R.drawable.ic_nav_library),
}

// The phone's destinations are NavKeys saved with each tab's back stack. One with arguments
// carries its `:feature` typed route, so `:feature` doesn't take Navigation3.

/** A tab's first page. */
@Serializable
internal data class TabKey(val tab: Tab) : NavKey

/** A page drawn without the navigation bar (Settings, `m6-settings`): the bar slides away under it. */
internal interface BarlessKey : NavKey

/** Settings' categories, opened from the profile menu (Q12). */
@Serializable
internal data object SettingsKey : BarlessKey

/** Settings' categories in the TV's order; [Backup] is Import from YouTube for anyone but the owner. */
internal enum class SettingsPage { Profiles, Filters, Playback, SponsorBlock, DeArrow, Dislikes, Interface, Sync, Backup, About }

/** A Settings category's page. */
@Serializable
internal data class SettingsPageKey(val page: SettingsPage) : BarlessKey

/** The owner's restrictions on another profile, opened from its sheet in Profiles (after the owner's lock). */
@Serializable
internal data class RestrictionsKey(val profileId: Long) : BarlessKey

/** Search, opened from the pill over the shown tab (M6-D13); each opening starts empty (Q19). */
@Serializable
internal data object SearchKey : NavKey

/** A kids profile's Parent page (`m6-kids` 13–20), opened from the kid's menu over its Home. */
@Serializable
internal data object ParentKey : NavKey

/** A channel's page, opened over the shown tab (M6-D14). */
@Serializable
internal data class ChannelKey(val route: ChannelRoute) : NavKey

/** All history, by day (M6-D15). */
@Serializable
internal data object HistoryKey : NavKey

/** One of the profile's own playlists (M6-D15). */
@Serializable
internal data class SavedPlaylistKey(val route: SavedPlaylistRoute) : NavKey

/** A YouTube playlist, opened from its card (M6-D15). */
@Serializable
internal data class PlaylistKey(val route: PlaylistRoute) : NavKey

/**
 * Where the shell is: the shown [tab] and a saved back stack per tab ([stacks]), so a tab keeps
 * its pages while another shows. Back unwinds the shown tab, then returns to Home, and on Home's
 * first page leaves Ayn (exit through Home, as the TV's rail and Navigation3's multiple-stacks
 * recipe). Tapping the shown tab again asks its page to scroll to the top ([reselected]). A page's
 * start is an arrival ([consumeArrival]) when its tab was tapped to or the app came back to it,
 * never when Back uncovered it (PERF P9c: a fresh feed held meanwhile lands on an arrival).
 */
@Stable
internal class ShellNav(private val shown: MutableState<Tab>, val stacks: Map<Tab, NavBackStack<NavKey>>) {
    val tab: Tab
        get() = shown.value

    /** The page on top of the shown tab. */
    val current: NavKey
        get() = stacks.getValue(tab).last()

    /** The tab whose bar item was tapped while it showed, until its page has scrolled to the top. */
    var reselected: Tab? by mutableStateOf(null)
        private set

    /** The page whose next start is an arrival; any page opened or closed clears it. Read by no composition. */
    private var arrival: NavKey? = null

    /** The bar: another tab shows, or the shown one scrolls to the top (only its first page has
     *  a top to go to; a page over it would leave the request for Back to uncover). */
    fun onTabTap(target: Tab) {
        if (target == tab) {
            if (stacks.getValue(tab).size == 1) reselected = target
        } else {
            shown.value = target
            arrival = stacks.getValue(target).last()
        }
    }

    /** The app left the foreground: the page showing arrives when it comes back. */
    fun onAppStopped() {
        arrival = stacks.getValue(tab).last()
    }

    /** Whether this start of [key]'s page is an arrival; asked once per start. */
    fun consumeArrival(key: NavKey): Boolean = (key == arrival).also { arrival = null }

    fun onScrolledToTop() {
        reselected = null
    }

    /** [key] opens over the shown tab's page; a second tap while it is already on top opens nothing. */
    fun open(key: NavKey) {
        val stack = stacks.getValue(tab)
        if (stack.lastOrNull() != key) stack.add(key)
        arrival = null
    }

    /** Settings' [page] opens over the shown tab, over Settings itself; nothing when it already shows. */
    fun openSettings(page: SettingsPage) {
        val key = SettingsPageKey(page)
        if (current == key) return
        if (current != SettingsKey) open(SettingsKey)
        open(key)
    }

    fun back() {
        val stack = stacks.getValue(tab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else if (tab != Tab.Home) shown.value = Tab.Home
        arrival = null
    }

    /** [key]'s page leaves the shown tab (a playlist deleted), wherever it sits above the tab's first page. */
    fun close(key: NavKey) {
        val stack = stacks.getValue(tab)
        val at = stack.lastIndexOf(key)
        if (at > 0) stack.removeAt(at)
        arrival = null
    }

    /** The tabs whose entries show, Home always first (Back leaves through it). */
    val tabsInUse: List<Tab>
        get() = if (tab == Tab.Home) listOf(Tab.Home) else listOf(Tab.Home, tab)
}

/** The shell's navigation, kept across recreation and process death (the shown tab and every stack). */
@Composable
internal fun rememberShellNav(): ShellNav {
    val shown = rememberSaveable { mutableStateOf(Tab.Home) }
    val stacks = Tab.entries.associateWith { rememberNavBackStack(TabKey(it)) }
    return remember { ShellNav(shown, stacks) }
}

/**
 * The entries [NavDisplay][androidx.navigation3.ui.NavDisplay] shows: each tab's stack decorated
 * on its own (saved state and a ViewModel store per entry), so a tab that isn't shown keeps both.
 * Content keys carry the tab: the same page can sit in two tabs' stacks at once.
 */
@Composable
internal fun ShellNav.entries(provider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
    val entryStores = viewModel { ShellEntryStores() }
    val decorated = stacks.mapValues { (tab, stack) ->
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(entryStores.of(tab)),
            ),
            entryProvider = { key ->
                val entry = provider(key)
                NavEntry(key, contentKey = "${tab.name}:${entry.contentKey}", metadata = entry.metadata) { entry.Content() }
            },
        )
    }
    return tabsInUse.flatMap { decorated.getValue(it) }
}

/** While [requested], [list] scrolls to its top, then reports it with [onDone], also when a drag
 *  cuts the scroll short (a request left standing would swallow the next re-tap). */
@Composable
internal fun ScrollsToTop(list: LazyListState, requested: Boolean, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        try {
            list.animateScrollToItem(0)
        } finally {
            done()
        }
    }
}

/**
 * Each tab's entry ViewModel stores, held in the shell's ViewModel store: they outlive the
 * shell's composition (the gate closing it for the picker and reopening the same profile resumes
 * them) and clear with the shell's store (another profile opening).
 */
internal class ShellEntryStores : ViewModel() {
    private val providers: Map<Tab, ViewModelStoreProvider> = Tab.entries.associateWith { ViewModelStoreProvider(ViewModelStore()) }

    fun of(tab: Tab): ViewModelStoreProvider = providers.getValue(tab)

    override fun onCleared() {
        providers.values.forEach(ViewModelStoreProvider::clearAllKeys)
    }
}
