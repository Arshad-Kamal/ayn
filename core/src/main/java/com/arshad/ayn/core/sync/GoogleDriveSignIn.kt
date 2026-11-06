package com.arshad.ayn.core.sync

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Google Drive's sign-in as the setup screens run it ([GoogleDriveSignIn]). */
interface DriveSignIn {
    suspend fun start(): DriveSignInStart

    suspend fun awaitGrant(started: DriveSignInStart.Started): SignInEnd
}

sealed interface DriveSignInStart {
    /** Show [userCode] and [verificationUrl] (google.com/device): the user types the code there on
     *  a phone and allows Ayn while [DriveSignIn.awaitGrant] polls. */
    class Started internal constructor(val userCode: String, val verificationUrl: String, internal val start: DeviceStart) : DriveSignInStart {
        /** How long [userCode] works, in whole minutes (Google's `expires_in`). */
        val minutes: Long get() = start.expiresInSeconds / 60
    }

    data class Refused(val failure: SetupFailure) : DriveSignInStart
}

/**
 * Google's device sign-in for the setup screens (spec 20 Decisions): [start] gets the code,
 * [awaitGrant] polls at the interval Google gave (5 s longer after each `slow_down`) until the
 * user allows or refuses Ayn or the code's time is up by [timeSource]. A poll under way is left to
 * finish rather than cut off mid-answer, as [NextcloudSignIn]'s; a grant that arrives after the
 * wait was left is dropped (the user starts again). The grant's access token then reads the
 * account's address ([GoogleLogin.accountEmail]) for the credential.
 */
class GoogleDriveSignIn internal constructor(
    private val login: GoogleLogin,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DriveSignIn {
    constructor(httpClient: OkHttpClient, client: GoogleClient) : this(GoogleLogin(httpClient, client))

    override suspend fun start(): DriveSignInStart = try {
        val start = login.start()
        DriveSignInStart.Started(start.userCode, start.verificationUrl.toString(), start)
    } catch (e: SyncStorageException) {
        DriveSignInStart.Refused(setupFailureOf(e.failure))
    }

    /** A dropped connection doesn't end the wait; Google refusing the poll itself (a wrong client
     *  secret, say) does, as [SetupFailure.SERVER_ERROR]. */
    override suspend fun awaitGrant(started: DriveSignInStart.Started): SignInEnd {
        val begun = timeSource.markNow()
        val life = started.start.expiresInSeconds.seconds
        var every = started.start.intervalSeconds.coerceAtLeast(1).seconds
        while (true) {
            if (begun.elapsedNow() + every >= life) return SignInEnd.Expired
            delay(every)
            val poll = withContext(NonCancellable) {
                try {
                    login.poll(started.start)
                } catch (e: SyncStorageException) {
                    if (e.failure != StorageFailure.NO_CONNECTION) return@withContext null
                    DevicePoll.Pending
                }
            }
            when (poll) {
                null -> return SignInEnd.Refused(SetupFailure.SERVER_ERROR)
                DevicePoll.Pending -> Unit
                DevicePoll.SlowDown -> every += SLOW_DOWN_STEP
                DevicePoll.Denied -> return SignInEnd.Refused(SetupFailure.DENIED)
                DevicePoll.Expired -> return SignInEnd.Expired
                is DevicePoll.Granted -> return granted(poll)
            }
        }
    }

    private suspend fun granted(grant: DevicePoll.Granted): SignInEnd = try {
        SignInEnd.Granted(SyncCredential.GoogleDrive(grant.refreshToken, login.accountEmail(grant.accessToken)))
    } catch (e: SyncStorageException) {
        SignInEnd.Refused(setupFailureOf(e.failure))
    }

    private companion object {
        /** RFC 8628 3.5: each `slow_down` adds 5 s to the poll interval from then on. */
        val SLOW_DOWN_STEP = 5.seconds
    }
}
