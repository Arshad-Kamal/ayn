package com.arshad.ayn.core.access.potoken

import com.github.libretube.api.poToken.PoTokenException
import com.github.libretube.api.poToken.parseHomepageChallenge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BotGuard challenge read from a recorded YouTube homepage (trimmed, see the fixture's
 * header). Expected values were decoded from the recording independently (Python, 2026-10-23).
 */
class HomepageChallengeTest {

    private val homepage = javaClass.classLoader!!.getResource("youtube-homepage-attestation.html")!!.readText()

    @Test
    fun theRecordedHomepageYieldsItsChallengeAndEventId() {
        val challenge = parseHomepageChallenge(homepage)

        assertEquals("s6TbaoSlOaG-y-8Phv_68AE", challenge.eventId)
        assertEquals("trayride", challenge.globalName)
        assertEquals(
            "https://www.google.com/js/th/WqKOXfWIFxB-XN3hBm5zepers2HYXoMPp6sS78jI_4Y.js",
            challenge.interpreterUrl,
        )
        assertEquals(39_783, challenge.program.length)
        assertTrue(challenge.program.startsWith("lol4qbqnX6zVWtT+w96zaYEL"))
        assertTrue(challenge.program.endsWith("OC9OCfdFs96k"))
    }

    @Test
    fun theScriptLiteralsCarryTheInterpreterProgramAndEventId() {
        val challenge = parseHomepageChallenge(homepage)

        val challengeData = Json.parseToJsonElement(challenge.runBotGuardArgument("var vm = 1;")).jsonObject
        assertEquals(
            "var vm = 1;",
            challengeData.getValue("interpreterJavascript").jsonObject
                .getValue("privateDoNotAccessOrElseSafeScriptWrappedValue").jsonPrimitive.content,
        )
        assertEquals("trayride", challengeData.getValue("globalName").jsonPrimitive.content)
        assertEquals("""{"EVENT_ID":"s6TbaoSlOaG-y-8Phv_68AE"}""", challenge.ytConfig)
    }

    @Test
    fun aPageWithoutTheAttestationCallIsRefused() {
        val page = homepage.replace("window.ytAtN({", "window.other({")

        val error = assertThrows(PoTokenException::class.java) { parseHomepageChallenge(page) }

        assertEquals("homepage has no attestation challenge", error.message)
    }

    @Test
    fun anInterpreterOffGooglesHttpsHostIsRefused() {
        val offHost = listOf(
            "\\/\\/www.example.com", // another host
            "http:\\/\\/www.google.com", // not https
            "\\/\\/www.google.com.example.com", // a lookalike suffix
            "\\/\\/www.google.com@example.com", // userinfo before the real host
        )
        for (url in offHost) {
            val page = homepage.replace("\\/\\/www.google.com", url)

            val error = assertThrows(url, PoTokenException::class.java) { parseHomepageChallenge(page) }

            assertEquals(url, "BotGuard interpreter off its host", error.message)
        }
    }

    @Test
    fun aNonHexEscapeIsAMalformedChallenge() {
        val page = homepage.replaceFirst("\\x7b\\x22bgChallenge", "\\x7")

        val error = assertThrows(PoTokenException::class.java) { parseHomepageChallenge(page) }

        assertEquals("malformed attestation challenge", error.message)
    }

    @Test
    fun anEscapeCutByTheStringsEndIsAMalformedChallenge() {
        val page = homepage.replace("\\x7d\\x7d','T'", "\\x7d\\x7','T'")

        val error = assertThrows(PoTokenException::class.java) { parseHomepageChallenge(page) }

        assertEquals("malformed attestation challenge", error.message)
    }

    @Test
    fun aMissingChallengeFieldIsAMalformedChallenge() {
        val page = homepage.replace("\\x22program\\x22", "\\x22programme\\x22")

        val error = assertThrows(PoTokenException::class.java) { parseHomepageChallenge(page) }

        assertEquals("malformed attestation challenge", error.message)
    }
}
