package com.arshad.ayn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.sync.CredentialCheck
import com.arshad.ayn.core.sync.DriveSignIn
import com.arshad.ayn.core.sync.DriveSignInStart
import com.arshad.ayn.core.sync.FolderLook
import com.arshad.ayn.core.sync.LinkResult
import com.arshad.ayn.core.sync.MatchLook
import com.arshad.ayn.core.sync.SetupFailure
import com.arshad.ayn.core.sync.SignInEnd
import com.arshad.ayn.core.sync.SignInStart
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncControl
import com.arshad.ayn.core.sync.SyncCredential
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncSignIn
import com.arshad.ayn.core.sync.account
import com.arshad.ayn.core.sync.webDavCredential
import com.arshad.ayn.feature.profiles.LockEntryInput
import com.arshad.ayn.feature.profiles.OwnerGateOpening
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.openOwnerGate
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A new sync passphrase has at least this many characters. */
const val MIN_PASSPHRASE = 8

/**
 * Settings › Sync (`m8-sync-tv`, `m8-sync-phone`; M8-D9, M8-D11, M8-D18), the owner's page. Turn
 * on, Sign in again and Turn off ask the owner's lock first (while the owner has one); the runner
 * takes no pass, so the gate is this page's. Turn on: a service → its sign-in (Nextcloud's and
 * Google's wait in [viewModelScope], so they outlive the phone's trip to the browser; Google Drive
 * only with a [driveSignIn]) → a look in the folder →
 * the passphrase, new or joining → for a join, Match your profiles; leaving Match unlinks, and
 * Turn on while a join awaits Match goes back to it. Sign in again must reach the linked folder,
 * then only the credential changes. A lost link (LINK_LOST) links again through Turn on, keeping
 * this device's id. [requestSync] asks the sync triggers for a round (Sync now). Main thread;
 * [timeSource] runs lock waits and must count deep sleep (`elapsedRealtime`).
 */
