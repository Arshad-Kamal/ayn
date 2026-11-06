package com.arshad.ayn.core.sync

import android.util.Log
import androidx.sqlite.SQLiteException
import com.arshad.ayn.core.access.boundaryJson
import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.backup.BackupBundle
import com.arshad.ayn.core.backup.BackupProfile
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.MAX_BACKUP_BYTES
import com.arshad.ayn.core.backup.SYNC_IDS_VERSION
import com.arshad.ayn.core.backup.decodeBackup
import com.arshad.ayn.core.backup.encodeBackup
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why the last sync failed (M8-D11). One before the round's apply changes nothing here; one at
 *  the upload keeps what the round merged, which the next round uploads. The next trigger tries
 *  again. */
enum class SyncFailure {
    /** The service refused the sign-in (revoked, or a changed password): sign in again. */
    SIGN_IN_REJECTED,
    STORAGE_FULL,
    NO_CONNECTION,
    FOLDER_MISSING,
    SERVER_ERROR,

    /** Another device's file is sealed under another passphrase: the folder's passphrase changed,
     *  or two devices started this folder at once (turn sync off on one and join). */
    WRONG_PASSPHRASE,

    /** Another device runs a newer Ayn: update this one. */
    NEWER_VERSION,

    /** This device's key or sign-in can no longer be read (Android dropped the Keystore key): link
     *  again. */
    LINK_LOST,

    /** This device's snapshot is larger than any device reads. */
    TOO_LARGE,

    /** This device's database or link couldn't be written. */
    NOT_SAVED,
}

/** What Settings › Sync and the launch notice show (M8-D11, M8-D18). */
data class SyncStatus(
    val linked: Boolean,
    val syncing: Boolean = false,
    val lastSyncedMillis: Long? = null,
    /** The last round's failure; `null` once a round works. */
    val failure: SyncFailure? = null,
    val account: SyncAccount? = null,
    /** This device's id in the folder (its files' names). */
    val deviceId: String? = null,
    /** The other devices in the folder at the last round. */
    val devices: List<SyncDevice> = emptyList(),
    val folderBytes: Long? = null,
    /** Linked by joining, waiting for the owner's Match choices ([SyncRunner.startSyncing]). */
    val awaitingMatch: Boolean = false,
    /** How many [SyncRunner.sync] calls have ended in this process: a launch's notice waits for
     *  one past the count it started at (M8-D11). */
    val checks: Int = 0,
)

sealed interface LinkResult {
    /** [joined]: the folder already held another device's files, whose passphrase this one is. */
    data class Linked(val joined: Boolean) : LinkResult

    /** The folder's files don't open with this passphrase; nothing was saved. */
    data object WrongPassphrase : LinkResult

    data class Failed(val failure: SyncFailure) : LinkResult
}

/**
 * Cloud sync on this device (M8-D2, M8-D16, M8-D17): one round at a time, each a list, the
 * download of every other device's file that changed since it was last merged, one apply of them
 * all, then this device's upload when its snapshot changed. Nothing is applied until every
 * download opened, so a failure before the apply changes nothing here; one after it leaves the
 * merged data, which the next round uploads. [beforeSnapshot] runs first in each round (the
 * player saves its place, M5-D3). Work on [compute]: sealing, opening, encoding and decoding.
 */
