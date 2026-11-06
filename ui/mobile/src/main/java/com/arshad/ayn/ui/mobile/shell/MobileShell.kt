package com.arshad.ayn.ui.mobile.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.kids.timeLeftText
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.kids.KidsWarning
import com.arshad.ayn.ui.mobile.kids.rememberTimeLeft
import com.arshad.ayn.ui.mobile.profiles.color
import com.arshad.ayn.ui.mobile.settings.ProfileTags
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.profiles.SmallTile
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration

/** The open [profile]'s shell; the profile menu's switch row calls [onSwitchProfile] (the gate's
 *  rail slot: a locked profile locks, an unlocked one gets the picker over it, a kids profile asks
 *  the [owner]'s lock to leave). [restored] is set when a Settings restore opened it, until
 *  [onRestoredShown] (Q9); [settingsPickWaiting] while a file picked for its Settings waits
 *  (M5-D20). Either opens Settings › Backup. A kids profile's Parent page leaves it through
 *  [onLeaveKids] with the owner's pass it holds; [onPicture] hears the open video's picture (`null`
 *  with none), for Time's up. */
internal class ShellOpening(
    val profile: Profile,
    val onSwitchProfile: () -> Unit,
    val restored: RestoredBackup? = null,
    val settingsPickWaiting: Boolean = false,
    val onRestoredShown: () -> Unit = {},
    val owner: Profile? = null,
    val onLeaveKids: (OwnerPass) -> Unit = {},
    val onPicture: (String?) -> Unit = {},
)

/** The clock and time zone the Library names days, ages and changes by (fixed in tests). */
internal data class ShellTime(val clock: Clock = Clock.System, val zone: ZoneId = ZoneId.systemDefault())

/** What a shell page needs of the shell: where it is ([nav]), whose it is, the profile menu, and
 *  the player ([play] opens a route's watch page, [playShort] a Short in the Shorts player with
 *  the list it runs through; [shortOpen] reads whether the Shorts player shows). [restored] reads
 *  the opening's restore results, which Backup shows and reports through [onRestoredShown]. A kids
 *  profile's pages read its time from [kidsClock]; its Parent page leaves it through [onLeaveKids]. */
@Stable
internal class ShellChrome(
    val nav: ShellNav,
    val profile: Profile,
    val openProfileMenu: () -> Unit,
    val play: (PlayerRoute) -> Unit,
    val playShort: (short: VideoItem, run: ShortsList) -> Unit = { _, _ -> },
    val shortOpen: () -> Boolean = { false },
    val restored: () -> RestoredBackup? = { null },
    val onRestoredShown: () -> Unit = {},
    val kidsClock: KidsClock? = null,
    val onLeaveKids: (OwnerPass) -> Unit = {},
)