class SyncViewModel(
    private val sync: SyncControl,
    private val signIn: SyncSignIn,
    private val driveSignIn: DriveSignIn?,
    profileStore: ProfileStore,
    private val locks: ProfileLocks,
    private val requestSync: () -> Unit,
    private val profileId: Long,
    timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SyncUiState(drive = driveSignIn != null, status = sync.status.value))
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val entry = LockEntryInput(
        scope = viewModelScope,
        timeSource = timeSource,
        read = { (screen as? SyncScreen.OwnerGate)?.entry },
        write = ::updateEntry,
    )

    private var ownerId: Long? = null

    /** The folder's sign-in from its service's step until the link; only while the passphrase
     *  shows ([moveTo]). */
    private var credential: SyncCredential? = null

    /** The setup step under way (a sign-in wait, a look, a link, Match): cancelled by [show],
     *  never by the screens it moves to itself. */
    private var step: Job? = null

    /** The unlink after leaving Match: the status reads awaiting Match until it lands. */
    private var leaving: Job? = null

    init {
        viewModelScope.launch {
            profileStore.profiles().collect { profiles ->
                val me = profiles.firstOrNull { it.id == profileId }
                ownerId = me?.id?.takeIf { me.isOwner }
                _uiState.update { it.copy(isOwner = me?.isOwner == true) }
            }
        }
        viewModelScope.launch {
            sync.status.collect { status -> _uiState.update { it.copy(status = status) } }
        }
    }

    fun onToastShown() {
        _uiState.update { it.copy(toast = null) }
    }

    // The page

    fun onTurnOn() {
        if (onOwnersPage() && _uiState.value.off) askOwner(SyncAction.TurnOn)
    }

    /** Link again after [SyncFailure.LINK_LOST]: Turn on's flow, which keeps this device's id. */
    fun onLinkAgain() {
        val status = _uiState.value.status
        if (onOwnersPage() && status.linked && status.failure == SyncFailure.LINK_LOST) askOwner(SyncAction.TurnOn)
    }

    fun onSignInAgain() {
        val status = _uiState.value.status
        if (onOwnersPage() && status.failure == SyncFailure.SIGN_IN_REJECTED && status.account != null) askOwner(SyncAction.SignInAgain)
    }

    fun onSyncNow() {
        val status = _uiState.value.status
        if (onOwnersPage() && status.linked && !status.awaitingMatch && !status.syncing) requestSync()
    }

    fun onTurnOff() {
        if (onOwnersPage() && !_uiState.value.off) askOwner(SyncAction.TurnOff)
    }

    // Choosing and signing in

    fun onGoogleDrive() {
        if (screen == SyncScreen.Services) startGoogle(again = false)
    }

    /** Try again, once Google's sign-in stopped with a problem: a new code. */
    fun onGoogleTryAgain() {
        val shown = screen as? SyncScreen.GoogleSignIn ?: return
        if (shown.problem != null) startGoogle(shown.again)
    }

    fun onNextcloud() {
        if (screen == SyncScreen.Services) show(SyncScreen.NextcloudAddress())
    }

    fun onWebDav() {
        if (screen == SyncScreen.Services) show(SyncScreen.WebDav())
    }

    /** Continue on Nextcloud's address: its sign-in starts, then waits for the grant. */
    fun onNextcloudContinue(address: String) {
        val shown = screen as? SyncScreen.NextcloudAddress ?: return
        if (shown.checking) return
        val typed = shown.copy(address = address.trim(), problem = null)
        show(typed.copy(checking = true))
        step = viewModelScope.launch {
            when (val start = signIn.start(typed.address)) {
                is SignInStart.Refused -> moveTo(typed.copy(problem = start.failure.problem()))
                is SignInStart.Started -> {
                    val waiting = SyncScreen.NextcloudWaiting(start.loginUrl, start.server, typed.address, again = typed.again)
                    moveTo(waiting)
                    granted(signIn.awaitGrant(start), typed.again, waiting.copy(checking = true)) { typed.copy(problem = it) }
                }
            }
        }
    }

    /** Google's sign-in: a code, then the wait until the user allows Ayn on another device. */
    private fun startGoogle(again: Boolean) {
        val google = driveSignIn ?: return
        val asking = SyncScreen.GoogleSignIn(again = again)
        show(asking)
        step = viewModelScope.launch {
            when (val start = google.start()) {
                is DriveSignInStart.Refused -> moveTo(asking.copy(problem = start.failure.problem()))
                is DriveSignInStart.Started -> {
                    val waiting = asking.copy(code = DriveCode(start.userCode, start.verificationUrl, start.minutes))
                    moveTo(waiting)
                    granted(google.awaitGrant(start), again, waiting.copy(checking = true)) { asking.copy(problem = it) }
                }
            }
        }
    }

    /** A sign-in wait's [end]: a grant goes on as [signedIn] does; else [problem] says why. The poll
     *  finishes even when the wait was left (the grant comes once): its answer is dropped then. */
    private suspend fun granted(end: SignInEnd, again: Boolean, checking: SyncScreen, problem: (SetupProblem) -> SyncScreen) {
        currentCoroutineContext().ensureActive()
        when (end) {
            is SignInEnd.Granted -> signedIn(end.credential, again, checking, problem)
            SignInEnd.Expired -> moveTo(problem(SetupProblem.EXPIRED))
            is SignInEnd.Refused -> moveTo(problem(end.failure.problem()))
        }
    }

    /** Change address, while waiting for Nextcloud's grant. */
    fun onChangeAddress() {
        val waiting = screen as? SyncScreen.NextcloudWaiting ?: return
        if (!waiting.checking) show(SyncScreen.NextcloudAddress(waiting.address, again = waiting.again))
    }

    /** Connect on another WebDAV server's fields; none may be blank. */
    fun onWebDavConnect(address: String, user: String, password: String) {
        val shown = screen as? SyncScreen.WebDav ?: return
        if (shown.checking || address.isBlank() || user.isBlank() || password.isEmpty()) return
        val typed = shown.copy(address = address.trim(), user = user.trim(), problem = null)
        when (val check = webDavCredential(typed.address, typed.user, password)) {
            is CredentialCheck.Refused -> show(typed.copy(problem = check.failure.problem()))
            is CredentialCheck.Ready -> {
                show(typed.copy(checking = true))
                step = viewModelScope.launch { signedIn(check.credential, typed.again, typed.copy(checking = true)) { typed.copy(problem = it) } }
            }
        }
    }

    // The passphrase

    /** Turn on sync (a new passphrase, at least [MIN_PASSPHRASE] characters) or Continue (the
     *  folder's). [passphrase] is wiped. */
    fun onPassphrase(passphrase: CharArray) {
        val shown = screen as? SyncScreen.Passphrase
        val held = credential
        val short = passphrase.isEmpty() || (shown?.mayStartFolder == true && passphrase.size < MIN_PASSPHRASE)
        if (shown == null || held == null || shown.linking || short) {
            passphrase.fill('\u0000')
            return
        }
        moveTo(shown.copy(wrong = false, failure = null, linking = true))
        step = viewModelScope.launch {
            when (val result = sync.link(held, passphrase)) {
                LinkResult.WrongPassphrase -> moveTo(shown.copy(wrong = true))
                is LinkResult.Failed -> moveTo(shown.copy(failure = result.failure))
                is LinkResult.Linked if sync.status.value.awaitingMatch -> readMatch(shown.account)
                is LinkResult.Linked -> {
                    // Linked again past Match (its account's choices kept): the link runs no round itself.
                    if (result.joined) requestSync()
                    finish(SyncToast.TurnedOn())
                }
            }
        }
    }

    // Match your profiles

    /** A row of Match: its choice sheet (none for a fixed pair). */
    fun onMatchRow(syncId: String) {
        val shown = screen as? SyncScreen.Match ?: return
        if (shown.sheet != null || shown.starting) return
        val match = shown.match
        val sheet = when {
            match.preview.local.any { it.syncId == syncId } -> MatchSheet.Here(syncId).takeIf { syncId !in match.fixed }
            match.remoteOnly.any { it.syncId == syncId } -> MatchSheet.There(syncId)
            else -> null
        }
        if (sheet != null) moveTo(shown.copy(sheet = sheet))
    }

    /** With [remoteId], in a local row's sheet. */
    fun onMatchWith(remoteId: String) {
        updateMatch { match, sheet -> (sheet as? MatchSheet.Here)?.let { match.pairedWith(it.syncId, remoteId) } }
    }

    fun onKeepApart() {
        updateMatch { match, sheet -> (sheet as? MatchSheet.Here)?.let { match.keptApart(it.syncId) } }
    }

    fun onAddHere() {
        updateMatch { match, sheet -> (sheet as? MatchSheet.There)?.let { match.addedHere(it.syncId) } }
    }

    fun onDontAdd() {
        updateMatch { match, sheet -> (sheet as? MatchSheet.There)?.let { match.notAdded(it.syncId) } }
    }

    fun onStartSyncing() {
        val shown = screen as? SyncScreen.Match ?: return
        if (shown.sheet != null || shown.starting) return
        moveTo(shown.copy(starting = true))
        step = viewModelScope.launch {
            sync.startSyncing(shown.match.choices())
            finish(SyncToast.TurnedOn(shown.match.countHere, matchDevices(shown.match.preview).map { it.name }))
        }
    }

    /** Try again, when the profiles to match couldn't be read. */
    fun onReadMatchAgain() {
        val shown = screen as? SyncScreen.ReadingMatch ?: return
        if (shown.failure == null) return
        moveTo(SyncScreen.ReadingMatch())
        step = viewModelScope.launch { readMatch(_uiState.value.status.account) }
    }

    // Turning off

    fun onConfirmTurnOff() {
        val shown = screen as? SyncScreen.TurnOff ?: return
        if (shown.running) return
        moveTo(SyncScreen.TurnOff(running = true))
        step = viewModelScope.launch {
            val service = sync.status.value.account?.service
            val removed = sync.unlink()
            finish(SyncToast.TurnedOff(filesLeft = !removed, service = service))
        }
    }

    // Lock entry

    fun onPress(press: Press) {
        entry.press(press)
    }

    fun onDigit(digit: Char) {
        entry.digit(digit)
    }

    fun onSubmitSequence() {
        entry.submitSequence()
    }

    fun onErase() {
        entry.erase()
    }

    fun onRejectionShown() {
        entry.rejectionShown()
    }

    /**
     * BACK: lock input erases first (and holds while a check runs); a Match sheet closes; Match
     * itself is left, which unlinks; a sign-in step goes back to the services (Sign in again's to
     * the page); anything else to the page. Ignored while a link, Start syncing or Turn off runs.
     */
    fun onBack() {
        if (entry.back() || screen.busy) return
        when (val shown = screen) {
            SyncScreen.Page -> Unit
            is SyncScreen.Match if shown.sheet != null -> moveTo(shown.copy(sheet = null))
            is SyncScreen.Match, is SyncScreen.ReadingMatch -> leaveMatch()
            is SyncScreen.NextcloudAddress, is SyncScreen.NextcloudWaiting, is SyncScreen.WebDav, is SyncScreen.GoogleSignIn ->
                show(if (shown.signingInAgain) SyncScreen.Page else SyncScreen.Services)
            is SyncScreen.OwnerGate, SyncScreen.Services, is SyncScreen.Passphrase, is SyncScreen.TurnOff -> show(SyncScreen.Page)
        }
    }

    /** Cancel (and Keep syncing): back to the page from any step; from Match it unlinks. Ignored
     *  while a link, Start syncing or Turn off runs. */
    fun onCancel() {
        if (screen.busy) return
        when (screen) {
            SyncScreen.Page, is SyncScreen.OwnerGate -> Unit
            is SyncScreen.Match, is SyncScreen.ReadingMatch -> leaveMatch()
            SyncScreen.Services, is SyncScreen.NextcloudAddress, is SyncScreen.NextcloudWaiting, is SyncScreen.WebDav,
            is SyncScreen.GoogleSignIn, is SyncScreen.Passphrase, is SyncScreen.TurnOff,
            -> show(SyncScreen.Page)
        }
    }

    /** Touch Back on the owner's lock (the phone): back to the page even with presses entered;
     *  ignored while a try is checked. */
    fun onEntryLeave() {
        val gate = screen as? SyncScreen.OwnerGate ?: return
        if (!gate.entry.checking) show(SyncScreen.Page)
    }

    /** A sheet swiped away (the phone): the services or Turn off sheet to the page, a Match
     *  row's sheet to Match. */
    fun onDismiss() {
        if (screen.busy) return
        when (val shown = screen) {
            SyncScreen.Services, is SyncScreen.TurnOff -> show(SyncScreen.Page)
            is SyncScreen.Match -> if (shown.sheet != null) moveTo(shown.copy(sheet = null))
            SyncScreen.Page, is SyncScreen.OwnerGate, is SyncScreen.NextcloudAddress, is SyncScreen.NextcloudWaiting, is SyncScreen.WebDav,
            is SyncScreen.GoogleSignIn, is SyncScreen.Passphrase, is SyncScreen.ReadingMatch,
            -> Unit
        }
    }

    /** Runs [action] once the owner's lock opens (at once while the owner has none). */
    private fun askOwner(action: SyncAction) {
        val owner = ownerId ?: return
        viewModelScope.launch {
            // Match just left: what Turn on does depends on the unlink having landed.
            leaving?.join()
            val opening = locks.openOwnerGate(owner)
            // Left meanwhile, or another press got there first.
            if (screen != SyncScreen.Page) return@launch
            when (opening) {
                is OwnerGateOpening.Free -> runOwnerAction(action)
                is OwnerGateOpening.Ask -> {
                    show(SyncScreen.OwnerGate(action, opening.kind))
                    entry.startTry(opening.kind, check = locks::unlockOwner) { runOwnerAction(action) }
                    opening.wait?.let(entry::waitFor)
                }
                OwnerGateOpening.Changed -> Unit
            }
        }
    }

    private fun runOwnerAction(action: SyncAction) {
        val status = sync.status.value
        when (action) {
            SyncAction.TurnOn if status.awaitingMatch -> {
                show(SyncScreen.ReadingMatch())
                step = viewModelScope.launch { readMatch(status.account) }
            }
            SyncAction.TurnOn -> show(SyncScreen.Services)
            SyncAction.SignInAgain -> {
                val account = status.account ?: return
                when (account.service) {
                    SyncService.NEXTCLOUD -> show(SyncScreen.NextcloudAddress(account.server, again = true))
                    SyncService.WEBDAV -> show(SyncScreen.WebDav(user = account.user, again = true))
                    // A build without Google's client can't sign in to Drive: back to the page.
                    SyncService.GOOGLE_DRIVE -> if (driveSignIn != null) startGoogle(again = true) else show(SyncScreen.Page)
                }
            }
            SyncAction.TurnOff -> show(SyncScreen.TurnOff())
        }
    }

    /** A sign-in [credential] from a service's step: Sign in again ([again]) replaces the link's
     *  when it reaches the linked folder, then asks for the round that tries it (no look first,
     *  which would make a folder elsewhere); else the folder's devices lead to the passphrase. A
     *  problem goes back to the step's screen ([problem]). */
    private suspend fun signedIn(credential: SyncCredential, again: Boolean, checking: SyncScreen, problem: (SetupProblem) -> SyncScreen) {
        moveTo(checking)
        if (again) {
            // A brief save Back mustn't split: a credential saved with no round asked for would
            // leave the old failure showing. A save that failed (NOT_SAVED) keeps the old one, so
            // no round is asked for, which would only bury NOT_SAVED under the old failure.
            val replaced = withContext(NonCancellable) {
                sync.replaceSignIn(credential).also { if (it && sync.status.value.failure != SyncFailure.NOT_SAVED) requestSync() }
            }
            currentCoroutineContext().ensureActive()
            moveTo(if (replaced) SyncScreen.Page else problem(SetupProblem.OTHER_ACCOUNT))
            return
        }
        when (val look = sync.look(credential)) {
            is FolderLook.Failed -> moveTo(problem(look.failure.setupProblem()))
            is FolderLook.Ready -> {
                val relinking = sync.status.value.linked
                this.credential = credential
                moveTo(SyncScreen.Passphrase(credential.account(), look.devices, joining = relinking || look.devices.isNotEmpty()))
            }
        }
    }

    private suspend fun readMatch(account: SyncAccount?) {
        when (val look = sync.matchPreview()) {
            is MatchLook.Ready -> moveTo(SyncScreen.Match(account, ProfileMatch.of(look.preview)))
            is MatchLook.Failed -> moveTo(SyncScreen.ReadingMatch(look.failure))
        }
    }

    /** Leaving Match unlinks (M8-D18): back on the page, which shows sync off. */
    private fun leaveMatch() {
        show(SyncScreen.Page)
        leaving = viewModelScope.launch { sync.unlink() }
    }

    private fun updateMatch(change: (ProfileMatch, MatchSheet?) -> ProfileMatch?) {
        val shown = screen as? SyncScreen.Match ?: return
        val next = change(shown.match, shown.sheet) ?: return
        moveTo(shown.copy(match = next, sheet = null))
    }

    /** The end of a flow: the page, and [toast]. */
    private fun finish(toast: SyncToast) {
        moveTo(SyncScreen.Page)
        _uiState.update { it.copy(toast = toast) }
    }

    private val screen: SyncScreen get() = _uiState.value.screen

    private fun onOwnersPage(): Boolean = screen == SyncScreen.Page && _uiState.value.isOwner

    /** Shows [next] from a user's action: the step under way stops. */
    private fun show(next: SyncScreen) {
        step?.cancel()
        step = null
        moveTo(next)
    }

    /** Shows [next], the step under way going on; lock input stops, and the sign-in waiting for
     *  the passphrase is dropped once it doesn't show. */
    private fun moveTo(next: SyncScreen) {
        entry.clear()
        if (next !is SyncScreen.Passphrase) credential = null
        _uiState.update { it.copy(screen = next) }
    }

    /** A link, Start syncing or Turn off runs: leaving it is ignored. */
    private val SyncScreen.busy: Boolean
        get() = when (this) {
            is SyncScreen.Passphrase -> linking
            is SyncScreen.Match -> starting
            is SyncScreen.TurnOff -> running
            SyncScreen.Page, is SyncScreen.OwnerGate, SyncScreen.Services, is SyncScreen.NextcloudAddress, is SyncScreen.NextcloudWaiting,
            is SyncScreen.WebDav, is SyncScreen.GoogleSignIn, is SyncScreen.ReadingMatch,
            -> false
        }

    /** A sign-in step of Sign in again rather than Turn on. */
    private val SyncScreen.signingInAgain: Boolean
        get() = when (this) {
            is SyncScreen.NextcloudAddress -> again
            is SyncScreen.NextcloudWaiting -> again
            is SyncScreen.WebDav -> again
            is SyncScreen.GoogleSignIn -> again
            SyncScreen.Page, is SyncScreen.OwnerGate, SyncScreen.Services, is SyncScreen.Passphrase, is SyncScreen.Match,
            is SyncScreen.ReadingMatch, is SyncScreen.TurnOff,
            -> false
        }

    private fun updateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _uiState.update { state ->
            val gate = state.screen as? SyncScreen.OwnerGate ?: return@update state
            state.copy(screen = gate.copy(entry = change(gate.entry)))
        }
    }
}