class SyncRunner internal constructor(
    private val secrets: SyncSecrets,
    private val state: SyncState,
    private val data: SyncDatabase,
    private val storageFor: (SyncCredential) -> SyncStorage?,
    /** Names this device's files, so the join screen can name the devices (M8-D17). */
    private val deviceName: String,
    private val openProfileId: () -> Long?,
    private val beforeSnapshot: suspend () -> Unit,
    private val clock: Clock = Clock.System,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val random: SecureRandom = SecureRandom(),
) : SyncControl {
    private val rounds = Mutex()
    private val current = MutableStateFlow(SyncStatus(linked = false))

    /** [SyncStatus.checks]; written under [rounds]. */
    private var checks = 0

    /** Unlinked until the first round reads the link. */
    override val status: StateFlow<SyncStatus> = current.asStateFlow()

    /** Whether this device is linked to a folder: a restore then stamps its records (M8-D14). */
    suspend fun isLinked(): Boolean = state.link() != null

    /** One round; nothing while unlinked or awaiting Match. A failure lands in [status] and in
     *  the link, kept for the next launch's notice. */
    suspend fun sync() {
        rounds.withLock {
            val link = state.link()
            if (link == null) show(SyncStatus(linked = false)) else roundUnlessMatching(link)
            checks++
            show(current.value)
        }
    }

    private fun show(status: SyncStatus) {
        current.value = status.copy(checks = checks)
    }

    /** [credential]'s folder before the passphrase (M8-D18): the other devices' files in it, the
     *  folder made when it isn't there yet. Changes nothing on this device. */
    override suspend fun look(credential: SyncCredential): FolderLook {
        val storage = storageFor(credential) ?: return FolderLook.Failed(SyncFailure.FOLDER_MISSING)
        return try {
            FolderLook.Ready(storage.listOrMake().othersThan(state.link()?.deviceId).devices())
        } catch (e: SyncStorageException) {
            FolderLook.Failed(e.failure.asSyncFailure())
        }
    }

    /** A joining device's Match screen (M8-D18): the folder's profiles beside this device's, with
     *  the pairs Ayn proposes. Changes nothing here. */
    override suspend fun matchPreview(): MatchLook = rounds.withLock {
        val link = state.link()
        val key = secrets.key()
        val storage = linkedStorage()
        if (link == null || key == null || storage == null) return@withLock MatchLook.Failed(SyncFailure.LINK_LOST)
        try {
            val downloads = storage.list().othersThan(link.deviceId).mapNotNull { download(storage, it, key) }
            MatchLook.Ready(matchPreviewOf(data.snapshot().profiles, downloads))
        } catch (e: SyncStorageException) {
            MatchLook.Failed(e.failure.asSyncFailure())
        } catch (e: RoundFailure) {
            MatchLook.Failed(e.failure)
        } catch (e: SQLiteException) {
            Log.w(SYNC_LOG_TAG, "match not read: ${e.javaClass.simpleName}")
            MatchLook.Failed(SyncFailure.NOT_SAVED)
        }
    }

    /** The owner confirmed Match: [choices] are kept in the link and the first round runs with
     *  them. Nothing unless this device is awaiting Match. */
    override suspend fun startSyncing(choices: MatchChoices) {
        rounds.withLock {
            val link = state.link()?.takeIf { it.awaitingMatch } ?: return@withLock
            val confirmed = link.copy(awaitingMatch = false, choices = choices)
            if (state.save(confirmed)) runRound(confirmed) else notSaved(link)
        }
    }

    /** Sign in again after [SyncFailure.SIGN_IN_REJECTED]: only the credential changes (the key
     *  stays) and the old sign-in's failure goes; the caller asks for the round that tries it.
     *  False, with nothing changed, unless [credential] names the linked folder (another one
     *  would hold no other device's files) or while unlinked. */
    override suspend fun replaceSignIn(credential: SyncCredential): Boolean = rounds.withLock {
        val link = state.link() ?: return@withLock false
        if (!credential.sameFolder(secrets.credential()?.let(::decodeCredential))) return@withLock false
        val next = link.copy(account = credential.account().stored(), failure = null)
        if (savedSealed { secrets.saveCredential(encodeCredential(credential)) && state.save(next) }) {
            show(next.status(syncing = false))
        } else {
            notSaved(link)
        }
        true
    }

    /**
     * Links this device to [credential]'s folder with [passphrase] (wiped): the folder's key when
     * any of its files opens with the passphrase, else a new one (M8-D15). A folder whose files
     * all fail to read as this build's envelopes is taken as a newer Ayn's. A new folder's link
     * holds only once this device's file is in it, so a device linking next joins this key; a
     * joining device awaits the owner's Match choices ([startSyncing]) and pairs its profiles in
     * that first round. Linking again after [SyncFailure.LINK_LOST] keeps this device's id (so its
     * file in the folder is replaced rather than left behind) and, for the same account, its Match
     * state and choices.
     */
    override suspend fun link(credential: SyncCredential, passphrase: CharArray): LinkResult = try {
        rounds.withLock {
            try {
                val storage = storageFor(credential) ?: return@withLock LinkResult.Failed(SyncFailure.FOLDER_MISSING)
                val names = storage.listOrMake().map { it.name }.filter { it.endsWith(SNAPSHOT_SUFFIX) }
                val previous = state.link()
                when (val found = folderKey(storage, names, passphrase)) {
                    is FolderKey.Found -> saveLink(credential, found.key, previous, joined = true)
                    FolderKey.WrongPassphrase -> LinkResult.WrongPassphrase
                    FolderKey.None if names.isNotEmpty() -> LinkResult.Failed(SyncFailure.NEWER_VERSION)
                    FolderKey.None -> startFolder(credential, deriveSyncKey(passphrase, newSalt(random), compute = compute), previous)
                }
            } catch (e: SyncStorageException) {
                LinkResult.Failed(e.failure.asSyncFailure())
            }
        }
    } finally {
        // Outside the lock, so a cancel while waiting for a round still wipes it.
        passphrase.fill('\u0000')
    }

    /**
     * Turns sync off on this device: its two files leave the folder (M8-D2), then its link and
     * secrets go whatever the service answered. False when the files couldn't be removed; the
     * other devices then keep merging their last copy.
     */
    override suspend fun unlink(): Boolean = rounds.withLock {
        val link = state.link()
        val storage = linkedStorage()
        val removed = link != null && storage != null && try {
            storage.delete(snapshotName(link.deviceId))
            storage.delete(prevName(link.deviceId))
            true
        } catch (e: SyncStorageException) {
            Log.w(SYNC_LOG_TAG, "unlink left this device's files: ${e.failure}")
            false
        }
        if (!(secrets.clear() and state.clear())) Log.w(SYNC_LOG_TAG, "unlink not fully saved")
        show(SyncStatus(linked = false))
        removed
    }

    private suspend fun roundUnlessMatching(link: SyncLink) {
        if (link.awaitingMatch) show(link.status(syncing = false)) else runRound(link)
    }

    /** [link]'s round, its status kept current; a cancelled round leaves the status it started
     *  from. */
    private suspend fun runRound(link: SyncLink): SyncLink {
        show(link.status(syncing = true))
        var after = link
        try {
            after = round(link)
            if (!state.save(after)) Log.w(SYNC_LOG_TAG, "sync state not saved")
        } finally {
            show(after.status(syncing = false))
        }
        return after
    }

    /** The key derived from [passphrase] with the first envelope's parameters, when it opens any
     *  of [names] (a damaged file doesn't stop a later one from proving it). */
    private suspend fun folderKey(storage: SyncStorage, names: List<String>, passphrase: CharArray): FolderKey {
        var key: SyncKey? = null
        for (name in names) {
            val envelope = storage.get(name) ?: continue
            val parameters = envelopeKeyParameters(envelope) ?: continue
            val derived = key ?: deriveSyncKey(passphrase, parameters.salt, parameters.iterations, compute).also { key = it }
            if (withContext(compute) { openEnvelope(derived, envelope) } is EnvelopeRead.Opened) return FolderKey.Found(derived)
        }
        return if (key == null) FolderKey.None else FolderKey.WrongPassphrase
    }

    /** A new folder: linked once this device's first round put its file there; else undone,
     *  whatever ended the round (a failure, a cancel, a throw). */
    private suspend fun startFolder(credential: SyncCredential, key: SyncKey, previous: SyncLink?): LinkResult {
        val linked = saveLink(credential, key, previous, joined = false)
        if (linked !is LinkResult.Linked) return linked
        var failure: SyncFailure? = SyncFailure.NOT_SAVED
        try {
            failure = runRound(checkNotNull(state.link())).lastFailure()
        } finally {
            if (failure != null) withContext(NonCancellable) { undoLink(previous) }
        }
        return when (failure) {
            null -> linked
            SyncFailure.WRONG_PASSPHRASE -> LinkResult.WrongPassphrase
            else -> LinkResult.Failed(failure)
        }
    }

    /** Saves the link, keeping [previous]'s id (a link lost to the Keystore) and, linking again to
     *  the same account, its Match state and choices; the rest of its state goes. A join awaits
     *  Match unless that [previous] was past it. A failed step or a sealing error undoes it:
     *  [Failed][LinkResult.Failed] ([SyncFailure.NOT_SAVED]). */
    private suspend fun saveLink(credential: SyncCredential, key: SyncKey, previous: SyncLink?, joined: Boolean): LinkResult {
        val account = credential.account().stored()
        val sameAccount = previous?.takeIf { it.account == account }
        val link = SyncLink(
            deviceId = previous?.deviceId ?: newDeviceId(),
            account = account,
            awaitingMatch = joined && (sameAccount?.awaitingMatch ?: true),
            choices = sameAccount?.choices ?: MatchChoices(),
        )
        val saved = savedSealed { state.clear() && secrets.saveCredential(encodeCredential(credential)) && secrets.saveKey(key) && state.save(link) }
        if (!saved) {
            undoLink(previous)
            return LinkResult.Failed(SyncFailure.NOT_SAVED)
        }
        show(link.status(syncing = false))
        return LinkResult.Linked(joined)
    }

    /** Forgets the link being made. A link it replaced stays lost (its id and Match kept, so
     *  linking again still replaces its file); with none, this device is unlinked. */
    private suspend fun undoLink(previous: SyncLink?) {
        secrets.clear()
        state.clear()
        val lost = previous?.let {
            SyncLink(deviceId = it.deviceId, failure = SyncFailure.LINK_LOST.name, account = it.account, awaitingMatch = it.awaitingMatch, choices = it.choices)
        }
        if (lost != null && !state.save(lost)) Log.w(SYNC_LOG_TAG, "lost link not kept")
        show(lost?.status(syncing = false) ?: SyncStatus(linked = false))
    }

    private fun notSaved(link: SyncLink) {
        show(link.failedWith(SyncFailure.NOT_SAVED).status(syncing = false))
    }

    /** [save]'s answer; false when sealing failed. */
    private suspend fun savedSealed(save: suspend () -> Boolean): Boolean = try {
        save()
    } catch (e: Exception) {
        if (e !is GeneralSecurityException && e !is IOException && e !is ProviderException) throw e
        Log.w(SYNC_LOG_TAG, "link not sealed: ${e.javaClass.simpleName}")
        false
    }

    /** `null` when this device's sign-in can't be read (or no longer reads as a folder). */
    private suspend fun linkedStorage(): SyncStorage? = secrets.credential()?.let(::decodeCredential)?.let(storageFor)

    private suspend fun round(link: SyncLink): SyncLink {
        val key = secrets.key()
        val storage = linkedStorage()
        if (key == null || storage == null) return link.failedWith(SyncFailure.LINK_LOST)
        var done = link
        return try {
            beforeSnapshot()
            val files = storage.list()
            val own = snapshotName(link.deviceId)
            val others = files.othersThan(link.deviceId)
            val downloads = others.filter { file -> file.version()?.let { it != link.merged[file.name] } ?: true }
                .mapNotNull { file -> download(storage, file, key) }
            val snapshots = downloads.mapNotNull { it.snapshot }
            if (snapshots.isNotEmpty() || !link.paired) data.apply(snapshots, pair = !link.paired, openProfileId(), link.choices)
            val listed = others.mapTo(HashSet()) { it.name }
            done = link.copy(
                paired = true,
                merged = link.merged.filterKeys { it in listed } + downloads.mapNotNull { (file, _) -> file.version()?.let { file.name to it } },
                devices = others.devices(),
                folderBytes = folderBytes(files, written = emptyMap()),
            )
            val (uploadedLink, written) = upload(storage, key, done, ownListed = files.any { it.name == own })
            uploadedLink.copy(folderBytes = folderBytes(files, written), syncedAtMillis = clock.now().toEpochMilliseconds(), failure = null)
        } catch (e: SyncStorageException) {
            done.failedWith(e.failure.asSyncFailure())
        } catch (e: RoundFailure) {
            done.failedWith(e.failure)
        } catch (e: SQLiteException) {
            Log.w(SYNC_LOG_TAG, "sync not applied: ${e.javaClass.simpleName}")
            done.failedWith(SyncFailure.NOT_SAVED)
        }
    }

    /** [file] and its snapshot, `null` as the snapshot when it can't be merged (damaged even in its
     *  `.prev.ayn`, or from before version 6); `null` when the file went meanwhile. */
    private suspend fun download(storage: SyncStorage, file: RemoteFile, key: SyncKey): Download? {
        val envelope = storage.get(file.name) ?: return null
        val plaintext = withContext(compute) { plaintextOf(key, envelope) }
            ?: storage.get(prevNameOf(file.name))?.let { previous -> withContext(compute) { openedOrNull(key, previous) } }
        val snapshot = plaintext?.let { withContext(compute) { snapshotOf(it) } }
        if (snapshot == null) Log.w(SYNC_LOG_TAG, "a device's file skipped: not a snapshot this build merges")
        return Download(file, snapshot)
    }

    /** Uploads this device's snapshot when it changed since the last upload, its export time
     *  aside, or its file is gone; the last upload goes to `.prev.ayn` first. Returns the link and
     *  the sizes of the files it wrote, by name. */
    private suspend fun upload(storage: SyncStorage, key: SyncKey, link: SyncLink, ownListed: Boolean): Pair<SyncLink, Map<String, Long>> {
        val snapshot = data.snapshot()
        val hash = withContext(compute) { sha256Hex(encodeBackup(snapshot.copy(exportedAtMillis = 0))) }
        if (hash == link.uploadedHash && ownListed) return link to emptyMap()
        val envelope = withContext(compute) {
            val plaintext = encodeBackup(snapshot)
            if (plaintext.size > MAX_BACKUP_BYTES) throw RoundFailure(SyncFailure.TOO_LARGE)
            sealEnvelope(key, plaintext, random)
        }
        val previous = state.lastUpload()
        previous?.let { storage.put(prevName(link.deviceId), it) }
        storage.put(snapshotName(link.deviceId), envelope)
        state.keepLastUpload(envelope)
        val written = buildMap {
            previous?.let { put(prevName(link.deviceId), it.size.toLong()) }
            put(snapshotName(link.deviceId), envelope.size.toLong())
        }
        return link.copy(uploadedHash = hash) to written
    }

    /** `<model slug>-<8 hex>` (M8-D17). */
    private fun newDeviceId(): String {
        val slug = deviceName.lowercase().replace(NOT_SLUG, "-").trim('-').take(MAX_SLUG).trimEnd('-').ifEmpty { "device" }
        return "$slug-${ByteArray(DEVICE_ID_BYTES).also(random::nextBytes).toHexString()}"
    }
}

