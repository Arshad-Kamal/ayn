package com.arshad.ayn.core.access.potoken

import com.arshad.ayn.core.access.sabr.webPoTokenBytes
import com.github.libretube.api.poToken.u8ToBase64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SABR seams decode the minter's text back to the token bytes. Inputs come from the vendored
 * `u8ToBase64`, the encoder the minting WebView's output goes through.
 */
class StreamingTokenBytesTest {

    @Test
    fun mintedTextDecodesToTheMintedBytes() {
        // 116 bytes leaves base64 padding; values 0..255 reach the url-safe '-' and '_' digits.
        val minted = ByteArray(116) { i -> (i * 251 + 7).toByte() }
        val text = u8ToBase64(minted.joinToString(",") { it.toUByte().toString() })
        assertTrue(text.endsWith("=") && text.any { it == '-' || it == '_' })

        assertArrayEquals(minted, webPoTokenBytes(text))
    }

    @Test
    fun unpaddedTextDecodesLikeThePaddedForm() {
        val minted = ByteArray(116) { i -> (i * 13).toByte() }
        val text = u8ToBase64(minted.joinToString(",") { it.toUByte().toString() })

        assertArrayEquals(minted, webPoTokenBytes(text.trimEnd('=')))
    }

    @Test
    fun textThatIsNotBase64UrlIsNull() {
        assertNull(webPoTokenBytes("not a token!"))
    }
}
