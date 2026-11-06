package com.arshad.ayn.ui.mobile.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import kotlinx.coroutines.delay

/** How long a notice with an action (Undo, M6-D11) stays, and one without. */
internal const val ACTION_NOTICE_MS: Long = 6_000L
internal const val NOTICE_MS: Long = 4_000L

/** What a notice says: [text] in ink, then [muted]; [action] names its button (Undo, Fix; none
 *  without); [warn] marks a failure. It stays [shownMs]. */
internal class NoticeLine(
    val text: String,
    val muted: String?,
    val action: String? = UNDO,
    val warn: Boolean = false,
    val shownMs: Long = if (action != null) ACTION_NOTICE_MS else NOTICE_MS,
)

private const val UNDO = "Undo"

private val SnackShape = RoundedCornerShape(14.dp)
private val RiseBy = 16.dp

/**
 * A snackbar above the navigation bar (`m6-home-and-menus` `.snack`) while [notice] is set: its
 * action (Undo, Fix) calls [onAction]; its [NoticeLine.shownMs] going by calls [onClose]. It rises
 * in and sinks away (alpha and transform, D8); sinking, the action takes no taps. A notice whose
 * [identity] differs from the one showing gets its own time (by default every notice is one).
 */
@Composable
internal fun <T : Any> NoticeSnackbar(
    notice: T?,
    line: (T) -> NoticeLine,
    onAction: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    identity: (T) -> Any? = { null },
) {
    val currentOnClose by rememberUpdatedState(onClose)
    val last = remember { LastShown<T>() }
    if (notice != null) last.value = notice
    val live = notice != null
    val rise = remember { Animatable(0f) }
    LaunchedEffect(live) { rise.animateTo(if (live) 1f else 0f, AynMotion.base()) }
    val shown = notice ?: last.value?.takeIf { rise.value > 0f } ?: return
    val words = line(shown)
    // Keyed on showing and the notice's identity, not on the notice itself: an updated copy of the
    // same one (Home's ranking landing under a Hidden notice) keeps its time; a different one (a
    // second block in the review) gets its own.
    LaunchedEffect(live, notice?.let(identity)) {
        if (!live) return@LaunchedEffect
        delay(words.shownMs)
        currentOnClose()
    }
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rise.value
                translationY = (1f - rise.value) * RiseBy.toPx()
                shadowElevation = 12.dp.toPx()
                shape = SnackShape
                clip = true
            }
            .background(AynColors.Surface3)
            .heightIn(min = 52.dp)
            .padding(start = 18.dp, end = if (words.action != null) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (words.warn) {
            Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = AynColors.ErrorInk, modifier = Modifier.size(18.dp))
        }
        Text(
            text = buildAnnotatedString {
                withStyle(weightSpan(650, AynColors.Ink)) { append(words.text) }
                words.muted?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.bodySmall.lineHeight),
            color = AynColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        words.action?.let { action ->
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AynColors.Tonal.copy(alpha = 0.12f))
                    .clickable(enabled = live, role = Role.Button, onClick = onAction)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, style = MaterialTheme.typography.labelMedium.atWeight(700), color = AynColors.Ink)
            }
        }
    }
}

/** Calls [onScroll] whenever the person scrolls something inside (a notice goes on the next scroll). */
@Composable
internal fun Modifier.onUserScroll(onScroll: () -> Unit): Modifier {
    val current by rememberUpdatedState(onScroll)
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) current()
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/** What a closing overlay (a snackbar, the menu's sheet) still shows while it leaves; written as
 *  one shows, never read for invalidation. */
internal class LastShown<T : Any> {
    var value: T? = null
}
