package com.arshad.ayn.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.RuleEdit
import com.arshad.ayn.core.store.SavedIn
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.profiles.LockEntryInput
import com.arshad.ayn.feature.profiles.OwnerGateOpening
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.openOwnerGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a menu's video was listed: what its remove row takes it out of (M5-D17). */
sealed interface VideoSource {
    data object History : VideoSource

    data object WatchLater : VideoSource

    data class Playlist(val playlistId: Long) : VideoSource

    /** Home, Search, Subscriptions, a channel, a YouTube playlist: no remove row. */
    data object Elsewhere : VideoSource

    /** One of For you's picks on Home: no remove row; the menu says why it was picked (FY-D8) and
     *  offers Not interested and Don't recommend this channel, which Home carries out. */
    data class ForYou(val reason: ForYouReason) : VideoSource
}

/** The menu's play rows: "Resume from m:ss" + "Play from the start", or "Play". */
sealed interface MenuResume {
    data object Reading : MenuResume

    data object None : MenuResume

    data class At(val positionMs: Long) : MenuResume
}

sealed interface MenuStage {
    /** The card menu (`b3-library` menu, menulater, menupl). */
    data object Options : MenuStage

    /** Save to playlist: Watch later and each playlist as switches (addto). */
    data object SaveTo : MenuStage

    /** A new playlist named from the save sheet; the video goes in first (Q16, newplsave). */
    data class Naming(val naming: PlaylistNaming) : MenuStage

    /** Block in which profiles? (`kp-filters` blocksheet, blockchoose, KP-D19): [chosen] is `null`
     *  while All profiles is the choice, else the ticked profiles' ids. */
    data class Block(val target: BlockTarget, val chosen: Set<Long>? = null) : MenuStage

    /** The owner's lock before a block made by anyone else reaches another profile (blockmira),
     *  counted on the owner's tries; BACK returns to the sheet as it was ([chosen]). */
    data class BlockGate(
        val target: BlockTarget,
        val chosen: Set<Long>?,
        val lockKind: LockKind,
        val entry: UnlockEntry = UnlockEntry(),
    ) : MenuStage

    /** Approve for [kid]: just this video, or its whole channel (`kp-kids` approvesheet, KP-D10). */
    data class Approve(val kid: Profile) : MenuStage
}

data class VideoMenu(
    val video: VideoItem,
    val source: VideoSource,
    val resume: MenuResume = MenuResume.Reading,
    val stage: MenuStage = MenuStage.Options,
    /** Where the video is saved; `null` until the save sheet's read lands. */
    val savedIn: SavedIn? = null,
    /** The profile's own playlists, most recently updated first; `null` until read. */
    val playlists: List<PlaylistSummary>? = null,
    /** Opened on a Short's card (Q41): Play opens the Shorts player, which keeps no resume point
     *  (Q39), and the head shows YouTube's title and picture (Q40). */
    val short: Boolean = false,
    /** Opened as the Save to playlist sheet alone (the Shorts player's Save, Q48): BACK closes
     *  it rather than stepping back to a menu that never showed. */
    val saveOnly: Boolean = false,
    /** Who Block can reach; `null` (no Block rows) in a kids profile or before profiles are read. */
    val blockers: BlockProfiles? = null,
) {
    val canRemove: Boolean get() = when (source) {
        VideoSource.History, VideoSource.WatchLater, is VideoSource.Playlist -> true
        VideoSource.Elsewhere, is VideoSource.ForYou -> false
    }
}

/**
 * The card menu opened by holding OK on any video (M5-D17, Q14) and its Save to playlist sheet,
 * for the screen that hosts it (one per destination). Play, channel and the player's start-over
 * are the screen's navigation; this owns the resume point shown (none while the profile's
 * "Resume where you left off" is off, Q57), saving, and removing the video from where it was
 * listed. A result that lands after the menu closed or moved on is dropped.
 *
 * Block (KP-D19, [blocking]) writes the video or its channel as the open profile's own rule and
 * as an owner restriction on each other profile chosen. The owner's open profile has the owner's
 * pass; anyone else reaching past their own profile enters the owner's lock first. In the owner's
 * open profile each kids profile also gets Approve for and Block for (KP-D10): an approved video
 * or channel, or a blocked video, written with that pass. The menu then closes on [notice], whose
 * Undo takes back what was added.
 */
