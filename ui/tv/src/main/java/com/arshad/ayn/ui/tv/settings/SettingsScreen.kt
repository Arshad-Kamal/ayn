package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.ui.tv.focus.FocusOnClose
import androidx.compose.ui.focus.focusRequester
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
import com.arshad.ayn.ui.tv.focus.focusMemory
import androidx.annotation.DrawableRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.settings.BackupScreen
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersSheet
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.KidsAddScreen
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesToast
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsScreen
import com.arshad.ayn.feature.settings.RestrictionsSheet
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.PreferencesSheet
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.SyncScreen
import com.arshad.ayn.feature.settings.SyncTime
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.ui.tv.common.rememberMinuteNow
import com.arshad.ayn.ui.tv.shell.ShellTime
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Appearing
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.common.PageHead
import com.arshad.ayn.ui.tv.common.ToastHost
import com.arshad.ayn.ui.tv.common.ToastLine
import com.arshad.ayn.ui.tv.common.Toasts
import com.arshad.ayn.ui.tv.common.rememberCameFrom
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.focus.SelectDwell
import com.arshad.ayn.ui.tv.focus.SelectDwellMs
import com.arshad.ayn.ui.tv.focus.releasesStayWith
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout

// `b3-settings` `.split` (px ÷ 2): categories 215 dp wide, the pane 35 dp after them, 20 dp
// below the page head.
private val CategoriesWidth = 215.dp
private val SplitGap = 35.dp
private val SplitTop = 20.dp

/** Settings' categories (`b3-settings`, `m8-sync-tv`), in order. [Backup] is Import from YouTube for anyone but the owner. */
internal enum class SettingsCategory { Profiles, Filters, Playback, SponsorBlock, DeArrow, Dislikes, Interface, Sync, Backup, About }

/** A category row's focus key; Settings opens on Profiles from the rail (`cats`). */
internal val SettingsCategory.focusKey: String get() = "category-${name.lowercase()}"

/**
 * Settings (`b3-settings`, `b3-profiles` cats): the categories at the left (Backup & restore is
 * Import from YouTube for anyone but the owner), the one focus chose at the right (CMP-TV-06);
 * [filters] holds Filters, whose Show Shorts row leads to Interface's switch; [preferences] the
 * pages after it up to Backup, and About; [restrictions] the owner's restrictions on another
 * profile, opened from its sheet and shown in the Profiles pane's place (BACK returns to the
 * sheet); [kidsAdd] a kids profile's add flow, covering the shell over Add a profile's kind
 * sheet, whose new row takes focus when it is added; [sync] Settings › Sync, its times said by
 * [time]'s clock and zone. Over the page a sheet
 * slides in from the right edge (the page and the rail dim, [onRailShade]) or a guided screen
 * covers the shell (the rail hides), through [Overlays]; while either shows, nothing behind it
 * takes focus. Closing one returns focus to the row that opened it. A shell opened by a Settings
 * restore starts on Backup & restore with [restored]'s results, which [backup] holds until Done,
 * then reports it shown (Q9, KP-D25). [chooseFile] opens the file picker; its pick reaches [backup] (M5-D20), and Settings
 * opened while one waits ([pickWaiting]) starts on Backup & restore (or Import from YouTube),
 * with that category's row focused.
 */
