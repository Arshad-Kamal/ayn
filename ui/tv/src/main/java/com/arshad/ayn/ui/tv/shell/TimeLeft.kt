package com.arshad.ayn.ui.tv.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.home.KidsPicks
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Toast
import com.arshad.ayn.feature.kids.timeLeftText
import com.arshad.ayn.feature.kids.warningText
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.weightSpan
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/** What a kids profile's shell reads (KP-D8, D10, D11): its watch time and warnings ([clock]),
 *  its Parent page's ViewModels ([parent]) and, for a kid's id, the owner's [picks] for its Home. */
internal class KidsShell(val clock: KidsClock, val parent: ParentModels, val picks: (kidId: Long) -> Flow<KidsPicks>)

/** Builds the Parent page's ViewModels: [page] for the kid's id and how it leaves the kid's
 *  profile with the owner's pass, [review] for the session's kid (KP-D10, F12). */
internal class ParentModels(
    val page: (kidId: Long, leave: (OwnerPass) -> Unit) -> ParentViewModel,
    val review: () -> ParentReviewViewModel,
)

/**
 * A time warning (`kp-kids` warn5, warn1): a toast at the bottom end with a clock in the kid's
 * [tint], over whatever shows (the player's picture included); it takes no focus. Up while
 * [minutes] is set; it keeps its last line as it sinks away.
 */
@Composable
internal fun TimeWarning(minutes: Int?, tint: Color, modifier: Modifier = Modifier) {
    val shown = remember { LastWarning() }
    minutes?.let { shown.minutes = it }
    shown.minutes?.let { Toast(minutes != null, R.drawable.ic_later, warningText(it), muted = null, modifier = modifier, iconTint = tint) }
}

// Written while the toast shows so it can sink with its last line; no composition reads it for invalidation.
private class LastWarning {
    var minutes: Int? = null
}

/** The time left today (`kp-kids` kidhome `.timeleft`): a clock in the kid's [tint], the time in
 *  bold, "left today" after it. */
@Composable
internal fun TimeLeftChip(left: Duration, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(KeColors.Surface2, CircleShape)
            .padding(start = 11.dp, end = 13.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_later), contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(weightSpan(700, KeColors.Ink)) { append(timeLeftText(left)) }
                append(" left today")
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = KeColors.Ink2,
            maxLines = 1,
        )
    }
}
