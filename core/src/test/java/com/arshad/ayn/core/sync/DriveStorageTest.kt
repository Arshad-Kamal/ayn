package com.arshad.ayn.core.sync

import java.time.Instant
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [DriveStorage] against Google Drive's recorded answers ([RecordedDrive]), each request checked
 *  against its recording. */
class DriveStorageTest {
    private val server = MockWebServer().apply { start() }
    private val io = UnconfinedTestDispatcher()

    @After
    fun stopServer() = server.close()

    @Test
    fun listWithoutTheFolderFailsAsFolderMissing() = runTest {
        val recorded = RecordedDrive.enqueue(server, "04-find-folder-none").single()

        assertStorageFailure(StorageFailure.FOLDER_MISSING) { storage().list() }
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun createFolderMakesAynSyncInMyDrive() = runTest {
        val recorded = RecordedDrive.enqueue(server, "04-find-folder-none", "05-create-folder")

        storage().createFolder()

        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun createFolderWhenItIsThereMakesNone() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder")

        storage().createFolder()

        assertEquals(1, server.requestCount)
    }

    /** The runner's setup on one storage: list, find no folder, make it, list again. */
    @Test
    fun setupMakesTheMissingFolderThenListsItEmpty() = runTest {
        val recorded = RecordedDrive.enqueue(server, "04-find-folder-none", "04-find-folder-none", "05-create-folder", "07-list-empty")
        val storage = storage()

        assertStorageFailure(StorageFailure.FOLDER_MISSING) { storage.list() }
        storage.createFolder()

        assertEquals(emptyList<RemoteFile>(), storage.list())
        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun getWithoutTheFolderIsNull() = runTest {
        RecordedDrive.enqueue(server, "04-find-folder-none")

        assertNull(storage().get("smoke-device.ayn"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun deleteWithoutTheFolderCountsAsDone() = runTest {
        RecordedDrive.enqueue(server, "04-find-folder-none")

        storage().delete("smoke-device.ayn")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun listGivesEachFileWithChecksumTimeAndSize() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "10-list-two")

        val files = storage().list()

        val expected = listOf(
            RemoteFile("smoke-big.ayn", "b2802cb55399f7a17f1ba2b9c3dd2b8a", millis("2026-11-03T00:31:14.524Z"), 5_000_000),
            RemoteFile("smoke-device.ayn", "658b2ab69b6cac4754266667fb4a2767", millis("2026-11-03T00:31:12.476Z"), 47),
        )
        assertEquals(expected, files)
        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun putOfANewNameUploadsItIntoTheFolder() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "07-list-empty", "08-upload-small")

        storage().put("smoke-device.ayn", SMALL)

        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun putOfFiveMegabytesSendsThemWhole() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "07-list-empty", "09-upload-5mb")
        val content = RecordedWebDav.content(5_000_000)

        storage().put("smoke-big.ayn", content)

        server.takeRequest()
        server.takeRequest()
        val upload = server.takeRequest()
        recorded[2].assertSent(upload)
        assertEquals(content.toByteString(), RecordedDrive.partsOf(upload)[1].content)
    }

    @Test
    fun getGivesTheFilesContent() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "10-list-two", "11-get-small", "12-get-5mb")
        val storage = storage()

        assertArrayEquals(SMALL, storage.get("smoke-device.ayn"))
        assertArrayEquals(RecordedWebDav.content(5_000_000), storage.get("smoke-big.ayn"))
        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun putOfAListedNameOverwritesItInPlace() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "10-list-two", "13-overwrite-small", "14-get-overwritten")
        val storage = storage()
        val v2 = "AYN-SYNC-SMOKE v2 68c356267303c5d1a66cd4954dfacdb3".encodeToByteArray()

        storage.put("smoke-device.ayn", v2)

        assertArrayEquals(v2, storage.get("smoke-device.ayn"))
        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun putOverAFileGoneMeanwhileUploadsItNew() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder", "15-list-after-overwrite")
        // Drive's 404 for a file id that's gone, recorded for a GET, stands in for the PATCH's.
        server.enqueue(RecordedDrive("17-get-deleted").response())
        val upload = RecordedDrive.enqueue(server, "08-upload-small").single()

        storage().put("smoke-device.ayn", SMALL)

