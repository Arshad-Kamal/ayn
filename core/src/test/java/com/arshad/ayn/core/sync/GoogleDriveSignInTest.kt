package com.arshad.ayn.core.sync

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GoogleDriveSignIn] and the Drive credential against Google's recorded device-flow answers
 *  ([RecordedDrive]); the code's recorded interval is 5 s and its life 1,800 s. */
class GoogleDriveSignInTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun stopServer() = server.close()

    @Test
    fun startGivesTheCodeAndThePageToShow() = runTest {
        RecordedDrive.enqueue(server, "01-device-code")

        val started = signIn().start() as DriveSignInStart.Started

        assertEquals("USER-CODE", started.userCode)
        assertEquals("https://www.google.com/device", started.verificationUrl)
        // The recording's expires_in is 1800 s.
        assertEquals(30L, started.minutes)
    }

    @Test
    fun startWhereGoogleCantBeReachedIsNoConnection() = runTest {
        val unreachable = GoogleEndpoints(oauth = server.url("/"), api = server.url("/")).let { endpoints ->
            server.close()
            GoogleDriveSignIn(GoogleLogin(OkHttpClient(), CLIENT, endpoints, UnconfinedTestDispatcher()), testScheduler.timeSource)
        }

        assertEquals(DriveSignInStart.Refused(SetupFailure.NO_CONNECTION), unreachable.start())
    }

    @Test
    fun startRefusedByGoogleIsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error_code":"rate_limit_exceeded"}""").build())

        assertEquals(DriveSignInStart.Refused(SetupFailure.SERVER_ERROR), signIn().start())
    }

    @Test
    fun awaitGrantPollsUntilTheUserAllowsThenReadsTheAccount() = runTest {
        val start = started()
        val recorded = RecordedDrive.enqueue(server, "02-token-pending", "03-token-granted", "28-about")

        val credential = (signIn().awaitGrant(start) as SignInEnd.Granted).credential as SyncCredential.GoogleDrive

        assertEquals("REFRESH-TOKEN", credential.refreshToken)
        assertEquals(SyncAccount(SyncService.GOOGLE_DRIVE, "", "owner@example.org"), credential.account())
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime)
        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    /** Each slow_down adds 5 s for good: polls at 5, then 10 and 15 apart. */
    @Test
    fun eachSlowDownWidensEveryLaterPollBy5Seconds() = runTest {
        val start = started()
        repeat(2) { server.enqueue(oauthError(403, "slow_down")) }
        RecordedDrive.enqueue(server, "03-token-granted", "28-about")

        assertTrue(signIn().awaitGrant(start) is SignInEnd.Granted)
        assertEquals((5 + 10 + 15).seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun aRefusalOnGooglesPageIsDenied() = runTest {
        val start = started()
        server.enqueue(oauthError(403, "access_denied"))

        assertEquals(SignInEnd.Refused(SetupFailure.DENIED), signIn().awaitGrant(start))
    }

    @Test
    fun anExpiredCodeEndsTheWait() = runTest {
        val start = started()
        server.enqueue(oauthError(400, "expired_token"))

        assertEquals(SignInEnd.Expired, signIn().awaitGrant(start))
    }

    /** 1,800 s at one poll every 5: the last poll goes out before the code's time is up. */
    @Test
    fun theWaitStopsWhenTheCodesTimeRunsOut() = runTest {
        val start = started()
        repeat(MAX_POLLS) { server.enqueue(RecordedDrive("02-token-pending").response()) }

        assertEquals(SignInEnd.Expired, signIn().awaitGrant(start))
        assertEquals(1 + MAX_POLLS, server.requestCount)
    }

    /** A slow network's polls count against the code's 1,800 s too: each poll here takes 600. */
    @Test
    fun slowPollsCountAgainstTheCodesTime() = runTest {
        val start = started()
        val time = TestTimeSource()
        val slow = OkHttpClient.Builder().eventListener(
            object : EventListener() {
                override fun callStart(call: Call) {
                    time += 600.seconds
                }
            },
        ).build()
        repeat(4) { server.enqueue(RecordedDrive("02-token-pending").response()) }

        val signIn = GoogleDriveSignIn(GoogleLogin(slow, CLIENT, RecordedDrive.endpoints(server), UnconfinedTestDispatcher(testScheduler)), time)

        assertEquals(SignInEnd.Expired, signIn.awaitGrant(start))
        assertEquals(1 + 3, server.requestCount)
    }

    @Test
    fun aServerHiccupDoesntEndTheWait() = runTest {
        val start = started()
        server.enqueue(MockResponse.Builder().code(503).build())
        RecordedDrive.enqueue(server, "03-token-granted", "28-about")

        assertTrue(signIn().awaitGrant(start) is SignInEnd.Granted)
    }

    @Test
    fun aDroppedConnectionDoesntEndTheWait() = runTest {
        val start = started()
        var dropped = false
        val dropsOnce = OkHttpClient.Builder().addInterceptor { chain ->
            if (!dropped) {
                dropped = true
                throw IOException("connection reset")
            }
            chain.proceed(chain.request())
        }.build()
        RecordedDrive.enqueue(server, "03-token-granted", "28-about")

        assertTrue(signIn(dropsOnce).awaitGrant(start) is SignInEnd.Granted)
    }

    /** Google refusing the poll itself (a wrong client secret) won't change by waiting. */
    @Test
    fun aRefusedPollEndsTheWaitAsServerError() = runTest {
        val start = started()
        server.enqueue(oauthError(401, "invalid_client"))

        assertEquals(SignInEnd.Refused(SetupFailure.SERVER_ERROR), signIn().awaitGrant(start))
        assertEquals(5.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    /** The tokens came once, so a failed account lookup ends the wait with a stated problem. */
    @Test
    fun aFailedAccountLookupAfterTheGrantIsRefused() = runTest {
        val start = started()
        RecordedDrive.enqueue(server, "03-token-granted", "19-bad-access-token")

        assertEquals(SignInEnd.Refused(SetupFailure.SERVER_ERROR), signIn().awaitGrant(start))
    }

    @Test
    fun anAccountLookupThatCantReachGoogleIsNoConnection() = runTest {
        val start = started()
        val dropsLookup = OkHttpClient.Builder().addInterceptor { chain ->
            if ("about" in chain.request().url.encodedPath) throw IOException("connection reset")
            chain.proceed(chain.request())
        }.build()
        RecordedDrive.enqueue(server, "03-token-granted")

        assertEquals(SignInEnd.Refused(SetupFailure.NO_CONNECTION), signIn(dropsLookup).awaitGrant(start))
    }

    /** A poll under way is left to finish: leaving the sign-in mid-poll lets its answer arrive. */
    @Test
    fun aPollUnderWayFinishesWhenTheWaitIsCancelled() = runTest {
        val start = started()
        server.enqueue(RecordedDrive("02-token-pending").response().newBuilder().headersDelay(300, TimeUnit.MILLISECONDS).build())
        val ended = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val client = OkHttpClient.Builder().eventListener(
            object : EventListener() {
                override fun callEnd(call: Call) = ended.set(true)

                override fun canceled(call: Call) = cancelled.set(true)
            },
        ).build()
        val login = GoogleLogin(client, CLIENT, RecordedDrive.endpoints(server), Dispatchers.IO)
        val waiting = launch { GoogleDriveSignIn(login, testScheduler.timeSource).awaitGrant(start) }
        testScheduler.advanceTimeBy(5.seconds)
        runCurrent()

        server.takeRequest()
        waiting.cancelAndJoin()

        assertTrue(ended.get())
        assertFalse(cancelled.get())
    }

    @Test
    fun aDriveCredentialKeepsItsTokenAndAddressThroughTheStore() {
        val stored = decodeCredential(encodeCredential(SyncCredential.GoogleDrive("REFRESH-TOKEN", "owner@example.org"))) as SyncCredential.GoogleDrive

        assertEquals("REFRESH-TOKEN", stored.refreshToken)
        assertEquals("owner@example.org", stored.email)
    }

    /** The stored form is a contract with every linked device: a renamed type or field would
     *  unlink them. */
    @Test
    fun storedCredentialsInTheirWrittenFormStillDecode() {
        val drive = decodeCredential("""{"type":"drive","refreshToken":"R","email":"owner@example.org"}""".encodeToByteArray()) as SyncCredential.GoogleDrive
        val webDav = decodeCredential(
            """{"type":"webdav","folder":"https://cloud.example.org/remote.php/dav/files/smoke/Ayn/","user":"smoke","password":"P","nextcloud":true}""".encodeToByteArray(),
        ) as SyncCredential.WebDav

        assertEquals("R" to "owner@example.org", drive.refreshToken to drive.email)
        assertEquals(SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "smoke"), webDav.account())
        assertEquals("P", webDav.password)
    }

    @Test
    fun aDriveCredentialsTextNeverHoldsItsToken() = runTest {
        val start = started()
        RecordedDrive.enqueue(server, "03-token-granted", "28-about")

        val end = signIn().awaitGrant(start) as SignInEnd.Granted

        assertFalse("REFRESH-TOKEN" in end.toString() || "REFRESH-TOKEN" in end.credential.toString())
    }

    @Test
    fun aDriveFolderIsTheSameForTheSameAccountInAnyCase() {
        val mine = SyncCredential.GoogleDrive("R1", "Owner@Example.org")

        assertTrue(mine.sameFolder(SyncCredential.GoogleDrive("R2", "owner@example.org")))
        assertFalse(mine.sameFolder(SyncCredential.GoogleDrive("R2", "someone@example.org")))
        assertFalse(mine.sameFolder(nextcloudCredential()))
    }

    @Test
    fun aBuildWithoutAClientIdHasNoGoogleClient() {
        assertNull(googleClientOrNull("", "CLIENT-SECRET"))
        assertNull(googleClientOrNull("CLIENT-ID", " "))
        assertNotNull(googleClientOrNull("CLIENT-ID", "CLIENT-SECRET"))
    }

    /** The recorded code, fetched through the sign-in (one request, taken off the server). */
    private suspend fun TestScope.started(): DriveSignInStart.Started {
        RecordedDrive.enqueue(server, "01-device-code")
        return (signIn().start() as DriveSignInStart.Started).also { server.takeRequest() }
    }

    private fun TestScope.signIn(client: OkHttpClient = OkHttpClient()): GoogleDriveSignIn =
        GoogleDriveSignIn(GoogleLogin(client, CLIENT, RecordedDrive.endpoints(server), UnconfinedTestDispatcher(testScheduler)), testScheduler.timeSource)

    private fun oauthError(code: Int, error: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8").body("""{"error":"$error"}""").build()

    private companion object {
        val CLIENT = GoogleClient("CLIENT-ID.apps.googleusercontent.com", "CLIENT-SECRET")

        /** Polls 5 s apart while elapsed + 5 s stays under 1,800 s: at 5 s … 1,795 s. */
        const val MAX_POLLS = 359
    }
}