@Composable
internal fun SettingsScreen(
    profiles: ProfilesViewModel,
    backup: BackupViewModel,
    preferences: PreferencesViewModel,
    filters: FiltersViewModel,
    restrictions: RestrictionsViewModel,
    kidsAdd: KidsAddViewModel,
    sync: SyncViewModel,
    restored: RestoredBackup?,
    pickWaiting: Boolean,
    chooseFile: (PickKind) -> Unit,
    onRestoredShown: () -> Unit,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
    time: ShellTime = ShellTime(),
) {
    val state by profiles.uiState.collectAsStateWithLifecycle()
    val syncState by sync.uiState.collectAsStateWithLifecycle()
    val syncScreen = syncState.screen
    val backupState by backup.uiState.collectAsStateWithLifecycle()
    val preferencesState by preferences.uiState.collectAsStateWithLifecycle()
    val filtersState by filters.uiState.collectAsStateWithLifecycle()
    val restrictionsState by restrictions.uiState.collectAsStateWithLifecycle()
    val kidsState by kidsAdd.uiState.collectAsStateWithLifecycle()
    val screen = state.screen
    val backupScreen = backupState.screen
    val owner = state.me?.isOwner == true
    val version = rememberVersionName()
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    val profilesLayer = layerOf(screen, owner)
    // Backup's screen leads only while Profiles' stays on the page.
    val backupLeads = profilesLayer == Layer.Page && backupScreen != BackupScreen.Page
    // Sync's likewise, while Backup's stays on its page too.
    val syncLeads = profilesLayer == Layer.Page && backupScreen == BackupScreen.Page && syncScreen != SyncScreen.Page
    // Restrictions open from a profile's sheet, which waits under them.
    val restrictionsScreen = restrictionsState.screen
    val restrictionsLeads = restrictionsScreen != RestrictionsScreen.Closed
    val restrictionsPage = restrictionsScreen as? RestrictionsScreen.Page
    // A kids profile's add flow covers the kind sheet it started from.
    val kidsLeads = kidsState.screen != KidsAddScreen.Closed
    val layer = when {
        kidsLeads -> Layer.Cover
        restrictionsLeads -> restrictionsLayerOf(restrictionsScreen)
        backupLeads -> backupLayerOf(backupScreen)
        syncLeads -> syncLayerOf(syncScreen)
        else -> profilesLayer
    }
    // A preference or filter sheet opens only from the page, so it shows only while nothing else does.
    val preferencesSheet = preferencesState.sheet.takeIf { layer == Layer.Page && !restrictionsLeads }
    val filtersSheet = filtersState.sheet.takeIf { layer == Layer.Page && !restrictionsLeads }
    val pageSheetOpen = preferencesSheet != null || filtersSheet != null
    BackHandler(
        enabled = screen != ProfilesScreen.Page || backupScreen != BackupScreen.Page || syncScreen != SyncScreen.Page || pageSheetOpen ||
            restrictionsLeads || kidsLeads,
    ) {
        when {
            kidsLeads -> kidsAdd.onBack()
            restrictionsLeads -> restrictions.onBack()
            backupLeads -> backup.onBack()
            syncLeads -> sync.onBack()
            screen != ProfilesScreen.Page -> profiles.onBack()
            filtersSheet != null -> filters.onBack()
            else -> preferences.onBack()
        }
    }
    val chooseBackup = { chooseFile(PickKind.Backup) }
    val chooseImport = { chooseFile(PickKind.Import) }
    val startsOnPick = remember { pickWaiting }
    // A restore lands on Backup & restore, under its results.
    val startsOnPane = remember { restored != null }
    var category by rememberSaveable {
        mutableStateOf(if (startsOnPick || startsOnPane) SettingsCategory.Backup else SettingsCategory.Profiles)
    }
    // A row another category's pane focuses once it shows (Filters' Show Shorts → Interface's).
    var paneFocus by remember { mutableStateOf<String?>(null) }

    val toasts = remember { Toasts() }
    LaunchedEffect(restored) {
        if (restored == null) return@LaunchedEffect
        backup.onRestored(restored)
        onRestoredShown()
    }
    LaunchedEffect(state.toast) {
        val toast = state.toast ?: return@LaunchedEffect
        toasts.show(toast.line())
        profiles.onToastShown()
    }
    LaunchedEffect(kidsState.added) {
        val id = kidsState.added ?: return@LaunchedEffect
        profiles.onKidAdded()
        kidsAdd.onAddedShown()
        paneFocus = profileKey(id)
    }
    LaunchedEffect(backupState.toast) {
        val toast = backupState.toast ?: return@LaunchedEffect
        toasts.show(toast.line())
        backup.onToastShown()
    }
    LaunchedEffect(syncState.toast) {
        val toast = syncState.toast ?: return@LaunchedEffect
        toasts.show(toast.line())
        sync.onToastShown()
    }
    val syncTime = SyncTime(rememberMinuteNow(time.clock), time.zone, LocalConfiguration.current.locales[0])

    // Done on a restore's results lands on Export a backup; the rail lands on the Profiles
    // category, a waiting pick on Backup & restore (or Import from YouTube).
    val pageEntry = remember { FocusRequester() }
    val ownSheetFrom = remember { OwnSheetEntry() }
    FocusOnClose(open = layer != Layer.Page, target = pageEntry)
    val from = rememberCameFrom(screen, ::screenIdentity)
    val preferencesFrom = rememberCameFrom(preferencesState.sheet) { it }
    val sheet = when {
        layer == Layer.Sheet && restrictionsLeads -> restrictionsPage?.sheet?.let { SettingsSheetShown.OfRestrictions(it) }
        layer == Layer.Sheet && backupLeads -> SettingsSheetShown.OfBackup(backupScreen)
        layer == Layer.Sheet && syncLeads -> SettingsSheetShown.OfSync(syncScreen)
        layer == Layer.Sheet -> SettingsSheetShown.OfProfiles(screen)
        filtersSheet != null -> SettingsSheetShown.OfFilters(filtersSheet)
        preferencesSheet != null -> SettingsSheetShown.OfPreferences(preferencesSheet)
        else -> null
    }
    val cover = when {
        layer != Layer.Cover -> null
        kidsLeads -> Cover.OfKids(kidsState.screen)
        restrictionsLeads -> (restrictionsScreen as? RestrictionsScreen.OwnerGate)?.let { Cover.OfRestrictions(it) }
        backupLeads -> Cover.OfBackup(backupScreen)
        syncLeads -> Cover.OfSync(syncScreen)
        else -> Cover.OfProfiles(screen)
    }

    Box(modifier.fillMaxSize()) {
        Overlays(
            sheet = sheet,
            cover = cover,
            onRailShade = onRailShade,
            sheetContent = { shown ->
                when (shown) {
                    is SettingsSheetShown.OfProfiles ->
                        ProfilesSheet(shown.screen, from, state, profiles, ownSheetFrom, restrictions::onOpen, kidsAdd::onStart)
                    is SettingsSheetShown.OfPreferences ->
                        PreferencesSheetContent(shown.sheet, preferencesFrom, preferencesState, state.me, deviceLanguage, preferences)
                    is SettingsSheetShown.OfBackup -> (shown.screen as? BackupScreen.ExportSheet)?.let { ExportSheet(it, backupState, backup) }
                    is SettingsSheetShown.OfFilters -> FiltersSheetContent(shown.sheet, filtersState, state.me, filters)
                    is SettingsSheetShown.OfRestrictions -> RestrictionsSheetContent(shown.sheet, restrictionsState, restrictions)
                    is SettingsSheetShown.OfSync -> when (shown.screen) {
                        SyncScreen.Services -> SyncServicesSheet(syncState, sync)
                        is SyncScreen.TurnOff -> SyncTurnOffSheet(syncState, sync)
                        else -> Unit
                    }
                }
            },
            coverContent = { shown ->
                when (shown) {
                    is Cover.OfProfiles -> ProfilesCover(shown.screen, state, profiles)
                    is Cover.OfBackup -> BackupCover(shown.screen, backupState, state.me, backup, chooseBackup, chooseImport)
                    is Cover.OfRestrictions -> RestrictionsGate(shown.gate, restrictionsState, restrictions)
                    is Cover.OfKids -> KidsAddCover(kidsState, kidsAdd)
                    is Cover.OfSync -> SyncCover(shown.screen, syncState, state.me, sync, syncTime)
                }
            },
            // Wider than the overlays' own: a release also stays with a screen that changes on the
            // page (a name edited in its row) or inside the owner's sheet (its rename).
            modifier = Modifier.releasesStayWith(
                listOf(
                    screenIdentity(screen),
                    backupIdentity(backupScreen),
                    syncIdentity(syncScreen),
                    preferencesSheet,
                    filtersSheet?.let { SettingsSheetShown.OfFilters(it).identity },
                    restrictionsIdentity(restrictionsScreen),
                    kidsState.screen::class,
                ),
            ),
            sheetKey = SettingsSheetShown::identity,
            coverKey = Cover::identity,
        ) {
            ScreenFocus(
                onNothingToFocus = onNothingToFocus,
                // The chosen category's row: Profiles from the rail, Backup for a waiting pick, and
                // after a restart the category that was showing, whose page may still be loading.
                initialFocusKey = if (startsOnPane) EXPORT_KEY else category.focusKey,
                // Focus waits for the profile to load.
                entryKey = if (state.me == null) null else Unit,
                focusHeldElsewhere = focusHeldElsewhere,
                // Restrictions opening land on their first row.
                returnKey = RESTRICTIONS_FIRST_KEY.takeIf { restrictionsPage?.arriving == true },
                onReturned = restrictions::onPageArrived,
            ) {
                SettingsPage(
                    pageEntry = pageEntry,
                    category = category,
                    version = version,
                    // Backup's row waits for the profile: its name depends on who it is.
                    backupLook = state.me?.let { if (owner) BackupCategory else ImportCategory },
                    // Sync's too: anyone but the owner sees it greyed (M8-D9).
                    syncLook = state.me?.let { if (owner) SyncCategory else SyncCategoryGreyed },
                    onCategory = {
                        // Restrictions show only in the Profiles pane, and while open they hold every
                        // other sheet back: another category closes them and the profile's sheet under them.
                        if (it != SettingsCategory.Profiles && restrictionsPage != null) {
                            restrictions.onLeave()
                            profiles.onDismiss()
                        }
                        category = it
                    },
                ) {
                    val memory = LocalFocusMemory.current
                    // A page sheet returns focus to the row that opened it, also when its last row
                    // went first (the last blocked channel unblocked): focus then already sits in
                    // the page, where entering it again wouldn't restore the row.
                    FocusOnClose(open = pageSheetOpen) { memory?.restore(null) }
                    // A row that isn't there yet (a profile just added) is tried again as the rows change.
                    LaunchedEffect(category, paneFocus, state.rows) {
                        val key = paneFocus ?: return@LaunchedEffect
                        withFrameNanos { }
                        if (memory?.focus(key) == true) paneFocus = null
                    }
                    // A profile's pages wait, like Backup's row, for who the profile is.
                    val me = state.me
                    when (category) {
                        SettingsCategory.Profiles if restrictionsPage != null -> RestrictionsPane(restrictionsState, restrictions)
                        SettingsCategory.Profiles -> ProfilesPane(state, profiles, onLockYours = { ownSheetFrom.lock = true })
                        SettingsCategory.Filters -> FiltersPane(me, filtersState, filters) {
                            paneFocus = INTERFACE_SHORTS_KEY
                            category = SettingsCategory.Interface
                        }
                        SettingsCategory.Playback, SettingsCategory.SponsorBlock, SettingsCategory.DeArrow, SettingsCategory.Dislikes,
                        SettingsCategory.Interface,
                        -> PreferencesPane(category, me, preferencesState, preferences)
                        SettingsCategory.Sync if me == null -> Unit
                        SettingsCategory.Sync -> SyncPane(syncState, sync, syncTime)
                        SettingsCategory.Backup if me == null -> Unit
                        SettingsCategory.Backup if owner -> BackupPane(backupState, backup, onFindBackup = chooseBackup, onImport = chooseImport)
                        SettingsCategory.Backup -> ImportPane(onImport = chooseImport)
                        SettingsCategory.About -> AboutPane(version, preferences)
                    }
                }
            }
        }
        ToastHost(toasts, Modifier.align(Alignment.BottomEnd))
    }
}

