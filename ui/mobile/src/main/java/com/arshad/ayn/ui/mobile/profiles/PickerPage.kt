package com.arshad.ayn.ui.mobile.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.AynMark
import com.arshad.ayn.ui.mobile.common.Kicker
import com.arshad.ayn.ui.mobile.common.floorGlow
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

private const val PressedScale = 0.96f
private val TileGap = 28.dp
private val RowGap = 26.dp

/**
 * "Who's watching?" (`m6-shell-and-profiles` picker): two tiles a row; the floor glows in the
 * tint of the profile the picker starts on ([GateScreen.Picker.focusId], the last used). A tile
 * sinks and spills its tint while pressed. A kids profile's name carries the Kids tag. Back
 * leaves Ayn, except over a profile opened from the shell ([GateScreen.Picker.canReturn]).
 * [footer] sits under the tiles, at the foot (a kept video, state 8).
 */
@Composable
internal fun PickerPage(
    picker: GateScreen.Picker,
    profiles: List<Profile>,
    onPick: (Profile) -> Unit,
    onBack: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    BackHandler(enabled = picker.canReturn, onBack = onBack)
    val lit = (profiles.firstOrNull { it.id == picker.focusId } ?: profiles.firstOrNull())?.tint?.color ?: AynColors.Ink
    Column(Modifier.fillMaxSize().floorGlow { lit }.safeDrawingPadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 100.dp, bottom = 32.dp)
                .risingIn(),
        ) {
            Column(Modifier.padding(horizontal = 28.dp)) {
                Kicker("on this phone") { AynMark(size = 22.dp) }
                Text("Who’s watching?", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 10.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                profiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                        pair.forEach { profile -> PickerTileButton(profile, onClick = { onPick(profile) }) }
                        if (pair.size == 1) Spacer(Modifier.width(PickerTile.size))
                    }
                }
            }
        }
        footer()
    }
}

@Composable
private fun PickerTileButton(profile: Profile, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, AynMotion.press(), label = "tilePress")
    val nameColor by animateColorAsState(if (pressed) AynColors.Ink else AynColors.Ink2, AynMotion.press(), label = "name")
    val tint = profile.tint.color
    Column(
        modifier = Modifier
            .width(PickerTile.size)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(PickerTile.size)
                .drawBehind { pressLight(tint, press) }
                .graphicsLayer {
                    val scale = 1f - (1f - PressedScale) * press
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            ProfileTile(profile.name, profile.tint, PickerTile)
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, color = nameColor)
            if (profile.isKids) KidsTag(nameColor)
            if (profile.lock != null) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = "locked", tint = AynColors.Ink3, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** While pressed: the tint spills below the tile and an ink ring stands off it (`.pt.pressed`). */
private fun DrawScope.pressLight(tint: Color, press: Float) {
    if (press <= 0f) return
    val spill = size.width * 0.75f
    val center = Offset(size.width / 2, size.height * 0.75f)
    drawCircle(Brush.radialGradient(0f to tint.copy(alpha = 0.6f * press), 1f to Color.Transparent, center = center, radius = spill), spill, center)
    val gap = 3.dp.toPx()
    val ring = 2.dp.toPx()
    val inset = gap + ring / 2
    val corner = PickerTile.corner.toPx() + inset
    drawRoundRect(
        color = AynColors.Ink.copy(alpha = press),
        topLeft = Offset(-inset, -inset),
        size = Size(size.width + 2 * inset, size.height + 2 * inset),
        cornerRadius = CornerRadius(corner),
        style = Stroke(ring),
    )
}

/** The Kids tag after a kids profile's name: a keyline in the name's ink. */
@Composable
private fun KidsTag(ink: Color) {
    Text(
        text = "Kids",
        style = MaterialTheme.typography.labelSmall.atWeight(650).copy(fontSize = 11.sp),
        color = ink,
        modifier = Modifier
            .drawBehind {
                drawRoundRect(ink.copy(alpha = 0.6f), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
