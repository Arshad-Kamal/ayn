package com.arshad.ayn.ui.mobile.profiles

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight

/** What [player] holds, kept current by its events; `null` while it holds nothing. */
@Composable
internal fun rememberHeld(player: Player): MediaMetadata? {
    var held by remember(player) { mutableStateOf(player.heldMetadata()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                held = player.heldMetadata()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return held
}

private fun Player.heldMetadata(): MediaMetadata? = mediaMetadata.takeIf { currentMediaItem != null }

/**
 * The video a phone keeps playing under the gate (`m6-shell-and-profiles` 7–8, M6-D8): its picture
 * and title under [label], and its own play/pause.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun KeptStrip(player: Player, held: MediaMetadata, label: String, modifier: Modifier = Modifier) {
    val button = rememberPlayPauseButtonState(player)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AynColors.Surface2)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(8.dp)).background(AynColors.Surface3)) {
            Picture(held.artworkUri?.toString())
        }
        // Polite: a new video read out, not the button's every Play/Pause.
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = AynColors.Ink3, maxLines = 1)
            Text(
                held.title?.toString().orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp).atWeight(620),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = button::onClick, enabled = button.isEnabled) {
            Icon(
                painterResource(if (button.showPlay) R.drawable.ic_play else R.drawable.ic_pause),
                contentDescription = if (button.showPlay) "Play" else "Pause",
                tint = AynColors.Ink,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The picker's strip and its note (state 8): another profile stops the video; a locked kept
 *  profile's own unlock keeps it. */
@Composable
internal fun PickerKeptNote(player: Player, held: MediaMetadata, name: String, locked: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        KeptStrip(player, held, "Playing for $name")
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            val keeps = if (locked) " $name’s unlock keeps it playing." else ""
            Text("Choosing another profile stops it.$keeps", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = AynColors.Ink2)
        }
    }
}