/** Where a Settings screen draws: the page itself, a sheet over it, or a screen covering the shell. */
private enum class Layer { Page, Sheet, Cover }

private fun layerOf(screen: ProfilesScreen, owner: Boolean): Layer = when (screen) {
    ProfilesScreen.Page -> Layer.Page
    // A non-owner's name is edited in its row on the page; the owner's in their sheet.
    is ProfilesScreen.Rename -> if (owner) Layer.Sheet else Layer.Page
    ProfilesScreen.OwnSheet, is ProfilesScreen.ProfileSheet, ProfilesScreen.Colour, ProfilesScreen.ChooseKind,
    ProfilesScreen.LockMenu, ProfilesScreen.ProfileKind,
    -> Layer.Sheet
    is ProfilesScreen.ConfirmRemove, is ProfilesScreen.OwnerGate, is ProfilesScreen.CurrentLock,
    is ProfilesScreen.NewLock, is ProfilesScreen.AddForm,
    -> Layer.Cover
}

/** A screen apart from what changes while it shows (its lock entry, the name typed). */
private fun screenIdentity(screen: ProfilesScreen): Any = when (screen) {
    is ProfilesScreen.OwnerGate -> ProfilesScreen.OwnerGate::class to screen.action
    is ProfilesScreen.CurrentLock -> ProfilesScreen.CurrentLock::class to screen.change
    is ProfilesScreen.NewLock -> ProfilesScreen.NewLock::class to screen.lockKind
    is ProfilesScreen.AddForm -> ProfilesScreen.AddForm::class
    is ProfilesScreen.Rename -> ProfilesScreen.Rename::class
    else -> screen
}

