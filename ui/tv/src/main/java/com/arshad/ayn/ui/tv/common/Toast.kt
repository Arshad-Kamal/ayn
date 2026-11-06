package com.arshad.ayn.ui.tv.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.weightSpan
import kotlinx.coroutines.delay

// The kit's `.toast`: bottom right on the safe lines, oklch 20% 0.01 65, rising 9 dp.
private val ToastFill = Color(0xFF191511)

/** How long a toast stays up. */
internal const val TOAST_MS: Long = 4_000L

/** A toast with an action (the player's resumed toast and skip note) stays longer (Q25 of M5). */
internal const val ACTION_TOAST_MS: Long = 6_000L

/**
 * A toast (`.toast.solo`): [text], then [muted] after [separator] (a dot) in a lighter weight,
 * behind [icon] when given ([warn] tints it, for one that reports a failure; [iconTint] gives it
 * another tint), and [action] at its end (`.toast` with a button, which sits closer to the edge).
 * It rises in while [visible] and sinks away after; the caller places it (bottom end of the screen).
 */
@Composable
internal fun Toast(
    visible: Boolean,
    @DrawableRes icon: Int?,
    text: String,
    muted: String?,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    iconTint: Color = if (warn) KeColors.Warn else KeColors.Ink,
    separator: String = MUTED_DOT,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    // From 0 even when it first composes visible, so every toast rises in (D8).
    val shown = remember { Animatable(0f) }
    LaunchedEffect(visible) { shown.animateTo(if (visible) 1f else 0f, tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) }
    if (!visible && shown.value == 0f) return
    ToastPill(
        text = text,
        muted = muted,
        separator = separator,
        modifier = modifier.rising(shown.asState()),
        lead = icon?.let {
            { Icon(painterResource(it), contentDescription = null, tint = iconTint, modifier = Modifier.size(15.dp)) }
        },
        action = action,
    )
}

/**
 * A toast's pill at the safe lines, without its coming and going: [lead] (an icon, or swatches;
 * none in a notice that names its action, `b3-home` fy-hidden), [text], then [muted] after
 * [separator] in a lighter weight, and [action] at its end.
 */
@Composable
internal fun ToastPill(
    text: String,
    muted: String?,
    modifier: Modifier = Modifier,
    lead: (@Composable () -> Unit)?,
    separator: String = MUTED_DOT,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .padding(end = KeLayout.SafeHorizontal, bottom = KeLayout.SafeVertical)
            .dropShadow(CircleShape) {
                radius = 30.dp.toPx()
                spread = (-8).dp.toPx()
                offset = Offset(0f, 10.dp.toPx())
                color = Color.Black
                alpha = 0.8f
            }
            .background(ToastFill, CircleShape)
            .padding(start = 13.dp, end = if (action != null) 8.dp else 15.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        lead?.invoke()
        Text(
            text = buildAnnotatedString {
                append(text)
                if (muted != null) withStyle(weightSpan(450, KeColors.Ink2)) { append("$separator$muted") }
            },
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp),
            color = KeColors.Ink,
        )
        action?.invoke(this)
    }
}

private const val MUTED_DOT = " · "

/** A toast's content. */
internal class ToastLine(@param:DrawableRes val icon: Int, val text: String, val muted: String?, val warn: Boolean = false)

/** The screen's toast: the latest one [show]n stays up for [TOAST_MS] ([ToastHost]), then sinks away. */
internal class Toasts {
    var line: ToastLine? by mutableStateOf(null)
        private set
    var shown: Int by mutableIntStateOf(0)
        private set
    var visible: Boolean by mutableStateOf(false)

    fun show(line: ToastLine) {
        this.line = line
        visible = true
        shown++
    }
}

@Composable
internal fun ToastHost(toasts: Toasts, modifier: Modifier = Modifier) {
    LaunchedEffect(toasts.shown) {
        if (toasts.shown == 0) return@LaunchedEffect
        delay(TOAST_MS)
        toasts.visible = false
    }
    toasts.line?.let { Toast(toasts.visible, it.icon, it.text, it.muted, modifier, warn = it.warn) }
}
