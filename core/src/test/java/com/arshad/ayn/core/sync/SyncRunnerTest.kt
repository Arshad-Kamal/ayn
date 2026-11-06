package com.arshad.ayn.core.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.backup.BUNDLE_VERSION
import com.arshad.ayn.core.backup.BackupBundle
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.decodeBackup
import com.arshad.ayn.core.backup.encodeBackup
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.MutableClock
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileRepository
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.SubscriptionRepository
import com.arshad.ayn.core.store.WatchRepository
import com.arshad.ayn.core.store.ensureOwner
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SyncRunner] for a TV, a phone and a tablet sharing one in-memory folder (M8-D2, M8-D17):
 * linking new and joining, convergence, each failure stated with this device's data untouched,
 * skipped and damaged files, the upload only on change with its `.prev.ayn`, one round at a time,
 * unlinking, a lost key and linking again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRunnerTest {
    @get:Rule
    val dirs = TemporaryFolder()

    private val scope = TestScope()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val clock = MutableClock(T0)
    private val folder = FakeFolder()
    private val tv by lazy { Device("tv", "SHIELD Android TV") }
    private val phone by lazy { Device("phone", "Pixel 7 Pro") }
    private val tablet by lazy { Device("tablet", "Pixel Tablet") }

    @After
    fun closeDatabases() {
        tv.database.close()
        phone.database.close()
        tablet.database.close()
    }

    @Test
    fun theFirstDeviceLinksANewFolderWithItsSnapshotInIt() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)

        assertEquals(LinkResult.Linked(joined = false), tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))

        val file = folder.files.keys.single()
        assertTrue(file, file.matches(Regex("shield-android-tv-[0-9a-f]{8}\\.ayn")))
        assertEquals(tv.data.snapshot().copy(exportedAtMillis = 0), tv.uploaded(file).copy(exportedAtMillis = 0))
        val status = tv.runner.status.value
        assertEquals(SyncStatus(linked = true, lastSyncedMillis = T0), status.copy(account = null, deviceId = null, folderBytes = null))
        assertEquals(SyncAccount(SyncService.WEBDAV, "cloud.example.org", "smoke"), status.account)
        assertEquals(file.removeSuffix(".ayn"), status.deviceId)
    }

    /** Without its file in the folder, a second device would start the folder under its own key. */
    @Test
    fun aNewFolderWhoseFirstUploadFailsLinksNothing() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        folder.failing[FakeFolder.Op.PUT] = StorageFailure.NO_CONNECTION

        assertEquals(LinkResult.Failed(SyncFailure.NO_CONNECTION), tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))

        assertForgotten(tv)
    }

    /** Leaving the link screen mid-round cancels it: the folder may be empty, so nothing holds. */
    @Test
    fun aNewFolderWhoseFirstRoundIsCancelledLinksNothing() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        folder.putGate = CompletableDeferred()

        val linking = launch { tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()) }
        advanceUntilIdle()
        linking.cancel()
        advanceUntilIdle()

        assertTrue(folder.files.isEmpty())
        assertForgotten(tv)
    }

    /** Another device started the folder between this one's look and its first round. */
    @Test
    fun aNewFolderStartedElsewhereMeanwhileIsAWrongPassphrase() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        val otherKey = deriveSyncKey("another passphrase".toCharArray(), ByteArray(SALT_BYTES) { 7 }, ITERATION_RANGE.first)
        val otherFile = sealEnvelope(otherKey, encodeBackup(tv.data.snapshot()))
        folder.afterList = { folder.files["other-00000000.ayn"] = otherFile }

        assertEquals(LinkResult.WrongPassphrase, tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))

        assertEquals(setOf("other-00000000.ayn"), folder.files.keys)
        assertForgotten(tv)
    }

    @Test
    fun linkingWipesThePassphrase() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        val passphrase = PASSPHRASE.toCharArray()

        tv.runner.link(CREDENTIAL, passphrase)

        assertTrue(passphrase.all { it == '\u0000' })
    }

    /** M8-D5: the joining phone pairs its owner with the TV's; after one more round each both
     *  devices hold both devices' data. */
    @Test
    fun aJoiningDeviceWithTheSamePassphraseConverges() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)

        assertEquals(LinkResult.Linked(joined = true), phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))
        phone.runner.startSyncing(MatchChoices())
        tv.runner.sync()

        assertEquals(phone.data.snapshot(), tv.data.snapshot())
        assertEquals(setOf(CHANNEL_A, CHANNEL_B), tv.data.snapshot().profiles.single().subscriptions.map { it.channelUrl }.toSet())
        assertEquals(2, folder.files.keys.count { !it.endsWith(".prev.ayn") })
    }

    @Test
    fun aWrongPassphraseLinksNothing() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)

        assertEquals(LinkResult.WrongPassphrase, phone.runner.link(CREDENTIAL, "not the passphrase".toCharArray()))

        assertNull(phone.secrets.key())
        assertNull(phone.state.link())
    }

    /** The launch notice's count: every sync ends one more, unlinked or linked, and linking and
     *  turning off keep it. */
    @Test
    fun everySyncEndingCountsAndOtherStepsKeepTheCount() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.runner.sync()
        assertEquals(SyncStatus(linked = false, checks = 1), tv.runner.status.value)

        tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        assertEquals(1, tv.runner.status.value.checks)
        tv.runner.sync()
        assertEquals(2, tv.runner.status.value.checks)
        tv.runner.unlink()

        assertEquals(SyncStatus(linked = false, checks = 2), tv.runner.status.value)
    }

    @Test
    fun aFolderOfFilesThisBuildCantReadIsANewerAyns() = scope.runTest {
        folder.files["future-00000000.ayn"] = "AYNS\u0002 from a newer Ayn".encodeToByteArray()

        assertEquals(LinkResult.Failed(SyncFailure.NEWER_VERSION), phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))
        assertNull(phone.state.link())
    }

    @Test
    fun aRevokedSignInAtTheListIsStatedAndChangesNothingHere() = scope.runTest {
        assertFailureLeavesThePhoneAsItWas(FakeFolder.Op.LIST, StorageFailure.SIGN_IN_REJECTED, SyncFailure.SIGN_IN_REJECTED)
    }

    @Test
    fun aMissingFolderIsStatedAndChangesNothingHere() = scope.runTest {
        assertFailureLeavesThePhoneAsItWas(FakeFolder.Op.LIST, StorageFailure.FOLDER_MISSING, SyncFailure.FOLDER_MISSING)
    }

    @Test
    fun aTimeoutDuringADownloadIsStatedAndChangesNothingHere() = scope.runTest {
        assertFailureLeavesThePhoneAsItWas(FakeFolder.Op.GET, StorageFailure.NO_CONNECTION, SyncFailure.NO_CONNECTION)
    }

    @Test
    fun aServerErrorDuringADownloadIsStatedAndChangesNothingHere() = scope.runTest {
        assertFailureLeavesThePhoneAsItWas(FakeFolder.Op.GET, StorageFailure.SERVER_ERROR, SyncFailure.SERVER_ERROR)
    }

    /** The apply waits for every download: the TV's file, read first, isn't merged either. */
    @Test
    fun aFailedSecondDownloadAppliesNotEvenTheFirst() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        tablet.withOwnerSubscribedTo(CHANNEL_C)
        tablet.linkPastMatch()
        tablet.runner.sync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkPastMatch()
        val before = phone.data.snapshot()
        folder.failingFiles[tablet.fileName()] = StorageFailure.NO_CONNECTION
        folder.gets.clear()

        phone.runner.sync()

        assertEquals(SyncFailure.NO_CONNECTION, phone.runner.status.value.failure)
        assertTrue(tv.fileName() in folder.gets)
        assertEquals(before.copy(exportedAtMillis = 0), phone.data.snapshot().copy(exportedAtMillis = 0))
    }

    /** The upload comes after the apply: what the round merged stays, and is uploaded next time. */
    @Test
    fun aFullDriveAtTheUploadIsStatedAndTheNextRoundUploads() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        tv.subscribe(CHANNEL_B)
        val uploaded = folder.files.getValue(tv.fileName()).copyOf()
        folder.failing[FakeFolder.Op.PUT] = StorageFailure.STORAGE_FULL

        tv.runner.sync()

        assertEquals(SyncFailure.STORAGE_FULL, tv.runner.status.value.failure)
        assertArrayEquals(uploaded, folder.files.getValue(tv.fileName()))
        folder.failing.clear()
        tv.runner.sync()
        assertNull(tv.runner.status.value.failure)
        assertEquals(setOf(CHANNEL_A, CHANNEL_B), tv.uploaded(tv.fileName()).profiles.single().subscriptions.map { it.channelUrl }.toSet())
    }

    /** M8-D11's "wrong passphrase after a change elsewhere": a file under another passphrase (its
     *  own salt) stops the round before the apply, so the TV's readable file isn't merged either. */
    @Test
    fun aFileUnderAnotherPassphraseStopsTheRoundBeforeTheApply() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkPastMatch()
        val before = phone.data.snapshot()
        val otherKey = deriveSyncKey("another passphrase".toCharArray(), ByteArray(SALT_BYTES) { 7 }, ITERATION_RANGE.first)
        folder.files["other-00000000.ayn"] = sealEnvelope(otherKey, encodeBackup(before))
        val puts = folder.puts.size

        phone.runner.sync()

        assertEquals(SyncFailure.WRONG_PASSPHRASE, phone.runner.status.value.failure)
        assertEquals(before.copy(exportedAtMillis = 0), phone.data.snapshot().copy(exportedAtMillis = 0))
        assertEquals(puts, folder.puts.size)
    }

    @Test
    fun aFileFromANewerAynsEnvelopeStopsTheRoundBeforeTheApply() = scope.runTest {
        assertNewerFileStopsThePhone { key, bundle -> sealEnvelope(key, encodeBackup(bundle)).also { it[VERSION_OFFSET] = 2 } }
    }

    @Test
    fun aSnapshotFromANewerAynStopsTheRoundBeforeTheApply() = scope.runTest {
        assertNewerFileStopsThePhone { key, bundle -> sealEnvelope(key, encodeBackup(bundle.copy(bundleVersion = BUNDLE_VERSION + 1))) }
    }

    /** A snapshot from before version 6 carries no sync ids; it is skipped before the merge, and
     *  not downloaded again until its etag changes. */
    @Test
    fun aSnapshotBelowVersionSixIsSkippedAndNotDownloadedAgain() = scope.runTest {
        assertOldSnapshotSkipped(downloads = 1)
    }

    @Test
    fun withoutEtagsTheModifiedTimeDecidesWhatIsDownloaded() = scope.runTest {
        folder.versions = FakeFolder.Versions.MODIFIED
        assertOldSnapshotSkipped(downloads = 1)
    }

    @Test
    fun aFileWithNeitherEtagNorModifiedTimeIsDownloadedEveryRound() = scope.runTest {
        folder.versions = FakeFolder.Versions.NONE
        assertOldSnapshotSkipped(downloads = 2)
    }

    /** M8-D2: a file that doesn't open as an envelope (cut short below its header) is read, and
     *  proves the passphrase, from its `.prev.ayn`. */
    @Test
    fun aFileCutShortBelowItsHeaderIsReadFromItsPreviousCopy() = scope.runTest {
        assertDamagedFileReadFromItsPreviousCopy { it.copyOf(10) }
    }

    /** Damaged, and no copy to fall back on: skipped, as the round's other files merge. */
    @Test
    fun aDamagedFileWithNoPreviousCopyIsSkipped() = scope.runTest {
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkAndSync()
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkPastMatch()
        tv.runner.sync()
        folder.files[tv.fileName()] = folder.files.getValue(tv.fileName()).let { it.copyOf(it.size - 1) }

        phone.runner.sync()

        assertNull(phone.runner.status.value.failure)
        assertEquals(setOf(CHANNEL_B), phone.data.snapshot().profiles.single().subscriptions.map { it.channelUrl }.toSet())
    }

    /** Its header still reads, with this folder's key parameters: damaged, not a changed passphrase. */
    @Test
    fun aFileCutShortPastItsHeaderIsReadFromItsPreviousCopy() = scope.runTest {
        assertDamagedFileReadFromItsPreviousCopy { it.copyOf(it.size - 1) }
    }

    @Test
    fun anUnchangedSnapshotIsntUploadedAgain() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        clock.nowMillis += 1_000

        tv.runner.sync()

        assertEquals(listOf(tv.fileName()), folder.puts)
        assertEquals(folder.files.values.sumOf { it.size.toLong() }, tv.runner.status.value.folderBytes)
    }

    @Test
    fun aChangedSnapshotKeepsThePreviousCopy() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        val first = folder.files.getValue(tv.fileName()).copyOf()

        tv.subscribe(CHANNEL_B)
        tv.runner.sync()

        assertArrayEquals(first, folder.files.getValue(prevName(tv.deviceId())))
        assertEquals(listOf(tv.fileName(), prevName(tv.deviceId()), tv.fileName()), folder.puts)
    }

    @Test
    fun anUnchangedSnapshotWhoseFileWentIsUploadedAgain() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        folder.files.remove(tv.fileName())

        tv.runner.sync()

        assertEquals(tv.data.snapshot().copy(exportedAtMillis = 0), tv.uploaded(tv.fileName()).copy(exportedAtMillis = 0))
    }

    /** M8-D17: a second request waits for the round under way, whose state it then reads. */
    @Test
    fun roundsRunOneAtATime() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        val gate = CompletableDeferred<Unit>().also { folder.listGate = it }
        val lists = folder.lists

        launch { tv.runner.sync() }
        launch { tv.runner.sync() }
        runCurrent()

        assertEquals(lists + 1, folder.lists)
        assertTrue(tv.runner.status.value.syncing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(lists + 2, folder.lists)
        assertFalse(tv.runner.status.value.syncing)
    }

    @Test
    fun unlinkingRemovesThisDevicesFilesAndForgetsTheLink() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        tv.subscribe(CHANNEL_B)
        tv.runner.sync()
        phone.withOwnerSubscribedTo(CHANNEL_C)
        phone.linkPastMatch()
        phone.runner.sync()

        assertTrue(tv.runner.unlink())

        assertEquals(listOf(phone.fileName()), folder.files.keys.toList())
        assertForgotten(tv)
    }

    /** The other devices keep merging the files left; this one is unlinked all the same. */
    @Test
    fun unlinkingWhenTheFilesCantBeRemovedStillForgetsTheLink() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        folder.failing[FakeFolder.Op.DELETE] = StorageFailure.NO_CONNECTION

        assertFalse(tv.runner.unlink())

        assertTrue(folder.files.isNotEmpty())
        assertForgotten(tv)
    }

    /** M8-D15: a key the Keystore can no longer open asks for linking again. */
    @Test
    fun aKeyThatCantBeOpenedIsALostLink() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        tv.box.lost = true

        tv.runner.sync()

        assertEquals(SyncFailure.LINK_LOST, tv.runner.status.value.failure)
    }

    /** Linking again keeps this device's id: its file is replaced, not left behind for the other
     *  devices to keep merging. */
    @Test
    fun linkingAgainAfterALostLinkKeepsThisDevicesFile() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        val file = tv.fileName()
        tv.box.lost = true
        tv.runner.sync()
        tv.box.lost = false

        assertEquals(LinkResult.Linked(joined = true), tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))
        tv.subscribe(CHANNEL_B)
        tv.runner.sync()

        assertEquals(file, tv.fileName())
        assertEquals(setOf(file), folder.files.keys)
        assertNull(tv.runner.status.value.failure)
    }

    /** A link again that can't be sealed leaves the lost link as it was, its id still kept. */
    @Test
    fun aFailedLinkAgainKeepsTheLostLinksId() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        val id = tv.deviceId()
        tv.box.lost = true
        tv.runner.sync()
        tv.box.lost = false
        tv.box.sealFails = true

        assertEquals(LinkResult.Failed(SyncFailure.NOT_SAVED), tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))

        assertEquals(id, tv.deviceId())
        assertEquals(SyncFailure.LINK_LOST, tv.runner.status.value.failure)
        tv.runner.sync()
        assertEquals(SyncFailure.LINK_LOST, tv.runner.status.value.failure)
    }

    /** M8-D18: a new Nextcloud has no Ayn folder until the first device links. */
    @Test
    fun aMissingFolderIsMadeWhenTheFirstDeviceLinks() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        folder.missing = true

        assertEquals(LinkResult.Linked(joined = false), tv.runner.link(CREDENTIAL, PASSPHRASE.toCharArray()))

        assertFalse(folder.missing)
        assertEquals(listOf(tv.fileName()), folder.files.keys.toList())
    }

    /** The passphrase screen names the devices already there, before any passphrase. */
    @Test
    fun aLookNamesTheOtherDevicesAndChangesNothingHere() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()

        val look = phone.runner.look(CREDENTIAL) as FolderLook.Ready

        assertEquals(listOf(tv.deviceId()), look.devices.map { it.id })
        assertEquals("Shield Android TV", look.devices.single().name)
        assertNull(phone.state.link())
    }

    @Test
    fun aLookAtAMissingFolderMakesItAndFindsNoDevices() = scope.runTest {
        folder.missing = true

        assertEquals(FolderLook.Ready(emptyList()), phone.runner.look(CREDENTIAL))
        assertFalse(folder.missing)
    }

    @Test
    fun aLookWithARejectedSignInSaysSo() = scope.runTest {
        folder.failing[FakeFolder.Op.LIST] = StorageFailure.SIGN_IN_REJECTED

        assertEquals(FolderLook.Failed(SyncFailure.SIGN_IN_REJECTED), phone.runner.look(CREDENTIAL))
    }

    /** M8-D18: a trigger before the owner confirms Match neither pairs, merges nor uploads. */
    @Test
    fun aJoiningDeviceSyncsNothingUntilMatchIsConfirmed() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        val before = phone.data.snapshot()

        phone.runner.sync()

        assertTrue(phone.runner.status.value.awaitingMatch)
        assertEquals(before.copy(exportedAtMillis = 0), phone.data.snapshot().copy(exportedAtMillis = 0))
        assertEquals(listOf(tv.fileName()), folder.files.keys.filterNot { it.endsWith(".prev.ayn") })
    }

    @Test
    fun matchProposesOwnerWithOwnerAndTheSameNameAndListsWhoHoldsEach() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        val tvMira = tv.addProfile("Mira")
        tv.addProfile("Guests")
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        val phoneMira = phone.addProfile("mira")
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())

        val preview = (phone.runner.matchPreview() as MatchLook.Ready).preview

        val tvIds = tv.syncIds()
        val phoneIds = phone.syncIds()
        assertEquals(mapOf(phoneIds.getValue(OWNER) to tvIds.getValue(OWNER), phoneIds.getValue(phoneMira) to tvIds.getValue(tvMira)), preview.pairs)
        assertEquals(setOf("Owner", "Mira", "Guests"), preview.remote.map { it.name }.toSet())
        assertTrue(preview.remote.all { profile -> profile.devices.map { it.id } == listOf(tv.deviceId()) })
        assertEquals(setOf("Owner", "mira"), preview.local.map { it.name }.toSet())
        assertTrue(phone.runner.status.value.awaitingMatch)
    }

    /** Q19: Guests isn't added here, then or at a later round; Mira kept apart syncs as a new
     *  profile beside the TV's. */
    @Test
    fun startSyncingAppliesKeepApartAndTheSkipListHoldsAtLaterRounds() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.addProfile("Mira")
        val guests = tv.addProfile("Guests")
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        val phoneMira = phone.addProfile("Mira")
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        val choices = MatchChoices(apart = setOf(phone.syncIds().getValue(phoneMira)), skipped = setOf(tv.syncIds().getValue(guests)))

        phone.runner.startSyncing(choices)
        tv.runner.sync()
        tv.subscribe(CHANNEL_C)
        tv.runner.sync()
        phone.runner.sync()

        assertNull(phone.runner.status.value.failure)
        assertFalse(phone.runner.status.value.awaitingMatch)
        val phoneNames = phone.data.snapshot().profiles.map { it.name }
        assertTrue(CHANNEL_C in phone.data.snapshot().profiles.single { it.owner }.subscriptions.map { it.channelUrl })
        assertEquals(3, phoneNames.size)
        assertFalse("Guests" in phoneNames)
        assertEquals(4, tv.data.snapshot().profiles.size)
    }

    @Test
    fun startSyncingDoesNothingOnceMatchIsPast() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        val lists = folder.lists

        tv.runner.startSyncing(MatchChoices(skipped = setOf("anything")))

        assertEquals(lists, folder.lists)
        assertEquals(MatchChoices(), tv.state.link()?.choices)
    }

    @Test
    fun aRoundRecordsTheOtherDevicesAndTheFolderSize() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkAndSync()
        val puts = folder.puts.size

        tv.runner.sync()

        val status = tv.runner.status.value
        assertEquals(listOf(phone.deviceId()), status.devices.map { it.id })
        assertEquals(listOf(prevName(tv.deviceId()), tv.fileName()), folder.puts.drop(puts))
        assertEquals(folder.files.values.sumOf { it.size.toLong() }, status.folderBytes)
    }

    /** The Shield read "0 B in the Ayn folder" right after starting the folder, whose round uploads its file. */
    @Test
    fun startingAFolderCountsItsOwnUploadInTheFolderSize() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)

        tv.linkPastMatch()

        assertEquals(listOf(tv.fileName()), folder.files.keys.toList())
        assertEquals(folder.files.getValue(tv.fileName()).size.toLong(), tv.runner.status.value.folderBytes)
    }

    /** M8-D18: Sign in again replaces the credential and keeps the key; the next round uses it. */
    @Test
    fun signingInAgainAfterARejectedSignInKeepsTheKeyAndSyncs() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        val key = checkNotNull(tv.secrets.key())
        folder.failing[FakeFolder.Op.LIST] = StorageFailure.SIGN_IN_REJECTED
        tv.runner.sync()
        folder.failing.clear()
        val again = SyncCredential.WebDav(CREDENTIAL.folder, "smoke", "NEW-APP-PASSWORD", nextcloud = true)

        val lists = folder.lists
        tv.runner.replaceSignIn(again)
        assertEquals(lists, folder.lists)
        tv.runner.sync()

        assertNull(tv.runner.status.value.failure)
        assertEquals(SyncService.NEXTCLOUD, tv.runner.status.value.account?.service)
        assertEquals("NEW-APP-PASSWORD", (decodeCredential(checkNotNull(tv.secrets.credential())) as SyncCredential.WebDav).password)
        assertArrayEquals(key.keyBytes, checkNotNull(tv.secrets.key()).keyBytes)
    }

    /** A link lost before Match was confirmed asks for Match again, and syncs nothing meanwhile. */
    @Test
    fun linkingAgainBeforeMatchStillAwaitsIt() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        val puts = folder.puts.size

        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        phone.runner.sync()

        assertTrue(phone.runner.status.value.awaitingMatch)
        assertEquals(puts, folder.puts.size)
    }

    /** Another account is another folder: its Match starts afresh. */
    @Test
    fun linkingAgainToAnotherAccountStartsMatchAfresh() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        phone.runner.startSyncing(MatchChoices(skipped = setOf("guests")))
        val id = phone.deviceId()

        phone.runner.link(SyncCredential.WebDav(CREDENTIAL.folder, "another", "APP-PASSWORD"), PASSPHRASE.toCharArray())

        assertTrue(phone.runner.status.value.awaitingMatch)
        assertEquals(MatchChoices(), phone.state.link()?.choices)
        assertEquals(id, phone.deviceId())
    }

    /** One past Match, lost and linked again to the same account, keeps its choices. */
    @Test
    fun linkingAgainAfterALostLinkKeepsItsMatch() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        phone.runner.startSyncing(MatchChoices(skipped = setOf("guests")))
        phone.box.lost = true
        phone.runner.sync()
        phone.box.lost = false

        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())

        assertFalse(phone.runner.status.value.awaitingMatch)
        assertEquals(setOf("guests"), phone.state.link()?.choices?.skipped)
    }

    @Test
    fun matchCountsHistoryOnceAcrossDevicesAndNamesEveryHolder() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.watch(V1)
        val mira = tv.addProfile("Mira")
        tv.linkAndSync()
        tablet.withOwnerSubscribedTo(CHANNEL_C)
        tablet.watch(V2)
        tablet.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        val before = phone.data.snapshot()

        val preview = (phone.runner.matchPreview() as MatchLook.Ready).preview

        val owner = preview.remote.single { it.owner }
        assertEquals(2, owner.historyCount)
        assertEquals(setOf(tv.deviceId(), tablet.deviceId()), owner.devices.map { it.id }.toSet())
        assertEquals(setOf(tv.deviceId(), tablet.deviceId()), preview.remote.single { it.syncId == tv.syncIds().getValue(mira) }.devices.map { it.id }.toSet())
        assertEquals(before.copy(exportedAtMillis = 0), phone.data.snapshot().copy(exportedAtMillis = 0))
    }

    @Test
    fun matchWithAKeyThatCantBeOpenedIsALostLink() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        phone.box.lost = true

        assertEquals(MatchLook.Failed(SyncFailure.LINK_LOST), phone.runner.matchPreview())
    }

    /** Sign in again while Match is still open replaces the credential and still waits. */
    @Test
    fun signingInAgainWhileAwaitingMatchSyncsNothing() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.runner.link(CREDENTIAL, PASSPHRASE.toCharArray())
        val puts = folder.puts.size

        phone.runner.replaceSignIn(SyncCredential.WebDav(CREDENTIAL.folder, "smoke", "NEW-APP-PASSWORD"))

        assertTrue(phone.runner.status.value.awaitingMatch)
        assertEquals(puts, folder.puts.size)
        assertEquals("NEW-APP-PASSWORD", (decodeCredential(checkNotNull(phone.secrets.credential())) as SyncCredential.WebDav).password)
    }

    @Test
    fun aSignInAgainThatCantBeSealedKeepsTheOldOneAndSaysSo() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        tv.box.sealFails = true

        tv.runner.replaceSignIn(SyncCredential.WebDav(CREDENTIAL.folder, "smoke", "NEW-APP-PASSWORD"))

        assertEquals(SyncFailure.NOT_SAVED, tv.runner.status.value.failure)
        assertEquals("APP-PASSWORD", (decodeCredential(checkNotNull(tv.secrets.credential())) as SyncCredential.WebDav).password)
    }

    /** Another folder holds no other device's files: Sign in again refuses it. */
    @Test
    fun signingInAgainToAnotherFolderChangesNothing() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        val elsewhere = SyncCredential.WebDav("https://cloud.example.org/remote.php/dav/files/smoke/Other/Ayn/", "smoke", "NEW-APP-PASSWORD")

        assertFalse(tv.runner.replaceSignIn(elsewhere))

        assertEquals("APP-PASSWORD", (decodeCredential(checkNotNull(tv.secrets.credential())) as SyncCredential.WebDav).password)
    }

    /** A link cancelled while a round holds the runner still wipes the passphrase it was given. */
    @Test
    fun aLinkCancelledWhileWaitingForARoundWipesThePassphrase() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        folder.listGate = CompletableDeferred()
        launch { tv.runner.sync() }
        runCurrent()
        val passphrase = PASSPHRASE.toCharArray()

        val linking = launch { tv.runner.link(CREDENTIAL, passphrase) }
        runCurrent()
        linking.cancel()
        runCurrent()

        assertTrue(passphrase.all { it == '\u0000' })
        folder.listGate?.complete(Unit)
    }

    @Test
    fun aLookFromALinkedDeviceLeavesItselfOut() = scope.runTest {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()

        assertEquals(FolderLook.Ready(emptyList()), tv.runner.look(CREDENTIAL))
    }

    @Test
    fun aServiceWithoutSizesGivesNoFolderSizeAndModifiedTimesDateTheDevices() = scope.runTest {
        folder.sizes = false
        folder.versions = FakeFolder.Versions.MODIFIED
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkAndSync()
        val phoneFile = folder.files.getValue(phone.fileName())

        tv.runner.sync()

        assertNull(tv.runner.status.value.folderBytes)
        assertEquals(phoneFile.contentHashCode().toLong(), tv.runner.status.value.devices.single().syncedAtMillis)
    }

    private suspend fun assertFailureLeavesThePhoneAsItWas(op: FakeFolder.Op, failure: StorageFailure, stated: SyncFailure) {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkPastMatch()
        val before = phone.data.snapshot()
        folder.failing[op] = failure

        phone.runner.sync()

        assertEquals(stated, phone.runner.status.value.failure)
        assertFalse(phone.runner.status.value.syncing)
        assertEquals(before.copy(exportedAtMillis = 0), phone.data.snapshot().copy(exportedAtMillis = 0))
        assertEquals(stated.name, phone.state.link()?.failure)
    }

    private suspend fun assertNewerFileStopsThePhone(newerFile: (SyncKey, BackupBundle) -> ByteArray) {
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkAndSync()
        val before = phone.data.snapshot()
        folder.files["future-00000000.ayn"] = newerFile(checkNotNull(phone.secrets.key()), before.copy(profiles = emptyList()))

        phone.runner.sync()

        assertEquals(SyncFailure.NEWER_VERSION, phone.runner.status.value.failure)
        assertEquals(before.copy(exportedAtMillis = 0), phone.data.snapshot().copy(exportedAtMillis = 0))
    }

    private suspend fun assertOldSnapshotSkipped(downloads: Int) {
        phone.withOwnerSubscribedTo(CHANNEL_B)
        phone.linkAndSync()
        val before = phone.data.snapshot()
        val oldAyn = before.copy(bundleVersion = 5, removals = emptyList(), profiles = before.profiles.map { it.copy(syncId = null, name = "Old") })
        folder.files["old-00000000.ayn"] = sealEnvelope(checkNotNull(phone.secrets.key()), encodeBackup(oldAyn))

        phone.runner.sync()
        phone.runner.sync()

        assertNull(phone.runner.status.value.failure)
        assertEquals(before.copy(exportedAtMillis = 0), phone.data.snapshot().copy(exportedAtMillis = 0))
        assertEquals(downloads, folder.gets.count { it == "old-00000000.ayn" })
    }

    private suspend fun assertDamagedFileReadFromItsPreviousCopy(damage: (ByteArray) -> ByteArray) {
        tv.withOwnerSubscribedTo(CHANNEL_A)
        tv.linkAndSync()
        tv.subscribe(CHANNEL_B)
        tv.runner.sync()
        folder.files[tv.fileName()] = damage(folder.files.getValue(tv.fileName()))
        phone.withOwnerSubscribedTo(CHANNEL_C)
        assertEquals(LinkResult.Linked(joined = true), phone.linkPastMatch())

        phone.runner.sync()

        assertNull(phone.runner.status.value.failure)
        assertEquals(setOf(CHANNEL_A, CHANNEL_C), phone.data.snapshot().profiles.single().subscriptions.map { it.channelUrl }.toSet())
    }

    private suspend fun assertForgotten(device: Device) {
        assertNull(device.state.link())
        assertNull(device.state.lastUpload())
        assertNull(device.secrets.key())
        assertNull(device.secrets.credential())
        assertEquals(SyncStatus(linked = false), device.runner.status.value.copy(checks = 0))
    }

    /** A device: its own database, preferences and files, on the shared clock and folder. */
    private inner class Device(name: String, model: String) {
        private val context = DeviceContext(RuntimeEnvironment.getApplication(), name, dirs.newFolder(name))
        val database: KeDatabase = Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(dispatcher)
            .build()
        val box = FakeBox()
        val secrets = SyncSecrets(context, box, dispatcher)
        val state = SyncState(context, dispatcher)
        val data = SyncDatabase(database, clock)
        private val session = ProfileSession()
        private val subscriptions = SubscriptionRepository(database, session, clock)
        val runner = SyncRunner(
            secrets, state, data, storageFor = { folder }, deviceName = model, openProfileId = { null }, beforeSnapshot = {},
            clock = clock, compute = dispatcher,
        )

        suspend fun withOwnerSubscribedTo(channelUrl: String) {
            session.select(ProfileRepository(database, clock).ensureOwner().id)
            subscribe(channelUrl)
        }

        suspend fun subscribe(channelUrl: String) {
            subscriptions.subscribe(ChannelItem(name = "Channel", url = channelUrl, avatarUrl = null, subscriberCount = -1, description = null, verified = false))
        }

        suspend fun linkAndSync() {
            linkPastMatch()
            runner.sync()
        }

        /** Links, and for a join stands in for the owner confirming Match with no choices, without
         *  the round [SyncRunner.startSyncing] would run. */
        suspend fun linkPastMatch(): LinkResult = runner.link(CREDENTIAL, PASSPHRASE.toCharArray()).also {
            state.link()?.let { link -> state.save(link.copy(awaitingMatch = false)) }
        }

        /** The open profile watched [videoId] a minute in. */
        suspend fun watch(videoId: VideoId) {
            WatchRepository(database, session, clock).recordPosition(PositionSave(session.currentId(), videoId, positionMs = 60_000, durationMs = 600_000, ended = false))
        }

        suspend fun addProfile(name: String): Long =
            (ProfileRepository(database, clock).create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId

        /** Profile id → sync id; the owner under [OWNER]. */
        suspend fun syncIds(): Map<Long, String> =
            database.profileDao().all().associate { (if (it.ownerMark != null) OWNER else it.id) to it.syncId }

        suspend fun deviceId(): String = checkNotNull(state.link()).deviceId

        suspend fun fileName(): String = snapshotName(deviceId())

        suspend fun uploaded(file: String): BackupBundle {
            val read = openEnvelope(checkNotNull(secrets.key()), folder.files.getValue(file)) as EnvelopeRead.Opened
            return (decodeBackup(read.plaintext) as BackupRead.Valid).backup.bundle
        }
    }
}

