package com.arshad.ayn.ui.mobile.profiles

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.MonogramStyle

/** The Cinema profile tints (the sandboxes' `--c` values). */
internal val ProfileTint.color: Color
    get() = when (this) {
        ProfileTint.Amber -> Color(red = 224, green = 168, blue = 90)
        ProfileTint.Rose -> Color(red = 219, green = 127, blue = 134)
        ProfileTint.Teal -> Color(red = 95, green = 191, blue = 176)
        ProfileTint.Blue -> Color(red = 110, green = 159, blue = 221)
        ProfileTint.Violet -> Color(red = 166, green = 138, blue = 216)
    }

/** A profile tile's size (`.tile` plate, sm, md, xl, lg): [size] square, [corner] radius, [letter] size. */
internal class TileSize(val size: Dp, val corner: Dp, val letter: TextUnit)

/** A profile beside its row in a sheet (`.lp`). */
internal val PlateTile = TileSize(30.dp, 8.dp, 16.sp)

internal val SmallTile = TileSize(40.dp, 12.dp, 22.sp)
internal val MediumTile = TileSize(64.dp, 18.dp, 36.sp)
internal val LargeTile = TileSize(104.dp, 28.dp, 60.sp)
internal val PickerTile = TileSize(148.dp, 34.dp, 84.sp)

/** The tint falling off to 78% (160°) with a lit top edge, as `.tile`'s gradient and inset light. */
private fun Modifier.tileFace(tint: Color): Modifier = drawWithCache {
    val face = Brush.linearGradient(
        0f to tint,
        1f to lerpToBlack(tint, 0.22f),
        start = Offset(size.width * 0.33f, 0f),
        end = Offset(size.width * 0.67f, size.height),
    )
    onDrawBehind {
        drawRect(face)
        drawRect(Color.White.copy(alpha = 0.25f), size = size.copy(height = 1.dp.toPx()))
    }
}

private fun lerpToBlack(color: Color, amount: Float): Color =
    Color(red = color.red * (1 - amount), green = color.green * (1 - amount), blue = color.blue * (1 - amount))

/** A profile's monogram plate; a tint change cross-fades ([AynMotion.base]). */
@Composable
internal fun ProfileTile(name: String, tint: ProfileTint, tile: TileSize, modifier: Modifier = Modifier) {
    val color by animateColorAsState(tint.color, AynMotion.base(), label = "tint")
    Plate(name, color, RoundedCornerShape(tile.corner), tile.letter, modifier.size(tile.size))
}

/** The profile avatar in the top bar (`.me`): the tint as a circle with the monogram. */
@Composable
internal fun ProfileAvatar(name: String, tint: ProfileTint, modifier: Modifier = Modifier) {
    Plate(name, tint.color, CircleShape, 15.sp, modifier.size(34.dp))
}

@Composable
private fun Plate(name: String, color: Color, shape: Shape, letter: TextUnit, modifier: Modifier) {
    Box(modifier.clip(shape).tileFace(color), contentAlignment = Alignment.Center) {
        // The letter repeats the name every tile stands beside, so TalkBack skips it.
        Text(monogram(name), style = MonogramStyle.copy(fontSize = letter), color = AynColors.Monogram, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** The first letter (code point) of the trimmed name, upper-cased. */
internal fun monogram(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.substring(0, Character.charCount(trimmed.codePointAt(0))).uppercase()
}