/** Restrictions' page takes the Profiles pane's place; its sheets slide over it, its gate covers the shell. */
private fun restrictionsLayerOf(screen: RestrictionsScreen): Layer = when (screen) {
    RestrictionsScreen.Closed -> Layer.Page
    is RestrictionsScreen.OwnerGate -> Layer.Cover
    is RestrictionsScreen.Page -> if (screen.sheet == null) Layer.Page else Layer.Sheet
}

/** A Restrictions screen apart from what changes while it shows (its lock entry, a rule typed, a
 *  suggestion ticked, focus landing). */
private fun restrictionsIdentity(screen: RestrictionsScreen): Any = when (screen) {
    RestrictionsScreen.Closed -> screen
    is RestrictionsScreen.OwnerGate -> RestrictionsScreen.OwnerGate::class
    is RestrictionsScreen.Page -> RestrictionsScreen.Page::class to screen.sheet?.let(::restrictionsSheetIdentity)
}

private fun restrictionsSheetIdentity(sheet: RestrictionsSheet): Any = when (sheet) {
    is RestrictionsSheet.Keywords -> RestrictionsSheet.Keywords::class
    is RestrictionsSheet.Patterns -> RestrictionsSheet.Patterns::class
    is RestrictionsSheet.Suggestions -> RestrictionsSheet.Suggestions::class
    RestrictionsSheet.RestrictedMode, RestrictionsSheet.Length, RestrictionsSheet.BlockedChannels, RestrictionsSheet.BlockedVideos,
    is RestrictionsSheet.Pack,
    -> sheet
}

