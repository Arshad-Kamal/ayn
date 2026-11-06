package com.arshad.ayn.core.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DownloadsBackupFiles] against a real MediaStore (M5-D14): the platform behaviour JVM tests
 * can't fake. Run on an emulator or test phone: `./gradlew :core:connectedDebugAndroidTest`.
 * Writes to the device's Downloads and removes what it wrote.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsBackupFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = DownloadsBackupFiles(context)
    private val stem = "${BACKUP_NAME_PREFIX}smoke-${System.nanoTime()}"
    private val name = "$stem.json"
    private val bytes = """{"smoke":true}""".encodeToByteArray()

    private val downloads: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @After
    fun removeWhatTheTestWrote() {
        rowIds(includePending = true).forEach { context.contentResolver.delete(ContentUris.withAppendedId(downloads, it), null, null) }
    }

    @Test
    fun aSavedBackupIsListedAndReadsBackWhole() = runBlocking {
        assertEquals(name, files.save(name, bytes) { it.contentEquals(bytes) })

        val listed = files.list().single { it.fileName.startsWith(stem) }
        assertEquals(name, listed.fileName)
        assertTrue(abs(listed.savedAtMillis - System.currentTimeMillis()) < 60_000)
        assertArrayEquals(bytes, (files.read(listed.uri, maxBytes = 1_024) as FileRead.Bytes).bytes)
    }

    @Test
    fun aSecondBackupTheSameDayGetsANumberedNameAndListsFirst() = runBlocking {
        files.save(name, bytes) { true }

        assertEquals("$stem (1).json", files.save(name, bytes) { true })
        assertEquals(listOf("$stem (1).json", name), files.list().map { it.fileName }.filter { it.startsWith(stem) })
    }

    @Test
    fun aBackupStaysHiddenWhileCheckedAndOneThatFailsIsDeleted() = runBlocking {
        var listedWhileChecked = -1
        var storedWhileChecked = -1

        val saved = files.save(name, bytes) {
            listedWhileChecked = rowIds(includePending = false).size
            storedWhileChecked = rowIds(includePending = true).size
            false
        }

        assertNull(saved)
        assertEquals(0, listedWhileChecked)
        assertEquals(1, storedWhileChecked)
        assertTrue(rowIds(includePending = true).isEmpty())
    }

    @Test
    fun aDeletedBackupLeavesTheListAndItsFileGoes() = runBlocking {
        files.save(name, bytes) { true }
        val uri = files.list().single { it.fileName.startsWith(stem) }.uri

        assertTrue(files.delete(uri))
        assertTrue(files.list().none { it.fileName.startsWith(stem) })
        assertTrue(rowIds(includePending = true).isEmpty())
        assertFalse(files.delete(uri))
    }

    @Test
    fun onlyTheListedRowUriDeletesABackup() = runBlocking {
        files.save(name, bytes) { true }
        val listed = files.list().single { it.fileName.startsWith(stem) }
        val id = ContentUris.parseId(listed.uri)

        assertFalse(files.delete(Uri.parse("$downloads/")))
        assertFalse(files.delete(ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)))
        assertEquals(listOf(listed.fileName), files.list().map { it.fileName }.filter { it.startsWith(stem) })
    }

    @Test
    fun aDownloadsFileThatIsntABackupIsNeverDeleted() = runBlocking {
        val other = "other-${System.nanoTime()}.json"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, other)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = checkNotNull(context.contentResolver.insert(downloads, values))
        try {
            assertFalse(files.delete(uri))
            assertFalse(files.delete(Uri.parse("content://com.example.picked/document/1")))
            assertEquals(1, context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count })
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun aFileLongerThanTheLimitIsTooLarge() = runBlocking {
        files.save(name, bytes) { true }
        val uri = files.list().single { it.fileName.startsWith(stem) }.uri

        assertEquals(FileRead.TooLarge, files.read(uri, maxBytes = bytes.size - 1))
        assertTrue(files.read(uri, maxBytes = bytes.size) is FileRead.Bytes)
    }

    /** This test's rows in Downloads, pending ones too when [includePending]. */
    private fun rowIds(includePending: Boolean): List<Long> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$stem%"))
            if (includePending) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return context.contentResolver.query(downloads, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }.orEmpty()
    }
}
