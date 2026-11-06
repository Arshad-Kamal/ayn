package com.arshad.ayn.ui.tv.common

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/** The light of a picture not yet sampled (the master's default `--c`). */
internal val NeutralLight = Color(red = 200, green = 190, blue = 180)

// A sample this small decodes at a large subsample (Coil picks the inSampleSize), so it costs a
// few hundred pixels; callers sample a picture already on screen, so it reads the disk cache.
private const val SampleSize = 24
private const val LightMax = 210
private const val LightMin = 45

/**
 * The colour a focused picture spills as light (Cinema: "the picture lights the room"), sampled
 * off a tiny software decode whenever [url] changes (`null` = not wanted yet); [NeutralLight]
 * until then and when it can't load.
 */
@Composable
internal fun rememberPictureLight(url: String?): State<Color> {
    val context = LocalContext.current
    return produceState(NeutralLight, url) {
        if (url == null) return@produceState
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(SampleSize)
            // Hardware bitmaps can't be read back on the CPU.
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@produceState
        value = pictureLight(result.image.toBitmap())
    }
}

private fun pictureLight(bitmap: Bitmap): Color {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var r = 0L
    var g = 0L
    var b = 0L
    pixels.forEach { pixel ->
        r += pixel shr 16 and 0xFF
        g += pixel shr 8 and 0xFF
        b += pixel and 0xFF
    }
    val n = pixels.size.coerceAtLeast(1)
    return lightFromAverage((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/**
 * A picture's average colour brightened to a light: its strongest channel raised to 210, every
 * channel kept within 45..210, as the mockups derive `--c` ("54 76 210" for a blue picture,
 * "210 210 210" for a grey one).
 */
internal fun lightFromAverage(red: Int, green: Int, blue: Int): Color {
    val strongest = maxOf(red, green, blue)
    if (strongest == 0) return Color(LightMax, LightMax, LightMax)
    fun lift(channel: Int): Int = (channel * LightMax / strongest).coerceIn(LightMin, LightMax)
    return Color(lift(red), lift(green), lift(blue))
}