private sealed interface FolderKey {
    class Found(val key: SyncKey) : FolderKey

    /** The folder holds envelopes, none of which opens with the passphrase. */
    data object WrongPassphrase : FolderKey

    /** No file reads as an envelope this build opens. */
    data object None : FolderKey
}

private data class Download(val file: RemoteFile, val snapshot: BackupBundle?)

/** The folder's list, the folder made first when it isn't there yet (listed again after, so an
 *  address that isn't a WebDAV folder still reads as missing). */
private suspend fun SyncStorage.listOrMake(): List<RemoteFile> = try {
    list()
} catch (e: SyncStorageException) {
    if (e.failure != StorageFailure.FOLDER_MISSING) throw e
    createFolder()
    list()
}

/** The folder's size after a round: the listed [files] with this round's [written] ones in place
 *  of their listed copies; `null` when the service lists a file without its size. */
private fun folderBytes(files: List<RemoteFile>, written: Map<String, Long>): Long? {
    val sizes = (files.associate { it.name to it.sizeBytes } + written).values
    return if (null in sizes) null else sizes.filterNotNull().sum()
}

/** The other devices' snapshot files: every one but [deviceId]'s. */
private fun List<RemoteFile>.othersThan(deviceId: String?): List<RemoteFile> =
    filter { it.name.isSnapshotName() && (deviceId == null || it.name != snapshotName(deviceId)) }