/** A device's own preferences (prefixed) and no-backup folder over the shared test application. */
private class DeviceContext(base: Context, private val prefix: String, private val noBackup: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$prefix-$name", mode)

    override fun getNoBackupFilesDir(): File = noBackup
}

/** One cloud folder in memory: a file's version is its content's hash, as an etag or as a
 *  modified time ([versions]); [failing] makes an operation fail as the service would, and
 *  [failingFiles] a named file's download. Records the names read and written. */
private class FakeFolder : SyncStorage {
    enum class Op { LIST, GET, PUT, DELETE }

    enum class Versions { ETAG, MODIFIED, NONE }

    val files = LinkedHashMap<String, ByteArray>()
    val failing = mutableMapOf<Op, StorageFailure>()
    val failingFiles = mutableMapOf<String, StorageFailure>()
    var versions = Versions.ETAG
    var listGate: CompletableDeferred<Unit>? = null
    var putGate: CompletableDeferred<Unit>? = null

    /** Runs once, after the next list is read. */
    var afterList: (() -> Unit)? = null
    var lists = 0
    val gets = mutableListOf<String>()
    val puts = mutableListOf<String>()

    var sizes = true

    /** No folder yet: the list says so until [createFolder]. */
    var missing = false

    override suspend fun list(): List<RemoteFile> {
        fail(Op.LIST)
        if (missing) throw SyncStorageException(StorageFailure.FOLDER_MISSING)
        lists++
        listGate?.await()
        val listed = files.map { (name, bytes) ->
            val hash = bytes.contentHashCode()
            RemoteFile(
                name,
                etag = "\"$hash\"".takeIf { versions == Versions.ETAG },
                modifiedMillis = hash.toLong().takeIf { versions == Versions.MODIFIED },
                sizeBytes = bytes.size.toLong().takeIf { sizes },
            )
        }
        afterList?.invoke()
        afterList = null
        return listed
    }

    override suspend fun get(name: String): ByteArray? {
        fail(Op.GET)
        failingFiles[name]?.let { throw SyncStorageException(it) }
        gets += name
        return files[name]?.copyOf()
    }

    override suspend fun put(name: String, content: ByteArray) {
        fail(Op.PUT)
        putGate?.await()
        puts += name
        files[name] = content.copyOf()
    }

    override suspend fun delete(name: String) {
        fail(Op.DELETE)
        files.remove(name)
    }

    override suspend fun createFolder() {
        missing = false
    }

    private fun fail(op: Op) {
        failing[op]?.let { throw SyncStorageException(it) }
    }
}

private val CREDENTIAL = SyncCredential.WebDav("https://cloud.example.org/remote.php/dav/files/smoke/Ayn/", "smoke", "APP-PASSWORD")
private const val PASSPHRASE = "correct horse battery"

/** [Device.syncIds]' key for the owner. */
private const val OWNER = -1L
private const val T0 = 1_700_000_000_000L
private const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
private const val CHANNEL_C = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"
private val V1 = VideoId("v1aaaaaaaaa")
private val V2 = VideoId("v2bbbbbbbbb")
