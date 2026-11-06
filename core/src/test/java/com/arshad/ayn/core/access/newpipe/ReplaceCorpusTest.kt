package com.arshad.ayn.core.access.newpipe

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The recorder's corpus swap, checked offline (the recorder itself needs live YouTube). */
class ReplaceCorpusTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aCompletedRecordingBecomesTheWholeCorpus() {
        val corpus = temp.root.toPath().resolve("extract-fixtures")
        write(corpus.resolve("search/old.body"), "old")
        write(corpus.resolve("orphan/gone.body"), "orphan")
        val recording = temp.root.toPath().resolve("extract-fixtures-recording")
        write(recording.resolve("search/new.body"), "new")

        replaceCorpus(recording, corpus)

        assertEquals(listOf("search/new.body"), filesUnder(corpus))
        assertEquals("new", Files.readString(corpus.resolve("search/new.body")))
        assertFalse(Files.exists(recording))
        assertFalse(Files.exists(corpus.resolveSibling("extract-fixtures-previous")))
    }

    @Test
    fun aBackupLeftByAnInterruptedSwapDoesNotBlockTheNextOne() {
        val corpus = temp.root.toPath().resolve("extract-fixtures")
        write(corpus.resolve("search/old.body"), "old")
        write(corpus.resolveSibling("extract-fixtures-previous").resolve("search/stale.body"), "stale")
        val recording = temp.root.toPath().resolve("extract-fixtures-recording")
        write(recording.resolve("search/new.body"), "new")

        replaceCorpus(recording, corpus)

        assertEquals(listOf("search/new.body"), filesUnder(corpus))
        assertFalse(Files.exists(corpus.resolveSibling("extract-fixtures-previous")))
    }

    private fun write(file: Path, text: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun filesUnder(dir: Path): List<String> = Files.walk(dir).use { paths ->
        paths.filter(Files::isRegularFile).map { dir.relativize(it).toString() }.sorted().toList()
    }
}