/** The devices whose snapshot files these are. */
private fun List<RemoteFile>.devices(): List<SyncDevice> = map { SyncDevice(it.name.removeSuffix(SNAPSHOT_SUFFIX), it.modifiedMillis) }

/** This device's [local] profiles and the [downloads]' (one row per sync id, as the merge keeps
 *  it, with every device holding it), paired as the first round would pair them. */
private fun matchPreviewOf(local: List<BackupProfile>, downloads: List<Download>): MatchPreview {
    val held = downloads.flatMap { download -> download.snapshot?.profiles.orEmpty().map { it to download.file } }
    val remote = held.groupBy({ (profile, _) -> profile.requireSyncId() }).values.map { copies ->
        val profiles = copies.map { (profile, _) -> profile }
        profiles.newestRow().toMatch(
            historyCount = profiles.flatMap { it.history }.distinctBy { it.videoId }.size,
            devices = copies.map { (_, file) -> file }.devices(),
        )
    }
    val pairing = pairProfiles(local, held.map { (profile, _) -> profile })
    return MatchPreview(local.map { it.toMatch(it.history.size) }, remote, pairing.profiles, pairing.fixed)
}

private fun BackupProfile.toMatch(historyCount: Int, devices: List<SyncDevice> = emptyList()): MatchProfile =
    MatchProfile(requireSyncId(), name, tint, owner, kids, historyCount, devices)

