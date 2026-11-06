package com.arshad.ayn.ui.tv.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.focus.ProfileTileSurface
import com.arshad.ayn.ui.tv.focus.drawKeyline
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight

// `.pick` (px ÷ 2): the block starts 118 dp down; tiles 130 dp, 36 dp apart, 55 dp under the title.
private val PickTop = 118.dp
private val TileGap = 36.dp
private val TilesTop = 55.dp
private val TileShape = RoundedCornerShape(TilePlate.corner)
private val NameRise = 7.dp
private val TagShape = RoundedCornerShape(3.5.dp)
private const val TagKeylineAlpha = 0.6f

/**
 * "Who's watching?" (`b3-profiles` picker): one tile per profile, focus starting on the
 * last-used one; the focused tile lights the room in its tint and the others dim. A kids profile's
 * name carries the Kids tag (`kp-kids` picker). BACK leaves KE,
 * except over a profile opened from the rail ([GateScreen.Picker.canReturn]), where it returns.
 */
@Composable
internal fun PickerScreen(
    picker: GateScreen.Picker,
    profiles: List<Profile>,
    onPick: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = picker.canReturn, onBack = onBack)
    val requesters = remember { mutableMapOf<Long, FocusRequester>() }
    val room = remember { FocusedCardLight() }
    var tilesFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        (requesters[picker.focusId] ?: profiles.firstOrNull()?.let { requesters[it.id] })?.requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        CardRoomLight(room, lit = tilesFocused)
        Column(Modifier.padding(start = KeLayout.ContentStart, top = PickTop)) {
            KickerRow("on this Shield") { KMark() }
            Text("Who’s watching?", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 11.dp))
            Row(
                modifier = Modifier
                    .padding(top = TilesTop)
                    .onFocusChanged { tilesFocused = it.hasFocus }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
            ) {
                profiles.forEach { profile ->
                    key(profile.id) {
                        val requester = remember { FocusRequester() }
                        requesters[profile.id] = requester
                        ProfileTile(
                            profile = profile,
                            dimmed = tilesFocused,
                            onClick = { onPick(profile) },
                            onFocused = room::onCardFocused,
                            modifier = Modifier.focusRequester(requester),
                        )
                    }
                }
            }
        }
    }
}

/** The Kids tag after a kids profile's name (`.ptile .tag`): a keyline in the name's ink. */
@Composable
private fun KidsTag(ink: Color) {
    Text(
        text = "Kids",
        style = MaterialTheme.typography.labelSmall.atWeight(650).copy(fontSize = 10.5.sp),
        color = ink,
        modifier = Modifier
            .drawBehind { drawKeyline(TagShape, 1.dp, ink.copy(alpha = TagKeylineAlpha), 1f) }
            .padding(horizontal = 4.5.dp, vertical = 1.dp),
    )
}

@Composable
private fun ProfileTile(
    profile: Profile,
    dimmed: Boolean,
    onClick: () -> Unit,
    onFocused: (State<Color>, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var centerX by remember { mutableFloatStateOf(Float.NaN) }
    val light = rememberUpdatedState(profile.tint.color)
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.base(), label = "tile")
    val nameColor by animateColorAsState(if (focused) KeColors.Ink else KeColors.Ink2, KeMotion.fast(), label = "name")

    Column(Modifier.width(TilePlate.size), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileTileSurface(
            onClick = onClick,
            light = profile.tint.color,
            shape = TileShape,
            dimmed = { dimmed },
            modifier = modifier
                .size(TilePlate.size)
                .onGloballyPositioned { centerX = it.positionInRoot().x + it.size.width / 2f }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused(light, centerX)
                },
        ) {
            PlateFace(profile.name, profile.tint, TilePlate)
        }
        Row(
            modifier = Modifier
                .padding(top = 13.dp)
                .graphicsLayer { translationY = NameRise.toPx() * progress.value },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(profile.name, style = MaterialTheme.typography.titleLarge.atWeight(600).copy(fontSize = 15.sp), color = nameColor)
            if (profile.isKids) KidsTag(nameColor)
            if (profile.lock != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = "locked",
                    tint = KeColors.Ink3,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
