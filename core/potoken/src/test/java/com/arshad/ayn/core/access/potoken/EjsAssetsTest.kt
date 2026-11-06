package com.arshad.ayn.core.access.potoken

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendored yt-dlp EJS 0.8.0 files are the release assets byte for byte (THIRD-PARTY.md entry 5;
 * hashes from the release, spec 12 ledger "yt-dlp EJS solver"), licence banner included.
 */
class EjsAssetsTest {

    @Test
    fun vendoredFilesMatchTheReleaseHashes() {
        assertEquals("c55987fe697e5b9ee18830163f7af85327e9bb5c3e674b969d38c8d205eaa577", sha256("yt.solver.lib.min.js"))
        assertEquals("18da6ce0758b416e7ae645084f4f8801f9f9d59d6c477c05eaa0ff94ebd8cc00", sha256("yt.solver.core.min.js"))
    }

    @Test
    fun libKeepsItsLicenceBanner() {
        val banner = asset("yt.solver.lib.min.js").readText().take(BANNER_CHARS)
        assertTrue(banner.contains("SPDX-License-Identifier: Unlicense"))
        assertTrue(banner.contains("License: ISC") && banner.contains("License: MIT"))
    }

    private fun sha256(name: String): String =
        MessageDigest.getInstance("SHA-256").digest(asset(name).readBytes()).joinToString("") { "%02x".format(it) }

    private fun asset(name: String): File = File("src/main/assets/ejs/$name")

    private companion object {
        const val BANNER_CHARS = 4_000
    }
}