/** A round stopped before its apply, or at its upload, for a [failure] not the service's. */
private class RoundFailure(val failure: SyncFailure) : Exception(failure.name)

/** `null` when [envelope] is damaged: not one (cut short below its header, or not Ayn's), or
 *  sealed with this key's parameters yet failing to open (cut short or altered past its header;
 *  a device sealing with these parameters holds this passphrase). */
private fun plaintextOf(key: SyncKey, envelope: ByteArray): ByteArray? = when (val read = openEnvelope(key, envelope)) {
    is EnvelopeRead.Opened -> read.plaintext
    EnvelopeRead.WrongKey if envelopeKeyParameters(envelope)?.let(key::sameParameters) == true -> null
    EnvelopeRead.WrongKey -> throw RoundFailure(SyncFailure.WRONG_PASSPHRASE)
    EnvelopeRead.NewerVersion -> throw RoundFailure(SyncFailure.NEWER_VERSION)
    EnvelopeRead.NotAnEnvelope -> null
}

/** The fallback copy's plaintext; `null` whatever kept it from opening. */
private fun openedOrNull(key: SyncKey, envelope: ByteArray): ByteArray? = (openEnvelope(key, envelope) as? EnvelopeRead.Opened)?.plaintext

/** A sync snapshot this build merges; `null` for one from before version 6 (no sync ids) or one
 *  that doesn't read as a backup. */
