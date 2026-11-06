package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.sync.CredentialCheck
import com.arshad.ayn.core.sync.DriveSignInStart
import com.arshad.ayn.core.sync.FakeDriveSignIn
import com.arshad.ayn.core.sync.FakeSyncControl
import com.arshad.ayn.core.sync.FakeSyncSignIn
import com.arshad.ayn.core.sync.FolderLook
import com.arshad.ayn.core.sync.LinkResult
import com.arshad.ayn.core.sync.MatchChoices
import com.arshad.ayn.core.sync.MatchLook
import com.arshad.ayn.core.sync.MatchPreview
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SetupFailure
import com.arshad.ayn.core.sync.SignInEnd
import com.arshad.ayn.core.sync.SignInStart
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncDevice
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.core.sync.account
import com.arshad.ayn.core.sync.driveCredential
import com.arshad.ayn.core.sync.nextcloudCredential
import com.arshad.ayn.core.sync.webDavCredential
import com.arshad.ayn.feature.profiles.UnlockMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings › Sync (M8-D9, M8-D18) over the fake runner and sign-in: the owner's lock before Turn
 * on, Sign in again and Turn off; Google's and Nextcloud's sign-ins and another WebDAV server's
 * fields with their problems (Drive only with a Google sign-in); the passphrase, new and joining, wrong and failed; Match, its choices, leaving
 * it and resuming it; Sign in again as the same account only; Link again after a lost link;
 * turning off; Sync now.
 */
class SyncViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val profiles = FakeProfileStore(listOf(OWNER, MIRA))
    private val locks = FakeProfileLocks().apply { ownerId = OWNER.id }
    private val sync = FakeSyncControl()
    private val signIn = FakeSyncSignIn()
    private val drive = FakeDriveSignIn()
    private var syncRequests = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun someoneElseCantTurnSyncOnOrOff() = runTest(dispatcher) {
        val vm = openAs(MIRA)

        vm.onTurnOn()
        advanceUntilIdle()
        sync.status.value = LINKED
        vm.onTurnOff()
        vm.onSyncNow()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isOwner)
        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(0, syncRequests)
    }

    @Test
    fun aNextcloudSignInWaitsForTheGrantThenANewFolderTakesANewPassphrase() = runTest(dispatcher) {
        val vm = atNextcloudAddress()

        vm.onNextcloudContinue("  cloud.example.org ")
        advanceUntilIdle()
        assertEquals(listOf("cloud.example.org"), signIn.addresses)
        assertEquals(SyncScreen.NextcloudWaiting(LOGIN_URL, "cloud.example.org", "cloud.example.org"), vm.screen)
        signIn.grant.complete(SignInEnd.Granted(NEXTCLOUD))
        advanceUntilIdle()

        assertEquals(SyncScreen.Passphrase(NEXTCLOUD.account(), devices = emptyList(), joining = false), vm.screen)
        assertEquals(listOf(NEXTCLOUD.account()), sync.looked.map { it.account() })
    }

    @Test
    fun aNewPassphraseShorterThanEightLinksNothingAndIsWiped() = runTest(dispatcher) {
        val vm = atPassphrase()
        val short = "seven77".toCharArray()

        vm.onPassphrase(short)
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), sync.links)
        assertTrue(short.all { it == '\u0000' })
    }

    @Test
    fun aNewPassphraseOfEightOrMoreTurnsSyncOn() = runTest(dispatcher) {
        val vm = atPassphrase()

        vm.onPassphrase("blue river stone".toCharArray())
        advanceUntilIdle()

        assertEquals(listOf("blue river stone"), sync.links.map { it.second })
        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(SyncToast.TurnedOn(), vm.uiState.value.toast)
        assertTrue(vm.uiState.value.status.linked)
    }

    @Test
    fun aLockedOwnerIsAskedForTheirLockBeforeTurnOnCountingWrongTries() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        val vm = openAs(OWNER)

        vm.onTurnOn()
        advanceUntilIdle()
        assertEquals(SyncScreen.OwnerGate(SyncAction.TurnOn, LockKind.Sequence), vm.screen)
        enter(vm, WRONG_SEQUENCE)
        advanceUntilIdle()
        assertEquals(UnlockMessage.Wrong(triesBeforeWait = 4), (vm.screen as SyncScreen.OwnerGate).entry.message)
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        assertEquals(SyncScreen.Services, vm.screen)
        assertEquals(listOf(OWNER.id, OWNER.id), locks.tries)
    }

    @Test
    fun turnOffAsksALockedOwnersLockAndLeavingItGoesBackToThePage() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        sync.status.value = LINKED
        val vm = openAs(OWNER)
        vm.onTurnOff()
        advanceUntilIdle()
        assertEquals(SyncScreen.OwnerGate(SyncAction.TurnOff, LockKind.Sequence), vm.screen)

        vm.onEntryLeave()
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(0, sync.unlinks)
    }

    @Test
    fun swipingAwayTheServicesSheetGoesBackToThePage() = runTest(dispatcher) {
        val vm = atServices()

        vm.onDismiss()

        assertEquals(SyncScreen.Page, vm.screen)
    }

    @Test
    fun aRefusedNextcloudAddressSaysWhyAndKeepsWhatWasTyped() = runTest(dispatcher) {
        signIn.startResult = SignInStart.Refused(SetupFailure.NOT_NEXTCLOUD)
        val vm = atNextcloudAddress()

        vm.onNextcloudContinue("files.example.org")
        advanceUntilIdle()

        assertEquals(SyncScreen.NextcloudAddress("files.example.org", SetupProblem.NOT_NEXTCLOUD), vm.screen)
        assertEquals(0, signIn.awaits)
    }

    @Test
    fun aSignInThatRanOutOfTimeGoesBackToTheAddress() = runTest(dispatcher) {
        val vm = atNextcloudAddress()
        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()

        signIn.grant.complete(SignInEnd.Expired)
        advanceUntilIdle()

        assertEquals(SyncScreen.NextcloudAddress("cloud.example.org", SetupProblem.EXPIRED), vm.screen)
    }

    @Test
    fun aSignInWhoseAccountLookupFailedGoesBackToTheAddressSayingSo() = runTest(dispatcher) {
        val vm = atNextcloudAddress()
        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()

        signIn.grant.complete(SignInEnd.Refused(SetupFailure.SERVER_ERROR))
        advanceUntilIdle()

        assertEquals(SyncScreen.NextcloudAddress("cloud.example.org", SetupProblem.SERVER_ERROR), vm.screen)
    }

    @Test
    fun leavingTheWaitStopsItAndALaterGrantLinksNothing() = runTest(dispatcher) {
        val vm = atNextcloudAddress()
        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()

        vm.onBack()
        signIn.grant.complete(SignInEnd.Granted(NEXTCLOUD))
        advanceUntilIdle()

        assertEquals(SyncScreen.Services, vm.screen)
        assertEquals(emptyList<Any>(), sync.looked)
    }

    @Test
    fun changeAddressReturnsToTheAddressAsTyped() = runTest(dispatcher) {
        val vm = atNextcloudAddress()
        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()

        vm.onChangeAddress()

        assertEquals(SyncScreen.NextcloudAddress("cloud.example.org"), vm.screen)
    }

    @Test
    fun aWebDavServerRefusingTheSignInNamesItAndKeepsTheFields() = runTest(dispatcher) {
        sync.lookResult = FolderLook.Failed(SyncFailure.SIGN_IN_REJECTED)
        val vm = atWebDav()

        vm.onWebDavConnect(DAV_ADDRESS, "arshad", "wrong")
        advanceUntilIdle()

        assertEquals(SyncScreen.WebDav(DAV_ADDRESS, "arshad", SetupProblem.WRONG_SIGN_IN), vm.screen)
    }

    @Test
    fun aPlainHttpWebDavAddressIsRefusedBeforeAnythingIsAsked() = runTest(dispatcher) {
        val vm = atWebDav()

        vm.onWebDavConnect("http://dav.example.com/", "arshad", "secret")
        vm.onWebDavConnect(DAV_ADDRESS, "", "secret")
        advanceUntilIdle()

        assertEquals(SyncScreen.WebDav("http://dav.example.com/", "arshad", SetupProblem.NEEDS_HTTPS), vm.screen)
        assertEquals(emptyList<Any>(), sync.looked)
    }

    @Test
    fun leavingAWebDavCheckStopsItAndItsAnswerChangesNothing() = runTest(dispatcher) {
        sync.lookGate = CompletableDeferred()
        val vm = atWebDav()
        vm.onWebDavConnect(DAV_ADDRESS, "arshad", "secret")
        advanceUntilIdle()

        vm.onBack()
        sync.lookGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(SyncScreen.Services, vm.screen)
    }

    @Test
    fun aFolderWithOtherDevicesAsksForItsPassphraseAndAWrongOneSaysSo() = runTest(dispatcher) {
        sync.lookResult = FolderLook.Ready(listOf(PIXEL))
        sync.linkResult = LinkResult.WrongPassphrase
        val vm = atWebDav()
        vm.onWebDavConnect(DAV_ADDRESS, "arshad", "secret")
        advanceUntilIdle()
        val joining = SyncScreen.Passphrase(DAV.account(), listOf(PIXEL), joining = true)
        assertEquals(joining, vm.screen)

        vm.onPassphrase("short".toCharArray())
        advanceUntilIdle()

        assertEquals(joining.copy(wrong = true), vm.screen)
        assertEquals(listOf("short"), sync.links.map { it.second })
    }

    @Test
    fun aFailedLinkSaysWhyAndTheSameSignInTriesAgain() = runTest(dispatcher) {
        sync.linkResult = LinkResult.Failed(SyncFailure.NO_CONNECTION)
        val vm = atPassphrase()

        vm.onPassphrase(PASSPHRASE.toCharArray())
        advanceUntilIdle()
        assertEquals(SyncFailure.NO_CONNECTION, (vm.screen as SyncScreen.Passphrase).failure)
        sync.linkResult = LinkResult.Linked(joined = false)
        vm.onPassphrase(PASSPHRASE.toCharArray())
        advanceUntilIdle()

        assertEquals(listOf(NEXTCLOUD.account(), NEXTCLOUD.account()), sync.links.map { it.first.account() })
        assertEquals(SyncScreen.Page, vm.screen)
    }

    @Test
    fun backIsIgnoredWhileLinking() = runTest(dispatcher) {
        sync.linkGate = CompletableDeferred()
        val vm = atPassphrase()
        vm.onPassphrase(PASSPHRASE.toCharArray())
        advanceUntilIdle()

        vm.onBack()
        vm.onCancel()
        sync.linkGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(SyncToast.TurnedOn(), vm.uiState.value.toast)
    }

    @Test
    fun joiningShowsAynsMatchAndStartSyncingKeepsTheOwnersChoices() = runTest(dispatcher) {
        val vm = atMatch()
        val shown = vm.screen as SyncScreen.Match
        assertEquals(ProfileMatch.of(PREVIEW), shown.match)
        assertEquals(DAV.account(), shown.account)

        vm.onMatchRow("m1")
        assertEquals(MatchSheet.Here("m1"), (vm.screen as SyncScreen.Match).sheet)
        vm.onKeepApart()
        vm.onMatchRow("G")
        vm.onDontAdd()
        vm.onStartSyncing()
        advanceUntilIdle()

        assertEquals(listOf(MatchChoices(pairs = emptyMap(), apart = setOf("m1"), skipped = setOf("G"))), sync.started)
        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(SyncToast.TurnedOn(profiles = 3, devices = listOf("Pixel 7 Pro")), vm.uiState.value.toast)
    }

    @Test
    fun eachMatchSheetTakesOnlyItsOwnChoices() = runTest(dispatcher) {
        val vm = atMatch()
        vm.onMatchRow("G")
        vm.onDontAdd()
        vm.onMatchRow("G")

        vm.onKeepApart()
        assertEquals(MatchSheet.There("G"), (vm.screen as SyncScreen.Match).sheet)
        vm.onAddHere()
        vm.onMatchRow("m1")
        vm.onMatchWith("G")

        val shown = vm.screen as SyncScreen.Match
        assertEquals(null, shown.sheet)
        assertEquals("G", shown.match.with["m1"])
        assertEquals(listOf("M"), shown.match.added.map { it.syncId })
    }

    @Test
    fun backIsIgnoredWhileStartSyncingRuns() = runTest(dispatcher) {
        sync.startGate = CompletableDeferred()
        val vm = atMatch()
        vm.onStartSyncing()
        advanceUntilIdle()

        vm.onBack()
        vm.onCancel()
        sync.startGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, sync.unlinks)
        assertEquals(1, sync.started.size)
    }

    @Test
    fun turnOnJustAfterLeavingMatchWaitsForTheUnlinkAndStartsAfresh() = runTest(dispatcher) {
        val vm = atMatch()
        sync.unlinkGate = CompletableDeferred()
        vm.onBack()
        advanceUntilIdle()

        vm.onTurnOn()
        advanceUntilIdle()
        sync.unlinkGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(SyncScreen.Services, vm.screen)
        assertEquals(1, sync.previews)
    }

    @Test
    fun theOwnersRowOpensNoChoices() = runTest(dispatcher) {
        val vm = atMatch()

        vm.onMatchRow("a1")

        assertEquals(null, (vm.screen as SyncScreen.Match).sheet)
    }

    @Test
    fun leavingMatchUnlinks() = runTest(dispatcher) {
        val vm = atMatch()

        vm.onBack()
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(1, sync.unlinks)
        assertTrue(vm.uiState.value.off)
    }

    @Test
    fun profilesThatCouldntBeReadForMatchAreReadAgainOnTryAgain() = runTest(dispatcher) {
        sync.previewResult = MatchLook.Failed(SyncFailure.NO_CONNECTION)
        val vm = atJoinedPassphrase()
        vm.onPassphrase(PASSPHRASE.toCharArray())
        advanceUntilIdle()
        assertEquals(SyncScreen.ReadingMatch(SyncFailure.NO_CONNECTION), vm.screen)

        sync.previewResult = MatchLook.Ready(PREVIEW)
        vm.onReadMatchAgain()
        advanceUntilIdle()

        assertEquals(ProfileMatch.of(PREVIEW), (vm.screen as SyncScreen.Match).match)
    }

    @Test
    fun turnOnWhileAJoinAwaitsMatchGoesBackToMatch() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(awaitingMatch = true)
        sync.previewResult = MatchLook.Ready(PREVIEW)
        val vm = openAs(OWNER)
        assertTrue(vm.uiState.value.off)

        vm.onTurnOn()
        advanceUntilIdle()

        assertEquals(ProfileMatch.of(PREVIEW), (vm.screen as SyncScreen.Match).match)
    }

    @Test
    fun linkingAgainPastMatchSyncsAtOnceWithoutMatch() = runTest(dispatcher) {
        sync.joinAwaitsMatch = false
        val vm = atJoinedPassphrase()

        vm.onPassphrase(PASSPHRASE.toCharArray())
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(1, syncRequests)
        assertEquals(0, sync.previews)
    }

    @Test
    fun signInAgainStartsAtTheLinksServer() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(failure = SyncFailure.SIGN_IN_REJECTED)
        val vm = openAs(OWNER)

        vm.onSignInAgain()
        advanceUntilIdle()

        assertEquals(SyncScreen.NextcloudAddress("cloud.example.org", again = true), vm.screen)
    }

    @Test
    fun signInAgainToAnotherFolderSaysSoAndLooksAtNothing() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(failure = SyncFailure.SIGN_IN_REJECTED)
        sync.replaceAccepts = false
        val vm = openAs(OWNER)
        vm.onSignInAgain()
        advanceUntilIdle()

        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()
        signIn.grant.complete(SignInEnd.Granted(nextcloudCredential(user = "mira")))
        advanceUntilIdle()

        assertEquals(SyncScreen.NextcloudAddress("cloud.example.org", SetupProblem.OTHER_ACCOUNT, again = true), vm.screen)
        assertEquals(emptyList<Any>(), sync.looked)
    }

    @Test
    fun signInAgainAsTheSameAccountReplacesOnlyTheSignIn() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(failure = SyncFailure.SIGN_IN_REJECTED)
        val vm = openAs(OWNER)
        vm.onSignInAgain()
        advanceUntilIdle()

        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()
        signIn.grant.complete(SignInEnd.Granted(NEXTCLOUD))
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(listOf(NEXTCLOUD.account()), sync.signIns.map { it.account() })
        assertEquals(1, syncRequests)
        assertEquals(emptyList<Any>(), sync.links)
    }

    @Test
    fun backDuringSignInAgainsSaveStillAsksForTheRound() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(failure = SyncFailure.SIGN_IN_REJECTED)
        sync.replaceGate = CompletableDeferred()
        val vm = openAs(OWNER)
        vm.onSignInAgain()
        advanceUntilIdle()
        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()
        signIn.grant.complete(SignInEnd.Granted(NEXTCLOUD))
        advanceUntilIdle()

        vm.onBack()
        sync.replaceGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, syncRequests)
        assertEquals(SyncScreen.Page, vm.screen)
    }

    @Test
    fun aSignInAgainThatWasntSavedAsksForNoRound() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(failure = SyncFailure.SIGN_IN_REJECTED)
        sync.replaceSaves = false
        val vm = openAs(OWNER)
        vm.onSignInAgain()
        advanceUntilIdle()
        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()

        signIn.grant.complete(SignInEnd.Granted(NEXTCLOUD))
        advanceUntilIdle()

        assertEquals(0, syncRequests)
        assertEquals(SyncFailure.NOT_SAVED, vm.uiState.value.status.failure)
    }

    @Test
    fun linkAgainAfterALostLinkTakesTheFoldersPassphraseEvenWithNoOtherDevice() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(failure = SyncFailure.LINK_LOST)
        val vm = openAs(OWNER)

        vm.onLinkAgain()
        advanceUntilIdle()
        assertEquals(SyncScreen.Services, vm.screen)
        vm.onWebDav()
        vm.onWebDavConnect(DAV_ADDRESS, "arshad", "secret")
        advanceUntilIdle()

        assertEquals(SyncScreen.Passphrase(DAV.account(), devices = emptyList(), joining = true), vm.screen)
    }

    /** With no other device's files the link may start the folder, so even linking again needs 8. */
    @Test
    fun linkingAgainToAFolderWithNoOtherDeviceStillNeedsEightCharacters() = runTest(dispatcher) {
        sync.status.value = LINKED.copy(failure = SyncFailure.LINK_LOST)
        val vm = openAs(OWNER)
        vm.onLinkAgain()
        advanceUntilIdle()
        vm.onWebDav()
        vm.onWebDavConnect(DAV_ADDRESS, "arshad", "secret")
        advanceUntilIdle()

        vm.onPassphrase("seven77".toCharArray())
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), sync.links)
    }

    @Test
    fun turningOffUnlinksAfterTheSheetAndSaysWhenTheFilesStayed() = runTest(dispatcher) {
        sync.status.value = LINKED
        sync.unlinkRemoves = false
        val vm = openAs(OWNER)

        vm.onTurnOff()
        advanceUntilIdle()
        assertEquals(SyncScreen.TurnOff(), vm.screen)
        vm.onConfirmTurnOff()
        advanceUntilIdle()

        assertEquals(1, sync.unlinks)
        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(SyncToast.TurnedOff(filesLeft = true, service = SyncService.NEXTCLOUD), vm.uiState.value.toast)
    }

    @Test
    fun keepSyncingClosesTheSheet() = runTest(dispatcher) {
        sync.status.value = LINKED
        val vm = openAs(OWNER)
        vm.onTurnOff()
        advanceUntilIdle()

        vm.onCancel()
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(0, sync.unlinks)
    }

    @Test
    fun aGoogleSignInShowsTheCodeThenANewDriveTakesANewPassphrase() = runTest(dispatcher) {
        val vm = atGoogleCode()
        assertEquals(SyncScreen.GoogleSignIn(code = CODE), vm.screen)

        drive.grant.complete(SignInEnd.Granted(DRIVE))
        advanceUntilIdle()

        assertEquals(SyncScreen.Passphrase(DRIVE.account(), devices = emptyList(), joining = false), vm.screen)
        assertEquals(listOf(DRIVE.account()), sync.looked.map { it.account() })
    }

    @Test
    fun withoutAGoogleSignInDriveIsHiddenAndAsksNothing() = runTest(dispatcher) {
        val vm = openAs(OWNER, google = null)
        vm.onTurnOn()
        advanceUntilIdle()

        vm.onGoogleDrive()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.drive)
        assertEquals(SyncScreen.Services, vm.screen)
    }

    @Test
    fun aRefusalOnGooglesPageSaysSoAndTryAgainGetsANewCode() = runTest(dispatcher) {
        val vm = atGoogleCode()
        drive.grant.complete(SignInEnd.Refused(SetupFailure.DENIED))
        advanceUntilIdle()
        assertEquals(SyncScreen.GoogleSignIn(problem = SetupProblem.DENIED), vm.screen)

        drive.grant = CompletableDeferred()
        vm.onGoogleTryAgain()
        advanceUntilIdle()

        assertEquals(SyncScreen.GoogleSignIn(code = CODE), vm.screen)
        assertEquals(2, drive.starts)
    }

    @Test
    fun aGoogleCodeThatRanOutOfTimeSaysSo() = runTest(dispatcher) {
        val vm = atGoogleCode()

        drive.grant.complete(SignInEnd.Expired)
        advanceUntilIdle()

        assertEquals(SyncScreen.GoogleSignIn(problem = SetupProblem.EXPIRED), vm.screen)
    }

    @Test
    fun googleUnreachableForACodeSaysSoAndWaitsForNothing() = runTest(dispatcher) {
        drive.startResult = DriveSignInStart.Refused(SetupFailure.NO_CONNECTION)

        val vm = atGoogleCode()

        assertEquals(SyncScreen.GoogleSignIn(problem = SetupProblem.NO_CONNECTION), vm.screen)
        assertEquals(0, drive.awaits)
    }

    @Test
    fun backFromGooglesSignInGoesToTheServicesAndALateGrantLooksAtNothing() = runTest(dispatcher) {
        val vm = atGoogleCode()

        vm.onBack()
        drive.grant.complete(SignInEnd.Granted(DRIVE))
        advanceUntilIdle()

        assertEquals(SyncScreen.Services, vm.screen)
        assertEquals(emptyList<Any>(), sync.looked)
    }

    @Test
    fun signInAgainOnADriveLinkRunsGooglesSignInAndReplacesOnlyTheSignIn() = runTest(dispatcher) {
        val vm = atGoogleSignInAgain()
        assertEquals(SyncScreen.GoogleSignIn(code = CODE, again = true), vm.screen)

        drive.grant.complete(SignInEnd.Granted(DRIVE))
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(listOf(DRIVE.account()), sync.signIns.map { it.account() })
        assertEquals(1, syncRequests)
        assertEquals(emptyList<Any>(), sync.looked)
    }

    @Test
    fun signInAgainAsAnotherGoogleAccountSaysSoAndBackLeavesTheLinkAsItWas() = runTest(dispatcher) {
        sync.replaceAccepts = false
        val vm = atGoogleSignInAgain()
        drive.grant.complete(SignInEnd.Granted(driveCredential(email = "mira@example.org")))
        advanceUntilIdle()
        assertEquals(SyncScreen.GoogleSignIn(problem = SetupProblem.OTHER_ACCOUNT, again = true), vm.screen)

        vm.onBack()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(SyncFailure.SIGN_IN_REJECTED, vm.uiState.value.status.failure)
        assertEquals(0, syncRequests)
    }

    @Test
    fun leavingWhileGoogleIsAskedForACodeWaitsForNothing() = runTest(dispatcher) {
        drive.startGate = CompletableDeferred()
        val vm = atGoogleCode()
        assertEquals(SyncScreen.GoogleSignIn(), vm.screen)

        vm.onBack()
        drive.startGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(SyncScreen.Services, vm.screen)
        assertEquals(0, drive.awaits)
    }

    @Test
    fun backWhileTheDriveIsCheckedLeavesWithoutAPassphrase() = runTest(dispatcher) {
        sync.lookGate = CompletableDeferred()
        val vm = atGoogleCode()
        drive.grant.complete(SignInEnd.Granted(DRIVE))
        advanceUntilIdle()
        assertEquals(SyncScreen.GoogleSignIn(code = CODE, checking = true), vm.screen)

        vm.onBack()
        sync.lookGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(SyncScreen.Services, vm.screen)
    }

    @Test
    fun cancelDuringGooglesWaitDropsALateGrant() = runTest(dispatcher) {
        val vm = atGoogleCode()

        vm.onCancel()
        drive.grant.complete(SignInEnd.Granted(DRIVE))
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(emptyList<Any>(), sync.looked)
    }

    @Test
    fun tryAgainWhileTheCodeShowsAsksForNoNewCode() = runTest(dispatcher) {
        val vm = atGoogleCode()

        vm.onGoogleTryAgain()
        advanceUntilIdle()

        assertEquals(1, drive.starts)
        assertEquals(SyncScreen.GoogleSignIn(code = CODE), vm.screen)
    }

    @Test
    fun tryAgainAfterAnotherAccountStaysSignInAgainAndThenReplacesTheSignIn() = runTest(dispatcher) {
        sync.replaceAccepts = false
        val vm = atGoogleSignInAgain()
        drive.grant.complete(SignInEnd.Granted(driveCredential(email = "mira@example.org")))
        advanceUntilIdle()

        sync.replaceAccepts = true
        drive.grant = CompletableDeferred()
        vm.onGoogleTryAgain()
        advanceUntilIdle()
        assertEquals(SyncScreen.GoogleSignIn(code = CODE, again = true), vm.screen)
        drive.grant.complete(SignInEnd.Granted(DRIVE))
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
        assertEquals(listOf("mira@example.org", DRIVE.account().user), sync.signIns.map { it.account().user })
        assertEquals(emptyList<Any>(), sync.looked)
    }

    @Test
    fun signInAgainOnADriveLinkInABuildWithoutGoogleGoesBackToThePage() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        sync.status.value = LINKED_DRIVE.copy(failure = SyncFailure.SIGN_IN_REJECTED)
        val vm = openAs(OWNER, google = null)
        vm.onSignInAgain()
        advanceUntilIdle()

        enter(vm, SEQUENCE)
        advanceUntilIdle()

        assertEquals(SyncScreen.Page, vm.screen)
    }

    @Test
    fun syncNowAsksForASyncOnlyWhileLinkedAndIdle() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onSyncNow()
        sync.status.value = LINKED.copy(syncing = true)
        advanceUntilIdle()
        vm.onSyncNow()
        sync.status.value = LINKED
        advanceUntilIdle()
        vm.onSyncNow()

        assertEquals(1, syncRequests)
    }

    private val SyncViewModel.screen: SyncScreen get() = uiState.value.screen

    private fun TestScope.openAs(me: Profile, google: FakeDriveSignIn? = drive): SyncViewModel =
        SyncViewModel(sync, signIn, google, profiles, locks, requestSync = { syncRequests++ }, me.id, testScheduler.timeSource).also { advanceUntilIdle() }

    /** Google's code showing, the wait for the grant under way. */
    private fun TestScope.atGoogleCode(): SyncViewModel = atServices().also { vm ->
        vm.onGoogleDrive()
        advanceUntilIdle()
    }

    /** Sign in again on a Drive link Google refused: its code showing. */
    private fun TestScope.atGoogleSignInAgain(): SyncViewModel {
        sync.status.value = LINKED_DRIVE.copy(failure = SyncFailure.SIGN_IN_REJECTED)
        return openAs(OWNER).also { vm ->
            vm.onSignInAgain()
            advanceUntilIdle()
        }
    }

    private fun TestScope.atServices(): SyncViewModel = openAs(OWNER).also { vm ->
        vm.onTurnOn()
        advanceUntilIdle()
    }

    private fun TestScope.atNextcloudAddress(): SyncViewModel = atServices().also { it.onNextcloud() }

    private fun TestScope.atWebDav(): SyncViewModel = atServices().also { it.onWebDav() }

    /** A new Nextcloud folder's passphrase. */
    private fun TestScope.atPassphrase(): SyncViewModel = atNextcloudAddress().also { vm ->
        vm.onNextcloudContinue("cloud.example.org")
        advanceUntilIdle()
        signIn.grant.complete(SignInEnd.Granted(NEXTCLOUD))
        advanceUntilIdle()
    }

    /** A WebDAV folder holding [PIXEL]'s files; linking joins it. */
    private fun TestScope.atJoinedPassphrase(): SyncViewModel {
        sync.lookResult = FolderLook.Ready(listOf(PIXEL))
        sync.linkResult = LinkResult.Linked(joined = true)
        return atWebDav().also { vm ->
            vm.onWebDavConnect(DAV_ADDRESS, "arshad", "secret")
            advanceUntilIdle()
        }
    }

    private fun TestScope.atMatch(): SyncViewModel {
        sync.previewResult = MatchLook.Ready(PREVIEW)
        return atJoinedPassphrase().also { vm ->
            vm.onPassphrase(PASSPHRASE.toCharArray())
            advanceUntilIdle()
        }
    }

    private fun enter(vm: SyncViewModel, sequence: List<Press>) {
        sequence.forEach(vm::onPress)
        vm.onSubmitSequence()
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
        val WRONG_SEQUENCE = listOf(Press.Left, Press.Left, Press.Left, Press.Left, Press.Left, Press.Left)
        const val PASSPHRASE = "blue river stone"
        const val LOGIN_URL = "https://cloud.example.org/login/v2/flow/FLOW-TOKEN"
        const val DAV_ADDRESS = "https://dav.example.com/remote.php/dav/files/arshad/"
        val NEXTCLOUD = nextcloudCredential()
        val DAV = (webDavCredential(DAV_ADDRESS, "arshad", "secret") as CredentialCheck.Ready).credential
        val LINKED = SyncStatus(
            linked = true,
            account = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad"),
            deviceId = FakeSyncControl.THIS_DEVICE,
        )
        val PIXEL = SyncDevice("pixel-7-pro-1a2b3c4d", 1_000)
        val DRIVE = driveCredential()
        val LINKED_DRIVE = SyncStatus(linked = true, account = DRIVE.account(), deviceId = FakeSyncControl.THIS_DEVICE)

        /** [FakeDriveSignIn.started] as the screens show it. */
        val CODE = DriveCode("GQVQ-JKEC", "https://www.google.com/device", minutes = 30)

        fun profile(syncId: String, name: String, owner: Boolean = false, there: Boolean = false) =
            MatchProfile(syncId, name, "amber", owner, kids = false, historyCount = 3, devices = if (there) listOf(PIXEL) else emptyList())

        val PREVIEW = MatchPreview(
            local = listOf(profile("a1", "Arshad", owner = true), profile("m1", "Mira")),
            remote = listOf(profile("A", "Arshad", owner = true, there = true), profile("M", "Mira", there = true), profile("G", "Guests", there = true)),
            pairs = mapOf("a1" to "A", "m1" to "M"),
            fixed = mapOf("a1" to "A"),
        )
    }
}
