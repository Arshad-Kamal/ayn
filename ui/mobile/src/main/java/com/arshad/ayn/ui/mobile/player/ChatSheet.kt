package com.arshad.ayn.ui.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.settings.MiniChat
import com.arshad.ayn.ui.mobile.settings.MiniPicture
import com.arshad.ayn.ui.mobile.settings.PlacementMini
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

/** The Chat sheet's subject and asks (LIVE-D21, D26): [kicker] names the channel and its
 *  watchers (an ended stream's views and when it streamed); the stream's [chat] and its view ([placement]). */
internal class ChatSheetCalls(
    val kicker: String,
    val chat: PlayerChat?,
    val placement: ChatPlacement,
    val chooseView: (live: Boolean) -> Unit,
    val choosePlacement: (ChatPlacement) -> Unit,
    val hide: () -> Unit,
)

// `.pvbody` 18 dp sides (the stage's frame gives 6), `.pvh` 10 dp above and 6 below, `.pvgrid`
// 6 dp gaps, `.pvopt` 6/6/7 dp inside and 5 between the drawing and its name, `.pvhide` 10 dp above.
private val StageSide = 12.dp
private val GridGap = 6.dp
private val CardShape = RoundedCornerShape(10.dp)
private const val GRID_COLUMNS = 3
private val MiniOnPicture = AynColors.OnInk.copy(alpha = 0.18f)
private val MiniOnChat = AynColors.OnInk.copy(alpha = 0.7f)

/**
 * The Chat sheet (`live-phone-player` 19–20): Show (Top chat / Live chat, once YouTube's answer
 * names both, as the panel's), View (the six, each drawn small; a tap moves chat there for this
 * stream), then Hide chat; a view picked or Hide chat closes the sheets ([onDone]), so the change
 * shows at once. No Scroll chat: over the picture a drag scrolls it (LIVE-D22).
 */
@Composable
internal fun ColumnScope.ChatSheet(calls: ChatSheetCalls, onDone: () -> Unit) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = StageSide)) {
        AnimatedVisibility(calls.chat?.switchShown == true, enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
            Column {
                SectionHeading("Show")
                ChatSwitch(live = calls.chat?.liveView == true, onPick = calls.chooseView)
            }
        }
        SectionHeading("View")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(GridGap)) {
            ChatPlacement.entries.chunked(GRID_COLUMNS).forEach { row ->
                // As tall as its tallest card, so a name on two lines doesn't stagger the row.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    row.forEach { placement ->
                        ViewCard(
                            placement,
                            on = placement == calls.placement,
                            onClick = {
                                calls.choosePlacement(placement)
                                onDone()
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        PillButton(
            "Hide chat",
            {
                calls.hide()
                onDone()
            },
            Modifier.padding(top = 10.dp).height(36.dp),
            kind = ButtonKind.Tonal,
            icon = R.drawable.ic_chat_off,
            small = true,
        )
    }
}

/** A heading in the sheet (`.pvh`). */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp).atWeight(620),
        color = AynColors.Ink3,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** One view (`.pvopt`): [placement] drawn small over its name; the view showing ([on]) is filled in ink. */
@Composable
private fun ViewCard(placement: ChatPlacement, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lit by animateFloatAsState(if (on) 1f else 0f, AynMotion.base(), label = "viewOn")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(interaction)
            .clip(CardShape)
            .drawBehind { drawRect(lerp(AynColors.Surface2, AynColors.Ink, lit)) }
            .selectable(selected = on, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PlacementMini(
            placement,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            picture = lerp(MiniPicture, MiniOnPicture, lit),
            chat = lerp(MiniChat, MiniOnChat, lit),
        )
        Text(
            placement.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp).atWeight(600),
            color = lerp(AynColors.Ink2, AynColors.OnInk, lit),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