private fun snapshotOf(plaintext: ByteArray): BackupBundle? = when (val read = decodeBackup(plaintext)) {
    is BackupRead.Valid -> read.backup.bundle.takeIf { it.bundleVersion >= SYNC_IDS_VERSION }
    is BackupRead.Refused if read.reason == BackupRefusal.NewerVersion -> throw RoundFailure(SyncFailure.NEWER_VERSION)
    is BackupRead.Refused -> null
}

private fun SyncLink.failedWith(failure: SyncFailure): SyncLink = copy(failure = failure.name)

private fun SyncLink.status(syncing: Boolean): SyncStatus = SyncStatus(
    linked = true,
    syncing = syncing,
    lastSyncedMillis = syncedAtMillis,
    failure = lastFailure(),
    account = account?.account(),
    deviceId = deviceId,
    devices = devices,
    folderBytes = folderBytes,
    awaitingMatch = awaitingMatch,
)

private fun SyncLink.lastFailure(): SyncFailure? = SyncFailure.entries.firstOrNull { it.name == failure }

private fun StorageFailure.asSyncFailure(): SyncFailure = when (this) {
    StorageFailure.SIGN_IN_REJECTED -> SyncFailure.SIGN_IN_REJECTED
    StorageFailure.STORAGE_FULL -> SyncFailure.STORAGE_FULL
    StorageFailure.NO_CONNECTION -> SyncFailure.NO_CONNECTION
    StorageFailure.FOLDER_MISSING -> SyncFailure.FOLDER_MISSING
    StorageFailure.SERVER_ERROR -> SyncFailure.SERVER_ERROR
}

/** What decides whether a file changed: its etag, else its modified time; `null` when the service
 *  gives neither (downloaded every round). */
private fun RemoteFile.version(): String? = etag ?: modifiedMillis?.let { "modified:$it" }

internal fun encodeCredential(credential: SyncCredential): ByteArray =
    boundaryJson.encodeToString(SyncCredential.serializer(), credential).encodeToByteArray()

internal fun decodeCredential(bytes: ByteArray): SyncCredential? = decodeOrNull(bytes.decodeToString(), SyncCredential.serializer())

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

/** A device's snapshot (M8-D2). */
internal fun snapshotName(deviceId: String): String = "$deviceId$SNAPSHOT_SUFFIX"

/** The snapshot it replaced. */
internal fun prevName(deviceId: String): String = "$deviceId$PREV_SUFFIX"

private fun prevNameOf(snapshotFile: String): String = prevName(snapshotFile.removeSuffix(SNAPSHOT_SUFFIX))

private fun String.isSnapshotName(): Boolean = endsWith(SNAPSHOT_SUFFIX) && !endsWith(PREV_SUFFIX)

internal const val SNAPSHOT_SUFFIX = ".ayn"
private const val PREV_SUFFIX = ".prev.ayn"
private val NOT_SLUG = Regex("[^a-z0-9]+")
private const val MAX_SLUG = 24
private const val DEVICE_ID_BYTES = 4