/** Sync's services and Turn off sheets slide over the page; every other step covers the shell. */
private fun syncLayerOf(screen: SyncScreen): Layer = when (screen) {
    SyncScreen.Page -> Layer.Page
    SyncScreen.Services, is SyncScreen.TurnOff -> Layer.Sheet
    is SyncScreen.OwnerGate, is SyncScreen.NextcloudAddress, is SyncScreen.NextcloudWaiting, is SyncScreen.WebDav,
    is SyncScreen.GoogleSignIn, is SyncScreen.Passphrase, is SyncScreen.Match, is SyncScreen.ReadingMatch,
    -> Layer.Cover
}

/** A Sync screen apart from what changes while it shows (its lock entry, typing, a check under
 *  way, a refusal, a Match choice); reading the profiles and failing to, and Google's sign-in
 *  waiting and stopped, are two screens each, so the failure's Try again takes focus. */
private fun syncIdentity(screen: SyncScreen): Any = when (screen) {
    is SyncScreen.OwnerGate -> SyncScreen.OwnerGate::class to screen.action
    is SyncScreen.ReadingMatch -> SyncScreen.ReadingMatch::class to (screen.failure != null)
    is SyncScreen.GoogleSignIn -> SyncScreen.GoogleSignIn::class to (screen.problem != null)
    SyncScreen.Page, SyncScreen.Services -> screen
    else -> screen::class
}

/** Backup's export sheet slides over the page; every other screen covers the shell. */
private fun backupLayerOf(screen: BackupScreen): Layer = when (screen) {
    BackupScreen.Page -> Layer.Page
    is BackupScreen.ExportSheet -> Layer.Sheet
    else -> Layer.Cover
}

/** A Backup & restore screen apart from what changes while it shows (its lock entry, the ticks,
 *  a row's choice, a restore starting). */
private fun backupIdentity(screen: BackupScreen): Any = when (screen) {
    is BackupScreen.OwnerGate -> BackupScreen.OwnerGate::class to screen.action
    is BackupScreen.ExportSheet -> BackupScreen.ExportSheet::class
    is BackupScreen.RestorePick -> BackupScreen.RestorePick::class
    else -> screen
}

/** The screen covering the shell, from whichever category's ViewModel shows one. */
private sealed interface Cover {
    val identity: Any

