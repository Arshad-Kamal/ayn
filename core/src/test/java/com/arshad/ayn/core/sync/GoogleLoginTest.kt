package com.arshad.ayn.core.sync

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** [GoogleLogin] against Google's recorded device-flow and refresh answers ([RecordedDrive]). */
class GoogleLoginTest {
    private val server = MockWebServer().apply { start() }
    private val login = GoogleLogin(
        OkHttpClient(),
        GoogleClient("CLIENT-ID.apps.googleusercontent.com", "CLIENT-SECRET"),
        RecordedDrive.endpoints(server),
        UnconfinedTestDispatcher(),
    )

    @After
    fun stopServer() = server.close()

    @Test
    fun startAsksForDriveFileAndGivesTheCodeThePageAndTheTimes() = runTest {
        val recorded = RecordedDrive.enqueue(server, "01-device-code").single()

        val start = login.start()

        assertEquals("USER-CODE", start.userCode)
        assertEquals("https://www.google.com/device".toHttpUrl(), start.verificationUrl)
        assertEquals(1800L, start.expiresInSeconds)
        assertEquals(5L, start.intervalSeconds)
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun startTakesTheIntervalTheAnswerGives() = runTest {
        server.enqueue(answer(200, """{"device_code":"D","user_code":"U","expires_in":1800,"interval":8,"verification_url":"https://www.google.com/device"}"""))

        assertEquals(8L, login.start().intervalSeconds)
    }

    @Test
    fun startWithAPageThatIsNotHttpsFailsAsServerError() = runTest {
        server.enqueue(answer(200, """{"device_code":"D","user_code":"U","expires_in":1800,"interval":5,"verification_url":"http://www.google.com/device"}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.start() }
    }

    @Test
    fun startRefusedForQuotaFailsAsServerError() = runTest {
        server.enqueue(answer(403, """{"error_code":"rate_limit_exceeded"}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.start() }
    }

    @Test
    fun pollBeforeTheUserAnswersIsPending() = runTest {
        val recorded = RecordedDrive.enqueue(server, "01-device-code", "02-token-pending")
        val start = login.start()

        assertEquals(DevicePoll.Pending, login.poll(start))
        server.takeRequest()
        recorded[1].assertSent(server.takeRequest())
    }

    @Test
    fun pollAfterTheUserAllowsGivesTheRefreshToken() = runTest {
        val recorded = RecordedDrive.enqueue(server, "01-device-code", "03-token-granted")
        val start = login.start()

        val poll = login.poll(start)

        assertEquals("REFRESH-TOKEN", (poll as DevicePoll.Granted).refreshToken)
        assertEquals("ACCESS-TOKEN", poll.accessToken)
        server.takeRequest()
        recorded[1].assertSent(server.takeRequest())
    }

    // Not recorded: the answers below are Google's device-flow page's examples (checked 2026-11-03).
    @Test
    fun pollTooOftenIsSlowDown() = runTest {
        assertEquals(DevicePoll.SlowDown, pollAnswered(403, """{"error":"slow_down","error_description":"Forbidden"}"""))
    }

    @Test
    fun pollAfterTheUserRefusesIsDenied() = runTest {
        assertEquals(DevicePoll.Denied, pollAnswered(403, """{"error":"access_denied","error_description":"Forbidden"}"""))
    }

    @Test
    fun pollAfterTheCodeRanOutIsExpired() = runTest {
        assertEquals(DevicePoll.Expired, pollAnswered(400, """{"error":"expired_token"}"""))
    }

    @Test
    fun pollWithAnUnlistedErrorFailsAsServerError() = runTest {
        RecordedDrive.enqueue(server, "01-device-code")
        val start = login.start()
        server.enqueue(answer(401, """{"error":"invalid_client"}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.poll(start) }
    }

    @Test
    fun aGrantWithoutARefreshTokenFailsAsServerError() = runTest {
        RecordedDrive.enqueue(server, "01-device-code")
        val start = login.start()
        server.enqueue(answer(200, """{"access_token":"A","expires_in":3599,"token_type":"Bearer"}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.poll(start) }
    }

    @Test
    fun refreshGivesANewAccessToken() = runTest {
        val recorded = RecordedDrive.enqueue(server, "20-refresh").single()

        assertEquals("ACCESS-TOKEN-2", login.accessToken("REFRESH-TOKEN"))
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun refreshAfterTheSignInWasRevokedFailsAsSignInRejected() = runTest {
        val recorded = RecordedDrive.enqueue(server, "26-refresh-revoked").single()

        assertStorageFailure(StorageFailure.SIGN_IN_REJECTED) { login.accessToken("REFRESH-TOKEN") }
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun aTokenAnswerNestedPastItsDepthFailsAsServerError() = runTest {
        server.enqueue(answer(200, """{"access_token":"A","x":{"y":{"z":1}}}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.accessToken("REFRESH-TOKEN") }
    }

    @Test
    fun refreshRefusedForAnotherReasonFailsAsServerError() = runTest {
        server.enqueue(answer(401, """{"error":"invalid_client","error_description":"The OAuth client was not found."}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.accessToken("REFRESH-TOKEN") }
    }

    @Test
    fun accountEmailReadsTheSignedInAddress() = runTest {
        val recorded = RecordedDrive.enqueue(server, "28-about").single()

        assertEquals("owner@example.org", login.accountEmail("ACCESS-TOKEN"))
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun aBlankAccountAddressFailsAsServerError() = runTest {
        server.enqueue(answer(200, """{"user":{"emailAddress":""}}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.accountEmail("ACCESS-TOKEN") }
    }

    @Test
    fun aPollAnsweredWithAServerErrorIsStillPending() = runTest {
        RecordedDrive.enqueue(server, "01-device-code")
        val start = login.start()
        server.enqueue(MockResponse.Builder().code(503).build())

        assertEquals(DevicePoll.Pending, login.poll(start))
    }

    @Test
    fun accountEmailWithARefusedTokenFailsAsSignInRejected() = runTest {
        server.enqueue(RecordedDrive("19-bad-access-token").response())

        assertStorageFailure(StorageFailure.SIGN_IN_REJECTED) { login.accountEmail("ACCESS-TOKEN") }
    }

    @Test
    fun noRedirectIsFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere")).build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.accessToken("REFRESH-TOKEN") }
        assertEquals(1, server.requestCount)
    }

    private suspend fun pollAnswered(code: Int, body: String): DevicePoll {
        RecordedDrive.enqueue(server, "01-device-code")
        val start = login.start()
        server.enqueue(answer(code, body))
        return login.poll(start)
    }

    private fun answer(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()
}