/**
 * The phone shell (`m6-shell-and-profiles` shell, M6-D10 Marquee, M6-D13): pages over the Material 3
 * navigation bar (Home, Subscriptions, Library; a rail on a wide window), each tab with its own
 * saved back stack ([ShellNav]), and [miniPlayer] docked above the bar. Pages carry their own top
 * bar ([ShellTopBar]): its search pill opens Search over the shown tab, its avatar the profile
 * menu: Lock and switch profile (Switch profile when unlocked) and Settings, whose pages slide the
 * bar away ([BarlessKey]). [pages] registers
 * each key's page; a page's Play goes to [onPlay], a Short's to [onPlayShort] ([shortOpen]: the
 * Shorts player shows). [overlay] draws over the whole shell (the watch page, the Shorts player);
 * while it [covered] the pages, they leave the semantics tree. An opening with restore results or
 * a waiting Settings pick lands on Settings › Backup over the shown tab.
 *
 * A kids profile's shell (`m6-kids` 2–8, Q49) is one page with no bar: Home, its pill searching
 * only what is approved, its time left from [kidsClock] under the pill, and its avatar's menu
 * holding Parent ([ParentKey]) and Leave (both behind the owner's lock); the 5- and 1-minute notes show over
 * everything, the watch page included.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileShell(
    opening: ShellOpening,
    modifier: Modifier = Modifier,
    onPlay: (PlayerRoute) -> Unit = {},
    onPlayShort: (short: VideoItem, run: ShortsList) -> Unit = { _, _ -> },
    shortOpen: () -> Boolean = { false },
    covered: Boolean = false,
    kidsClock: KidsClock? = null,
    miniPlayer: @Composable () -> Unit = {},
    overlay: @Composable (ShellChrome) -> Unit = {},
    pages: EntryProviderScope<NavKey>.(ShellChrome) -> Unit,
) {
    val nav = rememberShellNav()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { nav.onAppStopped() }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val currentOpening by rememberUpdatedState(opening)
    val chrome = remember(nav, opening.profile, onPlay, onPlayShort, shortOpen, kidsClock) {
        ShellChrome(
            nav,
            opening.profile,
            openProfileMenu = { menuOpen = true },
            play = onPlay,
            playShort = onPlayShort,
            shortOpen = shortOpen,
            restored = { currentOpening.restored },
            onRestoredShown = { currentOpening.onRestoredShown() },
            kidsClock = kidsClock,
            onLeaveKids = { currentOpening.onLeaveKids(it) },
        )
    }
    val toBackup = opening.restored != null || opening.settingsPickWaiting
    LaunchedEffect(toBackup) { if (toBackup) nav.openSettings(SettingsPage.Backup) }
    val provider = entryProvider { pages(chrome) }
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = AynColors.Ink,
            selectedTextColor = AynColors.Ink,
            indicatorColor = AynColors.Indicator,
            unselectedIconColor = AynColors.Ink2,
            unselectedTextColor = AynColors.Ink2,
        ),
    )
    val labelStyle = MaterialTheme.typography.labelSmall
    val kids = opening.profile.isKids
    val barless = kids || nav.current is BarlessKey
    val suite = rememberNavigationSuiteScaffoldState(if (barless) NavigationSuiteScaffoldValue.Hidden else NavigationSuiteScaffoldValue.Visible)
    LaunchedEffect(barless) { if (barless) suite.hide() else suite.show() }
    Box(modifier) {
        NavigationSuiteScaffold(
            modifier = if (covered) Modifier.clearAndSetSemantics { } else Modifier,
            navigationSuiteColors = NavigationSuiteDefaults.colors(
                navigationBarContainerColor = AynColors.Surface1,
                navigationBarContentColor = AynColors.Ink2,
                navigationRailContainerColor = AynColors.Surface1,
                navigationRailContentColor = AynColors.Ink2,
            ),
            containerColor = AynColors.Ground,
            contentColor = AynColors.Ink,
            state = suite,
            navigationSuiteItems = {
                // A kids profile has Home only: no tabs, not even hidden ones for TalkBack to find.
                if (!kids) Tab.entries.forEach { tab ->
                    val selected = tab == nav.tab
                    item(
                        selected = selected,
                        onClick = { nav.onTabTap(tab) },
                        icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                        label = { Text(tab.label, style = if (selected) labelStyle.atWeight(720) else labelStyle) },
                        colors = itemColors,
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                NavDisplay(entries = nav.entries(provider), onBack = nav::back, modifier = Modifier.weight(1f))
                miniPlayer()
            }
        }
        overlay(chrome)
        if (kidsClock != null) KidsWarning(kidsClock, opening.profile.tint.color, Modifier.align(Alignment.BottomCenter))
    }
    if (menuOpen) {
        val close = { menuOpen = false }
        if (kids) {
            KidsMenu(
                kid = opening.profile,
                owner = opening.owner?.name,
                timeLeft = rememberTimeLeft(kidsClock),
                onDismiss = close,
                onParent = {
                    close()
                    nav.open(ParentKey)
                },
                onLeave = {
                    close()
                    opening.onSwitchProfile()
                },
            )
        } else {
            ProfileMenu(
                profile = opening.profile,
                onDismiss = close,
                onSwitch = {
                    close()
                    opening.onSwitchProfile()
                },
                onSettings = {
                    close()
                    nav.open(SettingsKey)
                },
            )
        }
    }
}

/** The profile menu (`shell` 10): a modal sheet over the dimmed page. */
@Composable
private fun ProfileMenu(profile: Profile, onDismiss: () -> Unit, onSwitch: () -> Unit, onSettings: () -> Unit) {
    MenuSheet(onDismiss) {
        MenuHead(profile, profileLine(profile))
        if (profile.lock != null) {
            MenuRow(R.drawable.ic_lock, "Lock and switch profile", "Locks ${profile.name}, then Who’s watching?", onSwitch)
        } else {
            MenuRow(R.drawable.ic_swap, "Switch profile", "Who’s watching?", onSwitch)
        }
        MenuRow(R.drawable.ic_gear, "Settings", "Playback, SponsorBlock, profiles, backup", onSettings)
        Text(
            text = "Ayn ${appVersion()} · everything it keeps stays on this phone",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = AynColors.Ink3,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
        )
    }
}

/** A kid's menu (`m6-kids` 3): its time left, then Parent and Leave <kid>'s profile, both behind
 *  the [owner]'s lock; no Settings, no Switch profile. */
@Composable
private fun KidsMenu(kid: Profile, owner: String?, timeLeft: Duration?, onDismiss: () -> Unit, onParent: () -> Unit, onLeave: () -> Unit) {
    val needs = owner?.let { " · needs $it’s lock" }.orEmpty()
    MenuSheet(onDismiss) {
        MenuHead(kid, listOfNotNull("Kids profile", timeLeft?.let { "${timeLeftText(it)} left today" }).joinToString(" · "))
        MenuRow(R.drawable.ic_people, "Parent", "What ${kid.name} watched, ${kid.name}’s channels and time$needs", onParent, locked = true)
        MenuRow(R.drawable.ic_leave, "Leave ${kid.name}’s profile", "Opens Who’s watching?$needs", onLeave, locked = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(onDismiss: () -> Unit, rows: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = AynColors.Surface1,
        contentColor = AynColors.Ink,
        scrimColor = AynColors.Scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 16.dp).size(width = 32.dp, height = 4.dp).background(AynColors.Ink3, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 24.dp)) { rows() }
    }
}

/** The menu's head: [profile]'s tile and name (with Kids for a kids profile) over [line]. */
@Composable
private fun MenuHead(profile: Profile, line: String) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProfileTile(profile.name, profile.tint, SmallTile)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                if (profile.isKids) ProfileTags(owner = false, kids = true)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3)
        }
    }
    HorizontalDivider(color = AynColors.Line, modifier = Modifier.padding(bottom = 8.dp))
}

/** Who the profile is and how it locks, under its name. */
internal fun profileLine(profile: Profile): String {
    val role = when {
        profile.isOwner -> "Owner"
        profile.isKids -> "Kids profile"
        else -> "Profile"
    }
    val lock = when (profile.lock) {
        LockKind.Sequence -> "locked with a sequence"
        LockKind.Pin -> "locked with a PIN"
        null -> "not locked"
    }
    return "$role · $lock"
}

@Composable
private fun MenuRow(@DrawableRes icon: Int, title: String, line: String, onClick: () -> Unit, locked: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(line, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3)
        }
        if (locked) Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}