        repeat(2) { server.takeRequest() }
        val overwrite = server.takeRequest()
        assertEquals("PATCH", overwrite.method)
        assertEquals("/upload/drive/v3/files/FILE-ID-08", overwrite.url.encodedPath)
        upload.assertSent(server.takeRequest())
    }

    @Test
    fun getOfAFileGoneMeanwhileIsNull() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "10-list-two", "17-get-deleted")

        assertNull(storage().get("smoke-big.ayn"))
        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun getOfANameNotListedIsNullWithoutAsking() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder", "07-list-empty")

        assertNull(storage().get("smoke-device.ayn"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun deleteRemovesTheFile() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "10-list-two", "16-delete-5mb")

        storage().delete("smoke-big.ayn")

        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun deleteOfAFileGoneMeanwhileCountsAsDone() = runTest {
        val recorded = RecordedDrive.enqueue(server, "06-find-folder", "10-list-two", "18-delete-deleted")

        storage().delete("smoke-big.ayn")

        recorded.forEach { it.assertSent(server.takeRequest()) }
    }

    @Test
    fun aRefusedAccessTokenFailsAsSignInRejected() = runTest {
        val recorded = RecordedDrive.enqueue(server, "27-list-revoked-after-0s").single()

        assertStorageFailure(StorageFailure.SIGN_IN_REJECTED) { storage().list() }
        recorded.assertSent(server.takeRequest())
    }

    @Test
    fun aRevokedSignInFailsAsSignInRejectedBeforeAnyDriveCall() = runTest {
        RecordedDrive.enqueue(server, "26-refresh-revoked")

        assertStorageFailure(StorageFailure.SIGN_IN_REJECTED) { storage(refreshing()).list() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oneStorageRefreshesOnceAndSendsThatToken() = runTest {
        RecordedDrive.enqueue(server, "20-refresh", "06-find-folder", "22-list-empty-again", "22-list-empty-again")
        val storage = storage(refreshing())

        storage.list()
        storage.list()

        assertEquals("/token", server.takeRequest().url.encodedPath)
        repeat(3) { assertEquals("Bearer ACCESS-TOKEN-2", server.takeRequest().headers["Authorization"]) }
    }

    // Not recorded (the smoke couldn't fill a Drive): Drive's documented error shape.
    @Test
    fun aFullDriveFailsAsStorageFull() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder", "07-list-empty")
        server.enqueue(driveError(403, "storageQuotaExceeded"))

        assertStorageFailure(StorageFailure.STORAGE_FULL) { storage().put("smoke-device.ayn", SMALL) }
    }

    @Test
    fun aRateLimitFailsAsServerError() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder")
        server.enqueue(driveError(403, "userRateLimitExceeded"))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
    }

    @Test
    fun severalFoldersResolveToTheSmallestId() = runTest {
        server.enqueue(json("""{"files":[{"id":"folder-b","name":"Ayn sync"},{"id":"folder-a","name":"Ayn sync"}]}"""))
        RecordedDrive.enqueue(server, "07-list-empty")

        storage().list()

        server.takeRequest()
        assertEquals("'folder-a' in parents and trashed = false", server.takeRequest().url.queryParameter("q"))
    }

    @Test
    fun aFolderIdOutsideDrivesAlphabetIsRefused() = runTest {
        server.enqueue(json("""{"files":[{"id":"x' or name = 'y","name":"Ayn sync"}]}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aNameListedTwiceListsItsNewestCopyAndDeletesBoth() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder")
        server.enqueue(
            json(
                """{"files":[
                {"id":"new","name":"a.ayn","md5Checksum":"2","modifiedTime":"2026-11-03T01:00:00Z","size":"2"},
                {"id":"old","name":"a.ayn","md5Checksum":"1","modifiedTime":"2026-11-03T00:00:00Z","size":"1"}]}""",
            ),
        )
        repeat(2) { server.enqueue(MockResponse.Builder().code(204).build()) }
        val storage = storage()

        assertEquals(listOf("2"), storage.list().map { it.etag })
        storage.delete("a.ayn")

        repeat(2) { server.takeRequest() }
        assertEquals("/drive/v3/files/new", server.takeRequest().url.encodedPath)
        assertEquals("/drive/v3/files/old", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aListingOverTheSizeCapFailsAsServerError() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder")
        server.enqueue(json("""{"files":[],"pad":"${"x".repeat(MAX_LISTING_BYTES.toInt())}"}"""))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
    }

    @Test
    fun aListingThatIsNotJsonFailsAsServerError() = runTest {
        RecordedDrive.enqueue(server, "06-find-folder")
        server.enqueue(json("<html>Sign in</html>"))

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
    }

    @Test
    fun aRedirectIsNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere")).build())

        assertStorageFailure(StorageFailure.SERVER_ERROR) { storage().list() }
        assertEquals(1, server.requestCount)
    }

    private fun storage(newAccessToken: suspend () -> String = { "ACCESS-TOKEN" }): DriveStorage =
        DriveStorage(newAccessToken, OkHttpClient(), RecordedDrive.endpoints(server), io)

    private fun refreshing(): suspend () -> String {
        val login = GoogleLogin(OkHttpClient(), GoogleClient("CLIENT-ID.apps.googleusercontent.com", "CLIENT-SECRET"), RecordedDrive.endpoints(server), io)
        return { login.accessToken("REFRESH-TOKEN") }
    }

    private fun json(body: String): MockResponse = MockResponse.Builder().addHeader("Content-Type", "application/json; charset=UTF-8").body(body).build()

    private fun driveError(code: Int, reason: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=UTF-8")
        .body("""{"error":{"code":$code,"message":"m","errors":[{"message":"m","domain":"usageLimits","reason":"$reason"}]}}""")
        .build()

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

    private companion object {
        val SMALL = "AYN-SYNC-SMOKE 95cd68af075885d30ec97573fbd72925".encodeToByteArray()
    }
}
