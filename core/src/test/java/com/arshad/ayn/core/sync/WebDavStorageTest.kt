package com.arshad.ayn.core.sync

import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WebDavStorage] against Nextcloud's recorded answers ([RecordedWebDav]), each request checked
 * against its recording. Robolectric supplies the platform XML parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavStorageTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun stopServer() = server.close()

    @Test
    fun anEmptyFolderListsNoFiles() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "08-propfind-folder-empty")

        assertEquals(emptyList<RemoteFile>(), storage().list())
        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun aFolderListsItsFileWithTagTimeAndSize() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "11-propfind-folder-one-file")

        val files = storage().list()

        val expected = RemoteFile(
            name = "0f3c2a9e-device-a.ayn",
            etag = "\"bf2d0100e1494a275824e5b0a21398f7\"",
            modifiedMillis = Instant.parse("2026-10-30T05:53:14Z").toEpochMilli(),
            sizeBytes = 5_242_912,
        )
        assertEquals(listOf(expected), files)
        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun aMissingFolderFailsAsFolderMissing() = runTest {
        RecordedWebDav.enqueue(server, "05-propfind-folder-missing")

        assertStorageFailure(StorageFailure.FOLDER_MISSING) { storage().list() }
    }

    @Test
    fun creatingTheFolderSendsMkcol() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "06-mkcol-folder")

        storage().createFolder()

        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun creatingAFolderThatIsThereCountsAsMade() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "07-mkcol-folder-exists")

        storage().createFolder()

        recorded.single().assertSent(server.takeRequest())
    }

    /** RFC 4918 9.3.1: the address the user typed (the folder's parent) isn't there. */
    @Test
    fun creatingAFolderWithoutItsParentFailsAsFolderMissing() = runTest {
        server.enqueue(MockResponse.Builder().code(409).build())

        assertStorageFailure(StorageFailure.FOLDER_MISSING) { storage().createFolder() }
    }

    @Test
    fun putSendsTheContentForANewFileAndAnOverwrite() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "09-put-new", "10-put-overwrite")
        val content = RecordedWebDav.content(recorded.first().omittedBytes)

        storage().put(FILE, content)
        storage().put(FILE, content)

        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun getReturnsTheFilesContent() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "12-get").single()

        val content = storage().get(FILE)

        assertArrayEquals(RecordedWebDav.content(recorded.omittedBytes), content)
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun getOfAFileAnotherDeviceRemovedIsNull() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "15-get-missing")

        assertNull(storage().get(FILE))
        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun deleteRemovesTheFile() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "14-delete")

        storage().delete(FILE)

        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun deletingAFileAlreadyGoneCountsAsRemoved() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "16-delete-missing")

        storage().delete(FILE)

        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun aFullDriveFailsAsStorageFull() = runTest {
        RecordedWebDav.enqueue(server, "17-put-quota-exceeded")

        assertStorageFailure(StorageFailure.STORAGE_FULL) { storage().put(FILE, ByteArray(16)) }
    }

    @Test
    fun aWrongPasswordFailsAsSignInRejected() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "18-propfind-wrong-password")

        assertStorageFailure(StorageFailure.SIGN_IN_REJECTED) { storage(password = "wrong-password").list() }
        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun aRevokedSignInFailsAsSignInRejected() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "19-propfind-revoked")

        assertStorageFailure(StorageFailure.SIGN_IN_REJECTED) { storage().list() }
        recorded.single().assertSent(server.takeRequest())
    }

    @Test
    fun aServerThatStopsAnsweringFailsAsNoConnection() = runTest {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val impatient = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()

        assertStorageFailure(StorageFailure.NO_CONNECTION) { storage(client = impatient).get(FILE) }
    }

    @Test
    fun anUnreachableServerFailsAsNoConnection() = runTest {
        val folder = server.url(FOLDER_PATH)
        server.close()

        assertStorageFailure(StorageFailure.NO_CONNECTION) { storage(folder = folder).list() }
    }

    @Test
    fun anUnexpectedAnswerFailsAsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
    }

    /** The listing is otherwise readable: only its DTD (where an entity bomb would sit) refuses it. */
    @Test
    fun aListingThatDeclaresADtdIsRefused() = runTest {
        val listing = """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY a "aaaaaaaaaa">]>""" +
            """<d:multistatus xmlns:d="DAV:">${response("${FOLDER_PATH}b.ayn")}</d:multistatus>"""
        server.enqueue(MockResponse.Builder().code(207).body(listing).build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
    }

    @Test
    fun theFolderItselfAndItsSubFoldersAreLeftOut() {
        val listing = multistatus(response(FOLDER_PATH, collection = true), response("${FOLDER_PATH}old/", collection = true), response("${FOLDER_PATH}b.ayn"))

        assertEquals(listOf("b.ayn"), parseListing(listing, server.url(FOLDER_PATH))?.map { it.name })
    }

    @Test
    fun aFileOutsideTheFolderIsLeftOut() {
        val listing = multistatus(response("/remote.php/dav/files/smoke/other.ayn"), response("${FOLDER_PATH}b.ayn"))

        assertEquals(listOf("b.ayn"), parseListing(listing, server.url(FOLDER_PATH))?.map { it.name })
    }

    @Test
    fun aFullAddressHrefNamesAFileInTheFolder() {
        val listing = multistatus(response(server.url("${FOLDER_PATH}b.ayn").toString()))

        assertEquals(listOf("b.ayn"), parseListing(listing, server.url(FOLDER_PATH))?.map { it.name })
    }

    /** As Nextcloud reports a property it lacks: an empty element in a 404 propstat. */
    @Test
    fun aPropertyInANon200PropstatIsLeftUnset() {
        val listing = multistatus(
            "<d:response><d:href>${FOLDER_PATH}b.ayn</d:href>" +
                "<d:propstat><d:prop><d:getcontentlength>7</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                "<d:propstat><d:prop><d:getetag/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>",
        )

        assertEquals(listOf(RemoteFile("b.ayn", null, null, 7)), parseListing(listing, server.url(FOLDER_PATH)))
    }

    @Test
    fun aResponseWhoseOwnStatusIsNot200IsLeftOut() {
        val listing = multistatus(
            "<d:response><d:href>${FOLDER_PATH}gone.ayn</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>",
            response("${FOLDER_PATH}b.ayn"),
        )

        assertEquals(listOf("b.ayn"), parseListing(listing, server.url(FOLDER_PATH))?.map { it.name })
    }

    @Test
    fun aListingOverTheSizeCapFailsAsServerError() = runTest {
        server.enqueue(MockResponse.Builder().code(207).body("x".repeat(MAX_LISTING_BYTES.toInt() + 1)).build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
    }

    @Test
    fun aRedirectedPutFailsAsServerErrorInsteadOfFollowingIt() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/login").build())
        server.enqueue(MockResponse.Builder().code(200).body("<html>Log in</html>").build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().put(FILE, ByteArray(16)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFolderAddressWithoutTheTrailingSlashListsTheSameFolder() = runTest {
        val recorded = RecordedWebDav.enqueue(server, "08-propfind-folder-empty")
        val folder = server.url(FOLDER_PATH.removeSuffix("/"))

        storage(folder = folder).list()

        recorded.single().assertSent(server.takeRequest())
    }

    private fun storage(
        password: String = PASSWORD,
        client: OkHttpClient = OkHttpClient(),
        folder: HttpUrl = server.url(FOLDER_PATH),
    ): WebDavStorage = WebDavStorage(WebDavAccount(folder, USER, password), client, UnconfinedTestDispatcher())

    private fun multistatus(vararg responses: String): String = """<d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>"""

    private fun response(href: String, collection: Boolean = false): String =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>" +
            (if (collection) "<d:collection/>" else "") +
            "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private companion object {
        const val FOLDER_PATH = "/remote.php/dav/files/smoke/Ayn/"
        const val FILE = "0f3c2a9e-device-a.ayn"
        const val USER = "smoke"
        const val PASSWORD = "APP-PASSWORD"
    }
}