class VideoMenuViewModel(
    private val watchStore: WatchStore,
    private val playlistStore: PlaylistStore,
    private val settingsStore: ProfileSettingsStore,
    private val blocking: MenuBlocking,
) : ViewModel() {
    private val _menu = MutableStateFlow<VideoMenu?>(null)
    val menu: StateFlow<VideoMenu?> = _menu.asStateFlow()

    private val _notice = MutableStateFlow<MenuNotice?>(null)

    /** The block or approval just made, while its notice shows; [onNoticeClosed] consumes it. */
    val notice: StateFlow<MenuNotice?> = _notice.asStateFlow()

    private val gateEntry = LockEntryInput(
        scope = viewModelScope,
        timeSource = blocking.timeSource,
        read = { (_menu.value?.stage as? MenuStage.BlockGate)?.entry },
        write = ::updateGateEntry,
    )

    /** The open profile and every profile here, as last read; `null` in a kids profile. */
    private var blockers: BlockProfiles? = null

    /** A block or approval starting (its gate being read): a second press waits it out. */
    private var curationStart: Job? = null

    /** What the shown notice's Undo takes back; dropped with the notice (the pass with it). */
    private var undo: (suspend () -> Unit)? = null

    /** Bumped by every open and close; async results carry the one they started under. */
    private var generation = 0
    private var resumeRead: Job? = null
    private var saveSheetRead: Job? = null

    /** The store's last word on the open menu's video: a switch flips at once and returns here
     *  if its write fails. */
    private var storeSavedIn: SavedIn? = null

    init {
        viewModelScope.launch {
            combine(settingsStore.settings().map { it.profileId }.distinctUntilChanged(), blocking.profiles.profiles()) { open, all ->
                all.firstOrNull { it.id == open }?.takeIf { !it.isKids }?.let { BlockProfiles(it, all) }
            }
                .orFailed("block profiles read", null)
                .collect { blockers = it }
        }
    }

    fun open(video: VideoItem, source: VideoSource, short: Boolean = false) {
        if (short) {
            reset(VideoMenu(video, source, resume = MenuResume.None, short = true, blockers = blockers))
            return
        }
        val opened = reset(VideoMenu(video, source, blockers = blockers))
        resumeRead = viewModelScope.launch {
            // A store that can't answer offers Play, as the player then starts from the beginning;
            // unreadable settings resume, as the player does.
            val resumes = attempt("menu settings read") { settingsStore.settings().first().settings.resume } ?: true
            val point = if (resumes) attempt("menu resume read") { watchStore.resumePointMs(video.id) } else null
            val resume = point?.let(MenuResume::At) ?: MenuResume.None
            update(opened) { it.copy(resume = resume) }
        }
    }

    /** The Save to playlist sheet alone for [video] (the Shorts player's Save, Q48). */
    fun openSaveTo(video: VideoItem, short: Boolean = false) {
        val menu = VideoMenu(video, VideoSource.Elsewhere, resume = MenuResume.None, stage = MenuStage.SaveTo, short = short, saveOnly = true)
        reset(menu)
        followSaveSheet(menu)
    }

    fun close() {
        reset(null)
    }

    /** BACK: naming returns to the save sheet, the save sheet to the menu (or closes, opened alone),
     *  the Block sheet to the menu, the owner's lock (once lock input has nothing to erase) to the
     *  Block sheet; the menu closes. */
    fun onBack() {
        val menu = _menu.value ?: return
        when (val stage = menu.stage) {
            is MenuStage.Naming -> if (!stage.naming.saving) edit { it.copy(stage = MenuStage.SaveTo) }
            MenuStage.SaveTo -> if (menu.saveOnly) close() else edit { it.copy(stage = MenuStage.Options) }
            MenuStage.Options -> close()
            is MenuStage.Block, is MenuStage.Approve -> edit { it.copy(stage = MenuStage.Options) }
            is MenuStage.BlockGate -> if (!gateEntry.back()) leaveGate(stage)
        }
    }

    /** Touch Back or Cancel on the owner's lock (the phone, whose pads erase with their own key):
     *  back to the Block sheet as it was, presses entered dropped; taken without effect while a try
     *  is checked. */
    fun onGateLeave() {
        val gate = _menu.value?.stage as? MenuStage.BlockGate ?: return
        if (!gate.entry.checking) leaveGate(gate)
    }

    private fun leaveGate(gate: MenuStage.BlockGate) {
        gateEntry.clear()
        edit { it.copy(stage = MenuStage.Block(gate.target, gate.chosen)) }
    }

    fun onSaveTo() {
        val menu = _menu.value?.takeIf { it.stage == MenuStage.Options } ?: return
        edit { it.copy(stage = MenuStage.SaveTo) }
        followSaveSheet(menu)
    }

    /** Follows where [menu]'s video is saved and the profile's playlists, for the save sheet. */
    private fun followSaveSheet(menu: VideoMenu) {
        if (saveSheetRead?.isActive == true) return // still following the store from an earlier visit
        val opened = generation
        saveSheetRead = viewModelScope.launch {
            combine(playlistStore.savedIn(menu.video.id), playlistStore.playlists()) { savedIn, playlists -> savedIn to playlists }
                .orFailed("save sheet read", null)
                .collect { read ->
                    if (read != null && opened == generation) {
                        storeSavedIn = read.first
                        val playlists = keptOrder(read.second, _menu.value?.playlists)
                        edit { it.copy(savedIn = read.first, playlists = playlists) }
                    }
                }
        }
    }

    /** Flips the switch at once (a second press flips it back); the store's flow then confirms. */
    fun onToggleWatchLater() {
        val menu = saveSheet() ?: return
        val on = !(menu.savedIn ?: return).watchLater
        flip(menu.savedIn.copy(watchLater = on)) {
            if (on) playlistStore.addToWatchLater(menu.video) else playlistStore.removeFromWatchLater(menu.video.id)
        }
    }

    fun onTogglePlaylist(playlistId: Long) {
        val menu = saveSheet() ?: return
        val saved = menu.savedIn ?: return
        val on = playlistId !in saved.playlistIds
        flip(saved.copy(playlistIds = if (on) saved.playlistIds + playlistId else saved.playlistIds - playlistId)) {
            if (on) playlistStore.add(playlistId, menu.video) else playlistStore.remove(playlistId, menu.video.id)
        }
    }

    fun onNewPlaylist() {
        if (saveSheet() != null) edit { it.copy(stage = MenuStage.Naming(PlaylistNaming())) }
    }

    fun onNamingChange(name: String) {
        val naming = (_menu.value?.stage as? MenuStage.Naming)?.naming ?: return
        edit { it.copy(stage = MenuStage.Naming(naming.edited(name))) }
    }

    /**
     * Create and save (Q16): the playlist is created, the video saved into it, and the save sheet
     * shows its switch. A failed create keeps the name for another try; once the playlist exists
     * the sheet shows whatever the save left (a failed save: its switch off), so Done can't make a
     * second playlist.
     */
    fun onNamingDone() {
        val menu = _menu.value ?: return
        val naming = (menu.stage as? MenuStage.Naming)?.naming?.takeIf { it.canSave } ?: return
        val opened = generation
        edit { it.copy(stage = MenuStage.Naming(naming.copy(saving = true))) }
        viewModelScope.launch {
            val created = attempt("playlist create") { playlistStore.create(naming.name) }
            if (created != null) attempt("save to the new playlist") { playlistStore.add(created, menu.video) }
            update(opened) { it.copy(stage = if (created != null) MenuStage.SaveTo else MenuStage.Naming(naming)) }
        }
    }

    /** Takes the video out of where it was listed, then closes the menu. */
    fun onRemove() {
        val menu = _menu.value?.takeIf { it.canRemove && it.stage == MenuStage.Options } ?: return
        close()
        viewModelScope.launch {
            attempt("menu remove") {
                when (val source = menu.source) {
                    VideoSource.History -> watchStore.remove(menu.video.id)
                    VideoSource.WatchLater -> playlistStore.removeFromWatchLater(menu.video.id)
                    is VideoSource.Playlist -> playlistStore.remove(source.playlistId, menu.video.id)
                    VideoSource.Elsewhere, is VideoSource.ForYou -> Unit
                }
            }
        }
    }

    /** Block <channel> or Block this video: the sheet asking in which profiles. */
    fun onBlock(target: BlockTarget) {
        val menu = _menu.value ?: return
        if (menu.stage == MenuStage.Options && menu.blockers != null) edit { it.copy(stage = MenuStage.Block(target)) }
    }

    /** All profiles: blocks at once, after the owner's lock when it is asked for. */
    fun onBlockAll() {
        val stage = blockSheet() ?: return
        startBlock(stage.target, chosen = null)
    }

    /** Choose profiles: one tick per profile, the open profile's ticked. */
    fun onBlockChoose() {
        val stage = blockSheet() ?: return
        val self = _menu.value?.blockers?.self ?: return
        if (stage.chosen == null) edit { it.copy(stage = stage.copy(chosen = setOf(self.id))) }
    }

    fun onBlockToggle(profileId: Long) {
        val stage = blockSheet() ?: return
        val chosen = stage.chosen ?: return
        edit { it.copy(stage = stage.copy(chosen = if (profileId in chosen) chosen - profileId else chosen + profileId)) }
    }

    /** Block in N profiles. */
    fun onBlockChosen() {
        val stage = blockSheet() ?: return
        val chosen = stage.chosen?.takeIf { it.isNotEmpty() } ?: return
        startBlock(stage.target, chosen)
    }

    fun onGatePress(press: Press) {
        gateEntry.press(press)
    }

    fun onGateDigit(digit: Char) {
        gateEntry.digit(digit)
    }

    fun onGateSubmitSequence() {
        gateEntry.submitSequence()
    }

    fun onGateErase() {
        gateEntry.erase()
    }

    fun onGateRejectionShown() {
        gateEntry.rejectionShown()
    }

    /** Approve for [kid] (the owner's menu): the sheet asking for the video or its channel. */
    fun onApprove(kid: Profile) {
        val menu = _menu.value ?: return
        if (menu.stage == MenuStage.Options && menu.blockers?.kids?.any { it.id == kid.id } == true) {
            edit { it.copy(stage = MenuStage.Approve(kid)) }
        }
    }

    /** Approve this video: it shows for the kid without its channel approved. The notice names it
     *  by [shownTitle], the title its card showed (DeArrow's when it had one; `null` while the face
     *  is still coming: YouTube's); the approval keeps YouTube's. */
    fun onApproveVideo(shownTitle: String?) {
        val menu = _menu.value ?: return
        val kid = (menu.stage as? MenuStage.Approve)?.kid ?: return
        val video = menu.video
        val approval = ApprovedVideo(
            videoId = video.id.value,
            title = video.title,
            channelName = video.uploaderName,
            channelUrl = video.uploaderUrl,
            thumbnailUrl = video.thumbnailUrl,
            durationSeconds = video.durationSeconds.takeIf { it > 0 },
        )
        curate(kid, shownTitle ?: video.title) { pass ->
            when (blocking.filters.addRestriction(pass, kid.id, approval)) {
                RuleEdit.Added -> Curated { blocking.filters.removeRestriction(pass, kid.id, approval) }
                RuleEdit.AlreadyThere -> Curated(undo = null)
                RuleEdit.Invalid, RuleEdit.Full, RuleEdit.Failed -> null
            }
        }
    }

    /** Approve the whole channel: all its videos, new ones too; it joins the kid's channels. */
    fun onApproveChannel() {
        val menu = _menu.value ?: return
        val kid = (menu.stage as? MenuStage.Approve)?.kid ?: return
        val url = menu.video.uploaderUrl ?: return
        val name = menu.video.uploaderName ?: return
        val channel = ChannelItem(name, url, avatarUrl = null, subscriberCount = -1, description = null, verified = false)
        curate(kid, name) { pass ->
            val before = blocking.filters.approvedChannels(kid.id).first().any { it.channelUrl == canonicalChannelUrl(url) }
            when {
                !blocking.filters.approveChannel(pass, kid.id, channel) -> null
                before -> Curated(undo = null)
                else -> Curated { blocking.filters.removeApprovedChannel(pass, kid.id, url) }
            }
        }
    }

    /** Block for [kid]: this video, even from a channel approved for them (KP-D10), named by
     *  [shownTitle], the title its card showed. */
    fun onBlockForKid(kid: Profile, shownTitle: String?) {
        val menu = _menu.value?.takeIf { it.stage == MenuStage.Options } ?: return
        val blockers = menu.blockers?.takeIf { all -> all.kids.any { it.id == kid.id } } ?: return
        if (curationStart?.isActive == true) return
        val opened = generation
        curationStart = viewModelScope.launch {
            val pass = blocking.locks.ownerPassInOwnSession() ?: return@launch
            finishBlock(opened, BlockTarget.Video(menu.video, shownTitle), listOf(kid.id), blockers, pass)
        }
    }

    /** Undo on the notice: what it reports is taken back. */
    fun onUndoNotice() {
        if (_notice.value == null) return
        val taking = undo
        onNoticeClosed()
        if (taking != null) viewModelScope.launch { attempt("notice undo") { taking() } }
    }

    /** The notice closed without Undo: what it reports stands. */
    fun onNoticeClosed() {
        _notice.value = null
        undo = null
    }

    private fun blockSheet(): MenuStage.Block? = _menu.value?.stage as? MenuStage.Block

    /**
     * Blocks [target] in the [chosen] profiles (every one when `null`): at once when that is only
     * the open profile or the owner's open profile blocks; otherwise through the owner gate, which
     * shows the owner's lock when the owner has one.
     */
    private fun startBlock(target: BlockTarget, chosen: Set<Long>?) {
        val blockers = _menu.value?.blockers ?: return
        if (curationStart?.isActive == true) return
        val ids = blockers.all.map { it.id }.filter { chosen == null || it in chosen }
        val opened = generation
        curationStart = viewModelScope.launch {
            if (ids.all { it == blockers.self.id }) return@launch finishBlock(opened, target, ids, blockers, pass = null)
            val ownPass = if (blockers.self.isOwner) blocking.locks.ownerPassInOwnSession() else null
            if (ownPass != null) return@launch finishBlock(opened, target, ids, blockers, ownPass)
            val owner = blockers.owner ?: return@launch
            val opening = blocking.locks.openOwnerGate(owner.id)
            if (opened != generation) return@launch
            when (opening) {
                is OwnerGateOpening.Free -> finishBlock(opened, target, ids, blockers, opening.pass)
                is OwnerGateOpening.Ask -> {
                    edit { it.copy(stage = MenuStage.BlockGate(target, chosen, opening.kind)) }
                    gateEntry.startTry(opening.kind, check = blocking.locks::unlockOwner) { pass ->
                        finishBlock(opened, target, ids, blockers, pass)
                    }
                    opening.wait?.let(gateEntry::waitFor)
                }
                OwnerGateOpening.Changed -> Unit
            }
        }
    }

    /** Writes the rule to each of [ids] ([pass] for every profile but the open one), then closes
     *  the menu on the notice naming where it now holds. */
    private suspend fun finishBlock(opened: Int, target: BlockTarget, ids: List<Long>, blockers: BlockProfiles, pass: OwnerPass?) {
        val rule = target.rule
        val added = mutableListOf<Long>()
        val holds = HashSet<Long>()
        ids.forEach { profileId ->
            val edit = attempt("block write") {
                if (profileId == blockers.self.id) {
                    blocking.filters.addOwn(profileId, rule)
                } else {
                    blocking.filters.addRestriction(checkNotNull(pass), profileId, rule)
                }
            }
            if (edit == RuleEdit.Added) added += profileId
            if (edit == RuleEdit.Added || edit == RuleEdit.AlreadyThere) holds += profileId
        }
        if (opened != generation) return
        val takeBack: suspend () -> Unit = {
            added.forEach { profileId ->
                attempt("block undo") {
                    if (profileId == blockers.self.id) {
                        blocking.filters.removeOwn(profileId, rule)
                    } else {
                        blocking.filters.removeRestriction(checkNotNull(pass), profileId, rule)
                    }
                }
            }
        }
        val names = blockers.all.filter { it.id in holds }.map(Profile::name)
        showNotice(BlockNotice(target, names, everywhere = holds.size == blockers.all.size), takeBack.takeIf { added.isNotEmpty() })
    }

    /**
     * Writes an approval for [kid] with the owner's pass ([write]: `null` when it failed), then
     * closes the menu on its notice naming [what]. Only the owner's open profile offers approvals,
     * so the pass is there without a lock.
     */
    private fun curate(kid: Profile, what: String, write: suspend (OwnerPass) -> Curated?) {
        if (curationStart?.isActive == true) return
        val opened = generation
        curationStart = viewModelScope.launch {
            val pass = blocking.locks.ownerPassInOwnSession()
            val curated = pass?.let { attempt("approve write") { write(it) } }
            if (opened != generation) return@launch
            showNotice(ApproveNotice(kid.name, what, done = curated != null), curated?.undo)
        }
    }

    private fun showNotice(notice: MenuNotice, takeBack: (suspend () -> Unit)?) {
        undo = takeBack
        _notice.value = notice
        reset(null)
    }

    private fun updateGateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _menu.update { menu ->
            val gate = menu?.stage as? MenuStage.BlockGate ?: return@update menu
            menu.copy(stage = gate.copy(entry = change(gate.entry)))
        }
    }

    /**
     * [playlists] (most recently updated first) in the order [shown] already lists them, new ones
     * first: a switch stamps its playlist, which must not move the row under focus.
     */
    private fun keptOrder(playlists: List<PlaylistSummary>, shown: List<PlaylistSummary>?): List<PlaylistSummary> {
        if (shown == null) return playlists
        val byId = playlists.associateBy { it.id }
        val shownIds = shown.mapTo(HashSet()) { it.id }
        return playlists.filter { it.id !in shownIds } + shown.mapNotNull { byId[it.id] }
    }

    private fun saveSheet(): VideoMenu? = _menu.value?.takeIf { it.stage == MenuStage.SaveTo }

    private fun flip(shown: SavedIn, write: suspend () -> Unit) {
        val opened = generation
        edit { it.copy(savedIn = shown) }
        viewModelScope.launch {
            if (attempt("save sheet toggle") { write() } == null) update(opened) { it.copy(savedIn = storeSavedIn) }
        }
    }

    private fun reset(menu: VideoMenu?): Int {
        resumeRead?.cancel()
        saveSheetRead?.cancel()
        resumeRead = null
        saveSheetRead = null
        storeSavedIn = null
        gateEntry.clear()
        _menu.value = menu
        return ++generation
    }

    /** A change made now, on the menu that is open. */
    private fun edit(change: (VideoMenu) -> VideoMenu) {
        _menu.update { menu -> menu?.let(change) }
    }

    /** An async result for the menu opened as [opened]; dropped once another opened or it closed. */
    private fun update(opened: Int, change: (VideoMenu) -> VideoMenu) {
        if (opened == generation) edit(change)
    }
}

/** An approval written: [undo] takes it back, `null` when it was there already. */
private class Curated(val undo: (suspend () -> Unit)?)
