package com.arshad.ayn.core.sync

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [NextcloudLogin] against Nextcloud's recorded Login Flow v2 and OCS user answers ([RecordedWebDav]). */
class NextcloudLoginTest {
    private val server = MockWebServer().apply { start() }
    private val login = NextcloudLogin(OkHttpClient(), UnconfinedTestDispatcher())

    @After
    fun stopServer() = server.close()

    @Test
    fun startGivesTheLoginPageAndThePollEndpointAsTheServerSaid() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "01-login-start").single()

        val start = login.start(server.url("/"))

        assertEquals(server.url("/login/v2/flow/FLOW-TOKEN"), start.loginUrl)
        assertEquals(server.url("/login/v2/poll"), start.pollEndpoint)
        assertEquals("POLL-TOKEN", start.pollToken)
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun startAtASubfolderInstallAsksUnderThatFolder() = runTest {
        RecordedWebDav.enqueue(server, "01-login-start")

        login.start(server.url("/nextcloud/"))

        assertEquals("/nextcloud/index.php/login/v2", server.takeRequest().url.encodedPath)
    }

    @Test
    fun startOnAServerWithoutNextcloudFailsAsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("<html>Not Found</html>").build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.start(server.url("/")) }
    }

    @Test
    fun startWithAnUnreadableAnswerFailsAsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"poll":{"token":"T"}}""").build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.start(server.url("/")) }
    }

    @Test
    fun pollBeforeTheUserGrantsAccessIsNull() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "02-login-poll-pending").single()

        assertNull(login.poll(pollFor(recorded)))
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun pollAfterTheGrantGivesTheServerLoginNameAndAppPassword() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "03-login-poll-granted").single()

        val grant = checkNotNull(login.poll(pollFor(recorded)))

        assertEquals(server.url("/"), grant.server)
        assertEquals("smoke", grant.loginName)
        assertEquals("APP-PASSWORD", grant.appPassword)
        recorded.assertSent(server.takeRequest())
    }

    /** The user lookup names the files folder: the Ayn folder the smoke's WebDAV calls used. */
    @Test
    fun theAccountsFolderIsNamedByTheUserIdTheServerGives() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "20-ocs-user").single()

        val account = login.account(NextcloudGrant(server.url("/"), "smoke", "APP-PASSWORD"))

        assertEquals(server.url(RecordedWebDav("05-propfind-folder-missing").path), account.folder)
        assertEquals("smoke", account.user)
        assertEquals("APP-PASSWORD", account.password)
        recorded.assertSent(server.takeRequest())
    }

    /** Signed in with an email address, a local account is granted its user id (recorded). */
    @Test
    fun anEmailSignInGetsTheFolderTheServerAnsweredFor() = runTest {
        val (_, lookup) = RecordedWebDav.enqueue(server, "21-login-poll-granted-email", "22-ocs-user-email")

        val grant = checkNotNull(login.poll(pollFor(RecordedWebDav("21-login-poll-granted-email"))))
        val account = login.account(grant)

        server.takeRequest()
        lookup.assertSent(server.takeRequest())
        assertEquals(server.url(RecordedWebDav("23-propfind-email-user-files").path + "Ayn/"), account.folder)
        assertEquals("mira", account.user)
        assertEquals("APP-PASSWORD-EMAIL", account.password)
    }

    /** An LDAP or SSO account whose login name isn't its id (not recordable here): the folder takes
     *  the id, the sign-in keeps the login name. */
    @Test
    fun aLoginNameThatIsntTheUserIdSignsInAsItselfInTheIdsFolder() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"6f8a2c1e-ldap"}}}""").build())

        val account = login.account(NextcloudGrant(server.url("/"), "jdoe", "APP-PASSWORD"))

        assertEquals(server.url("/remote.php/dav/files/6f8a2c1e-ldap/Ayn/"), account.folder)
        assertEquals("jdoe", account.user)
        assertEquals(Credentials.basic("jdoe", "APP-PASSWORD"), server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aRefusedUserLookupFailsAsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.account(NextcloudGrant(server.url("/"), "smoke", "APP-PASSWORD")) }
    }

    /** An id that isn't one path segment would put the folder outside the user's files. */
    @Test
    fun anIdThatCantNameAFolderFailsAsServerError() = runTest {
        listOf("", "..", "a/b").forEach { id ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"ocs":{"data":{"id":"$id"}}}""").build())

            assertStorageFailure(StorageFailure.SERVER_ERROR) { login.account(NextcloudGrant(server.url("/"), "smoke", "APP-PASSWORD")) }
        }
    }

    @Test
    fun anUnreadableUserAnswerFailsAsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ocs":{"data":{}}}""").build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.account(NextcloudGrant(server.url("/"), "smoke", "APP-PASSWORD")) }
    }

    @Test
    fun pollAfterTheGrantWasCollectedIsNullAgain() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "04-login-poll-again").single()

        assertNull(login.poll(pollFor(recorded)))
    }

    @Test
    fun aPollFailureAnswerFailsAsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.poll(pollFor(RecordedWebDav("02-login-poll-pending"))) }
    }

    /** Followed, the redirect's GET would lose the token and its 404 would read as pending. */
    @Test
    fun aRedirectedPollFailsAsServerErrorInsteadOfFollowingIt() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
        server.enqueue(MockResponse.Builder().code(404).body("[]").build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { login.poll(pollFor(RecordedWebDav("02-login-poll-pending"))) }
        assertEquals(1, server.requestCount)
    }

    /** A start whose poll endpoint is the recorded one (the smoke polled the `index.php` form of
     *  the endpoint the server gave; both reach the same page). */
    private fun pollFor(recorded: RecordedWebDav): LoginStart =
        LoginStart(server.url("/login/v2/flow/FLOW-TOKEN"), server.url(recorded.path), "POLL-TOKEN")
}