    data class OfProfiles(val screen: ProfilesScreen) : Cover {
        override val identity: Any get() = screenIdentity(screen)
    }

    data class OfBackup(val screen: BackupScreen) : Cover {
        override val identity: Any get() = backupIdentity(screen)
    }

    data class OfRestrictions(val gate: RestrictionsScreen.OwnerGate) : Cover {
        override val identity: Any get() = RestrictionsScreen.OwnerGate::class
    }

    /** One cover per step: its lock entry, typing and sheets stay within it. */
    data class OfKids(val screen: KidsAddScreen) : Cover {
        override val identity: Any get() = screen::class
    }

    data class OfSync(val screen: SyncScreen) : Cover {
        override val identity: Any get() = syncIdentity(screen)
    }
}

/** The sheet over the page, from whichever ViewModel shows one. */
private sealed interface SettingsSheetShown {
    val identity: Any

    data class OfProfiles(val screen: ProfilesScreen) : SettingsSheetShown {
        // The owner renames in their own sheet, so the sheet stays while the name is edited.
        override val identity: Any get() = if (screen is ProfilesScreen.Rename) ProfilesScreen.OwnSheet else screenIdentity(screen)
    }

    data class OfPreferences(val sheet: PreferencesSheet) : SettingsSheetShown {
        override val identity: Any get() = sheet
    }

    data class OfBackup(val screen: BackupScreen) : SettingsSheetShown {
        override val identity: Any get() = backupIdentity(screen)
    }

    data class OfRestrictions(val sheet: RestrictionsSheet) : SettingsSheetShown {
        override val identity: Any get() = restrictionsSheetIdentity(sheet)
    }

    data class OfSync(val screen: SyncScreen) : SettingsSheetShown {
        override val identity: Any get() = syncIdentity(screen)
    }

    data class OfFilters(val sheet: FiltersSheet) : SettingsSheetShown {
        // A word typed, or a suggestion ticked, keeps its sheet.
        override val identity: Any
            get() = when (sheet) {
                is FiltersSheet.Keywords -> FiltersSheet.Keywords::class
                is FiltersSheet.Suggestions -> FiltersSheet.Suggestions::class
                FiltersSheet.RestrictedMode, FiltersSheet.Length, FiltersSheet.BlockedChannels, FiltersSheet.BlockedVideos,
                is FiltersSheet.UnblockWhere, is FiltersSheet.Pack,
                -> sheet
            }
    }
}

/** Where the owner's sheet opened from: the lock warning's row starts it on Lock. Read once by
 *  the sheet as it opens. */
internal class OwnSheetEntry {
    var lock: Boolean = false
}

/**
 * The Settings page: the head with Ayn's [version], the categories, the chosen [category]'s
 * [pane], which fades in as it is chosen (D8).
 */
@Composable
private fun SettingsPage(
    pageEntry: FocusRequester,
    category: SettingsCategory,
    version: String?,
    backupLook: CategoryLook?,
    syncLook: CategoryLook?,
    onCategory: (SettingsCategory) -> Unit,
    pane: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        Column {
            PageHead("Settings", listOfNotNull(version?.let { "Ayn $it" }, "on this Shield").joinToString(" · "))
            Row(Modifier.padding(start = KeLayout.ContentStart, end = KeLayout.SafeHorizontal, top = SplitTop)) {
                SettingsCategories(pageEntry, category, backupLook, syncLook, onCategory, Modifier.width(CategoriesWidth))
                Spacer(Modifier.width(SplitGap))
                Box(Modifier.weight(1f)) {
                    key(category) { Appearing { pane() } }
                }
            }
        }
    }
}

// `.navrow` (px ÷ 2): 40 dp tall; the chosen category rests on white at 8% with a 4 dp dot.
private val CategoryHeight = 40.dp
private val ChosenFill = Color.White.copy(alpha = 0.08f)

/** How a category's row shows; a [greyed] one rests dimmed (only the owner acts there). */
internal class CategoryLook(val label: String, @param:DrawableRes val icon: Int, val greyed: Boolean = false)

private val BackupCategory = CategoryLook("Backup & restore", R.drawable.ic_upload)
private val ImportCategory = CategoryLook("Import from YouTube", R.drawable.ic_nav_subscriptions)
private val SyncCategory = CategoryLook("Sync", R.drawable.ic_cloud)
private val SyncCategoryGreyed = CategoryLook("Sync", R.drawable.ic_cloud, greyed = true)

