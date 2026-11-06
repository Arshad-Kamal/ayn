package com.arshad.ayn.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * In-memory [SyncControl] for the layers above the runner. Each answer is the test's
 * ([lookResult], [linkResult], [previewResult], [replaceAccepts], [unlinkRemoves]); a gate, when set, holds its call
 * until the test completes it. Linking, starting, signing in again and unlinking move [status] as
 * the runner does: a join awaits Match while [joinAwaitsMatch].
 */
class FakeSyncControl : SyncControl {
    override val status: MutableStateFlow<SyncStatus> = MutableStateFlow(SyncStatus(linked = false))

    var lookResult: FolderLook = FolderLook.Ready(emptyList())
    var linkResult: LinkResult = LinkResult.Linked(joined = false)
    var previewResult: MatchLook = MatchLook.Failed(SyncFailure.NO_CONNECTION)
    var joinAwaitsMatch: Boolean = true

    /** False: Sign in again's credential names another folder. */
    var replaceAccepts: Boolean = true

    /** False: Sign in again's credential couldn't be sealed (NOT_SAVED, the old one kept). */
    var replaceSaves: Boolean = true
    var unlinkRemoves: Boolean = true

    var lookGate: CompletableDeferred<Unit>? = null
    var linkGate: CompletableDeferred<Unit>? = null
    var startGate: CompletableDeferred<Unit>? = null
    var replaceGate: CompletableDeferred<Unit>? = null
    var unlinkGate: CompletableDeferred<Unit>? = null

    val looked: MutableList<SyncCredential> = mutableListOf()

    /** Each link's credential and passphrase, read before the wipe. */
    val links: MutableList<Pair<SyncCredential, String>> = mutableListOf()
    val started: MutableList<MatchChoices> = mutableListOf()
    val signIns: MutableList<SyncCredential> = mutableListOf()
    var previews: Int = 0
    var unlinks: Int = 0

    override suspend fun look(credential: SyncCredential): FolderLook {
        looked += credential
        lookGate?.await()
        return lookResult
    }

    override suspend fun link(credential: SyncCredential, passphrase: CharArray): LinkResult {
        links += credential to String(passphrase)
        passphrase.fill('\u0000')
        linkGate?.await()
        val result = linkResult
        if (result is LinkResult.Linked) {
            status.value = SyncStatus(
                linked = true,
                account = credential.account(),
                deviceId = status.value.deviceId ?: THIS_DEVICE,
                awaitingMatch = result.joined && joinAwaitsMatch,
            )
        }
        return result
    }

    override suspend fun matchPreview(): MatchLook {
        previews++
        return previewResult
    }

    override suspend fun startSyncing(choices: MatchChoices) {
        started += choices
        startGate?.await()
        status.value = status.value.copy(awaitingMatch = false)
    }

    override suspend fun replaceSignIn(credential: SyncCredential): Boolean {
        signIns += credential
        replaceGate?.await()
        status.value = when {
            !replaceAccepts -> status.value
            replaceSaves -> status.value.copy(account = credential.account(), failure = null)
            else -> status.value.copy(failure = SyncFailure.NOT_SAVED)
        }
        return replaceAccepts
    }

    override suspend fun unlink(): Boolean {
        unlinks++
        unlinkGate?.await()
        status.value = SyncStatus(linked = false)
        return unlinkRemoves
    }

    companion object {
        const val THIS_DEVICE: String = "shield-android-tv-0a1b2c3d"
    }
}

/**
 * [SyncSignIn] for the setup screens' tests: [start] answers [startResult]; [awaitGrant] waits for
 * the test's [grant] and, like the real poll under way, finishes even when cancelled.
 */
class FakeSyncSignIn : SyncSignIn {
    var startResult: SignInStart = startedAt("cloud.example.org")
    var grant: CompletableDeferred<SignInEnd> = CompletableDeferred()
    val addresses: MutableList<String> = mutableListOf()
    var awaits: Int = 0

    override suspend fun start(address: String): SignInStart {
        addresses += address
        return startResult
    }

    override suspend fun awaitGrant(started: SignInStart.Started): SignInEnd {
        awaits++
        return withContext(NonCancellable) { grant.await() }
    }

    companion object {
        fun startedAt(server: String): SignInStart.Started = SignInStart.Started(
            loginUrl = "https://$server/login/v2/flow/FLOW-TOKEN",
            server = server,
            start = LoginStart("https://$server/login/v2/flow/FLOW-TOKEN".toHttpUrl(), "https://$server/login/v2/poll".toHttpUrl(), "POLL-TOKEN"),
        )
    }
}

/**
 * [DriveSignIn] for the setup screens' tests: [start] answers [startResult] (held by [startGate]
 * when set); [awaitGrant] waits for the test's [grant] and, like the real poll under way, finishes
 * even when cancelled.
 */
class FakeDriveSignIn : DriveSignIn {
    var startResult: DriveSignInStart = started()
    var startGate: CompletableDeferred<Unit>? = null
    var grant: CompletableDeferred<SignInEnd> = CompletableDeferred()
    var starts: Int = 0
    var awaits: Int = 0

    override suspend fun start(): DriveSignInStart {
        starts++
        startGate?.await()
        return startResult
    }

    override suspend fun awaitGrant(started: DriveSignInStart.Started): SignInEnd {
        awaits++
        return withContext(NonCancellable) { grant.await() }
    }

    companion object {
        /** A code as Google gives it (`01-device-code`: 30 minutes, polled every 5 s). */
        fun started(userCode: String = "GQVQ-JKEC"): DriveSignInStart.Started = DriveSignInStart.Started(
            userCode = userCode,
            verificationUrl = "https://www.google.com/device",
            start = DeviceStart(userCode, "https://www.google.com/device".toHttpUrl(), 1800, 5, "DEVICE-CODE"),
        )
    }
}

/** A Drive sign-in's credential, as [GoogleDriveSignIn] grants it. */
fun driveCredential(email: String = "owner@example.org"): SyncCredential = SyncCredential.GoogleDrive("REFRESH-TOKEN", email)

/** A Nextcloud sign-in's credential, as [NextcloudSignIn] grants it. */
fun nextcloudCredential(user: String = "arshad", server: String = "cloud.example.org"): SyncCredential =
    SyncCredential.WebDav("https://$server/remote.php/dav/files/$user/$SYNC_FOLDER/", user, "APP-PASSWORD", nextcloud = true)