private fun SetupFailure.problem(): SetupProblem = when (this) {
    SetupFailure.NOT_AN_ADDRESS -> SetupProblem.NOT_AN_ADDRESS
    SetupFailure.NEEDS_HTTPS -> SetupProblem.NEEDS_HTTPS
    SetupFailure.NO_CONNECTION -> SetupProblem.NO_CONNECTION
    SetupFailure.NOT_NEXTCLOUD -> SetupProblem.NOT_NEXTCLOUD
    SetupFailure.SERVER_ERROR -> SetupProblem.SERVER_ERROR
    SetupFailure.DENIED -> SetupProblem.DENIED
}

/** A look's failure: only the service's kinds come from it. */
private fun SyncFailure.setupProblem(): SetupProblem = when (this) {
    SyncFailure.SIGN_IN_REJECTED -> SetupProblem.WRONG_SIGN_IN
    SyncFailure.FOLDER_MISSING -> SetupProblem.NO_FOLDER
    SyncFailure.NO_CONNECTION -> SetupProblem.NO_CONNECTION
    SyncFailure.STORAGE_FULL -> SetupProblem.STORAGE_FULL
    SyncFailure.SERVER_ERROR, SyncFailure.WRONG_PASSPHRASE, SyncFailure.NEWER_VERSION, SyncFailure.LINK_LOST,
    SyncFailure.TOO_LARGE, SyncFailure.NOT_SAVED,
    -> SetupProblem.SERVER_ERROR
}