/** Each category's row; Backup's is [backup] and Sync's [sync], `null` (no row) until who the
 *  profile is is known. */
private fun SettingsCategory.look(backup: CategoryLook?, sync: CategoryLook?): CategoryLook? = when (this) {
    SettingsCategory.Profiles -> CategoryLook("Profiles", R.drawable.ic_channel)
    SettingsCategory.Filters -> CategoryLook("Filters", R.drawable.ic_funnel)
    SettingsCategory.Playback -> CategoryLook("Playback", R.drawable.ic_play)
    SettingsCategory.SponsorBlock -> CategoryLook("SponsorBlock", R.drawable.ic_shield)
    SettingsCategory.DeArrow -> CategoryLook("DeArrow", R.drawable.ic_edit)
    SettingsCategory.Dislikes -> CategoryLook("Dislikes", R.drawable.ic_thumb_down)
    SettingsCategory.Interface -> CategoryLook("Interface", R.drawable.ic_interface)
    SettingsCategory.Sync -> sync
    SettingsCategory.Backup -> backup
    SettingsCategory.About -> CategoryLook("About", R.drawable.ic_about)
}

/**
 * Settings' categories (`.snav`); Backup's row shows once [backup] is known (Q1). Focus chooses
 * one ([onChoose], CMP-TV-06) once it has rested on it for [SelectDwellMs] (OK chooses at once),
 * so categories D-pad only passes over build no pane; focus leaving the list first chooses
 * nothing. Entering the list lands on the [chosen] one, so LEFT from a page never switches it.
 * [pageEntry] is the Profiles row, where focus goes back into the page.
 */
@Composable
internal fun SettingsCategories(
    pageEntry: FocusRequester,
    chosen: SettingsCategory,
    backup: CategoryLook?,
    sync: CategoryLook?,
    onChoose: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember { SettingsCategory.entries.associateWith { if (it == SettingsCategory.Profiles) pageEntry else FocusRequester() } }
    val shown = SettingsCategory.entries.mapNotNull { category -> category.look(backup, sync)?.let { category to it } }
    val scope = rememberCoroutineScope()
    val dwell = remember(scope) { SelectDwell(scope) }
    Column(
        modifier
            .widenStart(RowInset)
            .focusProperties {
                onEnter = { rows.getValue(if (shown.any { it.first == chosen }) chosen else SettingsCategory.Profiles).requestFocus() }
            }
            .onFocusChanged { if (!it.hasFocus) dwell.cancel() }
            .focusGroup(),
    ) {
        shown.forEach { (category, look) ->
            CategoryRow(
                label = look.label,
                icon = look.icon,
                greyed = look.greyed,
                chosen = chosen == category,
                // Also on the chosen row: that replaces a pending choice with no change.
                onFocus = { dwell.rest { onChoose(category) } },
                onClick = { dwell.now { onChoose(category) } },
                modifier = Modifier.focusRequester(rows.getValue(category)).focusMemory(category.focusKey),
            )
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    @DrawableRes icon: Int,
    greyed: Boolean,
    chosen: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = ControlStyle.Row.copy(restFill = if (chosen) ChosenFill else ControlStyle.Row.restFill, growFrom = TransformOrigin.Center)
    var focused by remember { mutableStateOf(false) }
    val dim by animateFloatAsState(if (greyed && !focused) UnavailableAlpha else 1f, KeMotion.fast(), label = "categoryDim")
    ControlSurface(
        onClick = onClick,
        style = style,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }.alpha(dim),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = CategoryHeight).padding(horizontal = RowInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            TintedIcon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            TintedText(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1)
            if (chosen) {
                Box(Modifier.size(4.dp).drawBehind { drawCircle(KeColors.Ink) })
            }
        }
    }
}

/** The installed version's name ("0.4"), `null` when the package manager doesn't say. */
@Composable
private fun rememberVersionName(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
}

private fun ProfilesToast.line(): ToastLine = when (this) {
    ProfilesToast.LockSaved -> ToastLine(R.drawable.ic_check, "Lock saved", "your profile asks for it from now on")
    ProfilesToast.LockRemoved -> ToastLine(R.drawable.ic_check, "Lock removed", "your profile opens without asking")
}
