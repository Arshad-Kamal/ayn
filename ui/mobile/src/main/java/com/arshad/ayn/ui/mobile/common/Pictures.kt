package com.arshad.ayn.ui.mobile.common

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.CardFace
import com.arshad.ayn.ui.mobile.profiles.monogram
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.MonogramStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

/**
 * The face [video] shows: at once when it needs no wait, else `null` while DeArrow is waited for
 * (at most 1.5 s, M7-D8), then the face; a change of the profile's DeArrow switches asks again. A
 * DeArrow title the open profile's filter hides shows as YouTube's (KP-D20).
 */
@Composable
internal fun rememberCardFace(video: VideoItem): CardFace? {
    val branding = LocalCardBranding.current ?: return CardFace.original(video)
    var face by remember(branding, video) { mutableStateOf(branding.known(video)) }
    LaunchedEffect(branding, video) {
        // The switches' first read is part of face()'s one wait (M7-D11): only a later change asks again.
        val changes = branding.toggles.filterNotNull().distinctUntilChanged().drop(1)
        merge(flowOf(Unit), changes).collectLatest {
            face = branding.known(video) ?: run {
                face = null
                branding.face(video)
            }
        }
    }
    return face?.checkedBy(LocalContentFilter.current, video)
}

/** The alpha of what a face fills in: 1 when [ready] at first composition; after a wait it fades in
 *  from 0 (alpha only, nothing moves). */
@Composable
internal fun rememberFaceArrival(ready: Boolean): State<Float> {
    val alpha = remember { Animatable(if (ready) 1f else 0f) }
    LaunchedEffect(ready) {
        when {
            !ready -> alpha.snapTo(0f)
            alpha.value < 1f -> alpha.animateTo(1f, AynMotion.base())
        }
    }
    return alpha.asState()
}

/** A picture filling its box (cropped), fading in as it loads; nothing while [url] is `null`.
 *  [onLoaded] hears it land (a light sampled after that reads the cache, not the network). */
@Composable
internal fun Picture(url: String?, modifier: Modifier = Modifier, onLoaded: () -> Unit = {}) {
    if (url == null) return
    val context = LocalContext.current
    AsyncImage(
        model = remember(url, context) { ImageRequest.Builder(context).data(url).crossfade(AynMotion.BASE_MS).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { onLoaded() },
        modifier = modifier.fillMaxSize(),
    )
}

/** The light of a picture not sampled yet (the mockups' default `--c`). */
internal val NeutralLight = Color(red = 200, green = 190, blue = 180)

// A sample this small decodes at a large subsample, so it costs a few hundred pixels; callers
// sample a picture already on screen, so it reads the disk cache.
private const val SampleSize = 24
private const val LightMax = 210
private const val LightMin = 45

/** The colour a pressed picture spills ([url] `null` = not wanted yet); [NeutralLight] until then
 *  and when it can't load. */
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

/** A picture's average colour brightened to a light: its strongest channel raised to 210, every
 *  channel kept within 45..210, as the mockups derive `--c`. */
internal fun lightFromAverage(red: Int, green: Int, blue: Int): Color {
    val strongest = maxOf(red, green, blue)
    if (strongest == 0) return Color(LightMax, LightMax, LightMax)
    fun lift(channel: Int): Int = (channel * LightMax / strongest).coerceIn(LightMin, LightMax)
    return Color(lift(red), lift(green), lift(blue))
}

/** A channel's round picture, its monogram while it has none (or it hasn't loaded). The name shows
 *  beside it wherever it is used, so the monogram isn't read out. */
@Composable
internal fun ChannelAvatar(name: String, url: String?, modifier: Modifier = Modifier, monogramSize: Float = 18f) {
    Box(modifier.fillMaxSize().clip(CircleShape).background(AynColors.Surface3), contentAlignment = Alignment.Center) {
        Text(monogram(name), style = MonogramStyle.copy(fontSize = monogramSize.sp), color = AynColors.Ink2, modifier = Modifier.clearAndSetSemantics { })
        Picture(url)
    }
}
