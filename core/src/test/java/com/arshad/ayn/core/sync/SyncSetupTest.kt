package com.arshad.ayn.core.sync

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup surface (M8-D18): typed addresses, WebDAV folders, device names, and the Nextcloud
 *  sign-in's polling against the recorded Login Flow answers. */
class SyncSetupTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun stopServer() = server.close()

    @Test
    fun anAddressWithoutASchemeIsTakenAsHttps() {
        assertEquals("https://cloud.example.com:8443/nextcloud", (readAddress(" cloud.example.com:8443/nextcloud ") as AddressRead.Https).url.toString())
    }

    @Test
    fun anHttpAddressNeedsHttpsAndNonsenseIsNoAddress() {
        assertEquals(AddressRead.Refused(SetupFailure.NEEDS_HTTPS), readAddress("HTTP://cloud.example.com"))
        assertEquals(AddressRead.Refused(SetupFailure.NOT_AN_ADDRESS), readAddress(""))
        assertEquals(AddressRead.Refused(SetupFailure.NOT_AN_ADDRESS), readAddress("ftp://cloud.example.com"))
        assertEquals(AddressRead.Refused(SetupFailure.NOT_AN_ADDRESS), readAddress("not an address"))
    }

    /** M8-D18: the folder the Nextcloud path uses, whether the address ends in `/` or not. */
    @Test
    fun aWebDavCredentialSyncsInTheAynFolderInsideTheAddress() {
        for (address in listOf("https://dav.example.com/remote.php/dav/files/arshad/", "dav.example.com/remote.php/dav/files/arshad")) {
            val credential = (webDavCredential(address, "arshad", "secret") as CredentialCheck.Ready).credential as SyncCredential.WebDav

            assertEquals("https://dav.example.com/remote.php/dav/files/arshad/Ayn/", credential.folder)
            assertEquals(SyncAccount(SyncService.WEBDAV, "dav.example.com", "arshad"), credential.account())
        }
    }

    @Test
    fun aWebDavCredentialOverHttpIsRefused() {
        assertEquals(CredentialCheck.Refused(SetupFailure.NEEDS_HTTPS), webDavCredential("http://dav.example.com/", "arshad", "secret"))
    }

    @Test
    fun aDeviceIsNamedByTheModelInItsId() {
        assertEquals("Pixel 7 Pro", deviceNameOf("pixel-7-pro-1a2b3c4d"))
        assertEquals("Shield Android TV", deviceNameOf("shield-android-tv-0f0f0f0f"))
        assertEquals("Device", deviceNameOf("device-00000000"))
    }

    /** Not a data class: the app password stays out of UI state dumps and logs. */
    @Test
    fun aCredentialsTextNeverHoldsItsPassword() {
        val check = webDavCredential("dav.example.com", "arshad", "s3cret-app-password")

        assertFalse("s3cret-app-password" in check.toString())
        assertFalse("s3cret-app-password" in (check as CredentialCheck.Ready).credential.toString())
    }

    @Test
    fun startAtAnHttpAddressAsksNoServer() = runTest {
        assertEquals(SignInStart.Refused(SetupFailure.NEEDS_HTTPS), signIn().start(server.url("/").toString()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aLoginPageTooLongForAQrCodeIsRefused() {
        val poll = "https://cloud.example.org/login/v2/poll".toHttpUrl()
        val flow = "https://cloud.example.org/login/v2/flow/"
        val longest = flow + "a".repeat(MAX_LOGIN_URL - flow.length)

        val ok = startedAt(LoginStart(longest.toHttpUrl(), poll, "POLL-TOKEN"), "cloud.example.org")
        assertEquals(longest, (ok as SignInStart.Started).loginUrl)
        assertEquals(
            SignInStart.Refused(SetupFailure.NOT_NEXTCLOUD),
            startedAt(LoginStart("${longest}a".toHttpUrl(), poll, "POLL-TOKEN"), "cloud.example.org"),
        )
    }

    @Test
    fun aLoginPageOrPollThatIsntHttpsNeedsHttps() {
        val https = "https://cloud.example.org/login/v2/flow/FLOW-TOKEN".toHttpUrl()
        val poll = "https://cloud.example.org/login/v2/poll".toHttpUrl()

        assertEquals(
            SignInStart.Refused(SetupFailure.NEEDS_HTTPS),
            startedAt(LoginStart("http://cloud.example.org/login/v2/flow/FLOW-TOKEN".toHttpUrl(), poll, "POLL-TOKEN"), "cloud.example.org"),
        )
        assertEquals(
            SignInStart.Refused(SetupFailure.NEEDS_HTTPS),
            startedAt(LoginStart(https, "http://cloud.example.org/login/v2/poll".toHttpUrl(), "POLL-TOKEN"), "cloud.example.org"),
        )
    }

    @Test
    fun startWhereNothingAnswersIsNoConnection() = runTest {
        assertEquals(SignInStart.Refused(SetupFailure.NO_CONNECTION), signIn().start("https://127.0.0.1:1/"))
    }

    @Test
    fun awaitGrantPollsUntilTheUserGrantsAccess() = runTest {
        RecordedWebDav.enqueue(server, "02-login-poll-pending", "02-login-poll-pending")
        server.enqueue(httpsGrant())
        RecordedWebDav.enqueue(server, "20-ocs-user")

        val credential = (signIn().awaitGrant(started()) as SignInEnd.Granted).credential as SyncCredential.WebDav

        assertEquals(4, server.requestCount)
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals("https://cloud.example.org/remote.php/dav/files/smoke/Ayn/", credential.folder)
        assertEquals(SyncService.NEXTCLOUD, credential.account().service)
    }

    /** A dropped connection or a server hiccup mid-wait doesn't lose the sign-in. */
    @Test
    fun aFailedPollDoesntEndTheWait() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(httpsGrant())
        RecordedWebDav.enqueue(server, "20-ocs-user")

        assertTrue(signIn().awaitGrant(started()) is SignInEnd.Granted)
    }

    /** The grant came once, so a failed account lookup ends the wait with a stated problem. */
    @Test
    fun aFailedAccountLookupAfterTheGrantIsRefused() = runTest {
        server.enqueue(httpsGrant())
        server.enqueue(MockResponse.Builder().code(401).build())

        assertEquals(SignInEnd.Refused(SetupFailure.SERVER_ERROR), signIn().awaitGrant(started()))
    }

    @Test
    fun anAccountLookupThatCantReachTheServerIsNoConnection() = runTest {
        server.enqueue(httpsGrant())
        val dropsLookup = toServer.newBuilder().addInterceptor { chain ->
            if ("ocs" in chain.request().url.encodedPath) throw IOException("connection reset")
            chain.proceed(chain.request())
        }.build()

        val signIn = NextcloudSignIn(NextcloudLogin(dropsLookup, UnconfinedTestDispatcher()), 5.seconds, testScheduler.timeSource)

        assertEquals(SignInEnd.Refused(SetupFailure.NO_CONNECTION), signIn.awaitGrant(started()))
    }

    /** Behind a misconfigured proxy the server may name an http address: the app password would
     *  travel in clear, so it is refused as a typed http address is, before the account lookup. */
    @Test
    fun aGrantNamingAnHttpServerIsRefused() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"server":"http://cloud.example.org","loginName":"smoke","appPassword":"APP-PASSWORD"}""").build())

        assertEquals(SignInEnd.Refused(SetupFailure.NEEDS_HTTPS), signIn().awaitGrant(started()))
        assertEquals(1, server.requestCount)
    }

    /** The grant comes once: leaving the sign-in mid-poll lets that poll's answer arrive. */
    @Test
    fun aPollUnderWayFinishesWhenTheWaitIsCancelled() = runTest {
        server.enqueue(MockResponse.Builder().headersDelay(300, TimeUnit.MILLISECONDS).code(404).body("[]").build())
        val ended = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val client = OkHttpClient.Builder().eventListener(
            object : EventListener() {
                override fun callEnd(call: Call) = ended.set(true)

                override fun canceled(call: Call) = cancelled.set(true)
            },
        ).build()
        val waiting = launch { NextcloudSignIn(NextcloudLogin(client, Dispatchers.IO)).awaitGrant(started()) }
        runCurrent()

        server.takeRequest()
        waiting.cancelAndJoin()

        assertTrue(ended.get())
        assertFalse(cancelled.get())
    }

    /** The token lasts 20 minutes: at one poll every 10, two polls and no more. */
    @Test
    fun awaitGrantStopsWhenTheTokensTimeRunsOut() = runTest {
        RecordedWebDav.enqueue(server, "02-login-poll-pending", "02-login-poll-pending")

        assertEquals(SignInEnd.Expired, signIn(pollEvery = 10.minutes).awaitGrant(started()))
        assertEquals(2, server.requestCount)
        assertEquals(10.minutes.inWholeMilliseconds, testScheduler.currentTime)
    }

    /** A slow server's polls count against the token's 20 minutes too: each poll here takes 9. */
    @Test
    fun slowPollsCountAgainstTheTokensTime() = runTest {
        val time = TestTimeSource()
        val slow = OkHttpClient.Builder().eventListener(
            object : EventListener() {
                override fun callStart(call: Call) {
                    time += 9.minutes
                }
            },
        ).build()
        repeat(4) { server.enqueue(MockResponse.Builder().code(404).body("[]").build()) }

        assertEquals(SignInEnd.Expired, NextcloudSignIn(NextcloudLogin(slow, UnconfinedTestDispatcher()), 5.seconds, time).awaitGrant(started()))
        assertEquals(3, server.requestCount)
    }

    /** The recorded grant's answer with an https server (the recording's is the test server's
     *  http address, which the sign-in refuses). */
    private fun httpsGrant(): MockResponse =
        MockResponse.Builder().code(200).body("""{"server":"https://cloud.example.org","loginName":"smoke","appPassword":"APP-PASSWORD"}""").build()

    private fun TestScope.signIn(pollEvery: Duration = 5.seconds): NextcloudSignIn =
        NextcloudSignIn(NextcloudLogin(toServer, UnconfinedTestDispatcher()), pollEvery, testScheduler.timeSource)

    /** Sends every call to [server]: the grants name `https://cloud.example.org`, which the
     *  account lookup would otherwise reach. */
    private val toServer: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val sent = chain.request().url
        val url = if (sent.host == "cloud.example.org") sent.newBuilder().scheme("http").host(server.hostName).port(server.port).build() else sent
        chain.proceed(chain.request().newBuilder().url(url).build())
    }.build()

    private fun started(): SignInStart.Started = SignInStart.Started(
        "https://cloud.example.org/login/v2/flow/FLOW-TOKEN",
        "cloud.example.org",
        LoginStart("https://cloud.example.org/login/v2/flow/FLOW-TOKEN".toHttpUrl(), server.url(RecordedWebDav("02-login-poll-pending").path), "POLL-TOKEN"),
    )
}
