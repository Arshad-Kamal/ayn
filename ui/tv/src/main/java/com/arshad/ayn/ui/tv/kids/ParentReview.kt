package com.arshad.ayn.ui.tv.kids

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.kids.ParentReviewUiState
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.kids.ReviewDay
import com.arshad.ayn.feature.kids.ReviewUploads
import com.arshad.ayn.feature.kids.NEW_UPLOAD_DAYS
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.PageHead
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.VideoBadge
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusRequesters
import com.arshad.ayn.ui.tv.profiles.color
import com.arshad.ayn.feature.kids.short
import com.arshad.ayn.feature.kids.barTimeText
import com.arshad.ayn.feature.kids.newUploadsAside
import com.arshad.ayn.feature.kids.reviewAside
import com.arshad.ayn.feature.kids.reviewScaleMinutes
import com.arshad.ayn.feature.kids.todayAgainstLimit
import com.arshad.ayn.feature.kids.watchedLine
import com.arshad.ayn.feature.kids.NOTHING_WATCHED_TODAY
import com.arshad.ayn.feature.kids.NO_NEW_UPLOADS_TEXT
import com.arshad.ayn.feature.kids.WATCHED_TODAY_SHOWN
import com.arshad.ayn.feature.kids.moreWatchedTodayText
import com.arshad.ayn.feature.kids.uploadsFailedText
import com.arshad.ayn.feature.kids.uploadsLoadingText
import com.arshad.ayn.feature.kids.uploadsPartText
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.weightSpan

// `.rgrid` (px ÷ 2): the week's column 320 dp, 35 dp before the uploads; 15 dp under the head.
private val WeekColumn = 320.dp
private val ColumnGap = 35.dp
private val GridTop = 15.dp
private val SectionGap = 17.dp

// `.wk .bar2` (px ÷ 2): 26 × 85 dp, 5 dp corners; the limit line 1.5 dp, 3 dp past each side.
private val BarWidth = 26.dp
private val BarHeight = 85.dp
private val BarCorner = 5.dp
private val LimitLine = 1.5.dp
private val LimitOverhang = 3.dp

// `.lrow.sm` (px ÷ 2): a 96 × 54 dp thumbnail, 11 dp before the text.
private val ThumbWidth = 96.dp
private val ThumbHeight = 54.dp
private val ThumbShape = RoundedCornerShape(5.dp)

/**
 * The parent review (`kp-kids` review; F12, KP-D10): the week's watch time against the limits
 * (bars in [kid]'s colour, More time over the limit, the limit a line), what the kid watched
 * today, and the approved channels' new uploads with Keep and Block, which take [parent]'s pass.
 * A kept or blocked video leaves the list and focus moves to the next one's Keep.
 */
@Composable
internal fun ReviewContent(kid: Profile?, state: ParentReviewUiState, review: ParentReviewViewModel, parent: ParentViewModel, time: ShellTime) {
    kid ?: return
    Column(Modifier.fillMaxSize()) {
        PageHead("Review", state.week.firstOrNull()?.let { reviewAside(kid.name, it.date) })
        Row(
            Modifier.padding(start = KeLayout.ContentStart, top = GridTop, end = KeLayout.SafeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
        ) {
            Column(Modifier.width(WeekColumn)) {
                SectionTitle("Time this week", todayAgainstLimit(state.today))
                WeekBars(state.week, kid.tint.color)
                SectionTitle("Watched today", null, Modifier.padding(top = SectionGap))
                WatchedToday(state, time)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val ready = state.uploads as? ReviewUploads.Ready
                SectionTitle("New from ${kid.name}’s channels", ready?.let { newUploadsAside(it.videos.size) })
                Uploads(kid, state.uploads, review, parent, time)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, aside: String?, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            append(title)
            aside?.let { withStyle(weightSpan(450, KeColors.Ink3).copy(fontSize = 12.sp)) { append("   $it") } }
        },
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(bottom = 9.dp),
    )
}

/**
 * A bar per day, Monday first: what was watched up to the day's limit in the kid's colour, the
 * More time given over the limit in a paler one, the limit a line across; today's well ringed.
 */
@Composable
private fun WeekBars(week: List<ReviewDay>, tint: Color) {
    val scale = reviewScaleMinutes(week)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        week.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(BarWidth, BarHeight).drawBehind { drawDay(day, scale, tint) })
                Text(
                    text = if (day.isToday) "Today" else day.date.dayOfWeek.short(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = if (day.isToday) KeColors.Ink else KeColors.Ink2,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(barTimeText(day.usedSeconds), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = KeColors.Ink3)
            }
        }
    }
    Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendKey("Watched", tint.copy(alpha = 0.85f), 7.dp)
        LegendKey("More time you added", tint.copy(alpha = 0.45f), 7.dp)
        LegendKey("Limit", KeColors.Ink, LimitLine)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDay(day: ReviewDay, scale: Int, tint: Color) {
    val corner = CornerRadius(BarCorner.toPx())
    drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = corner)
    val perMinute = size.height / scale
    val used = (day.usedSeconds / 60).toInt()
    val limit = day.limitMinutes
    val watched = if (limit == null) used else minOf(used, limit)
    if (watched > 0) {
        val h = watched * perMinute
        drawRoundRect(tint.copy(alpha = 0.85f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = corner)
    }
    if (limit != null && day.moreTimeMinutes > 0) {
        val bottom = size.height - limit * perMinute
        val h = day.moreTimeMinutes * perMinute
        drawRoundRect(tint.copy(alpha = 0.45f), topLeft = Offset(0f, bottom - h), size = Size(size.width, h), cornerRadius = corner)
    }
    if (limit != null) {
        val y = size.height - limit * perMinute
        val overhang = LimitOverhang.toPx()
        drawLine(KeColors.Ink, Offset(-overhang, y), Offset(size.width + overhang, y), strokeWidth = LimitLine.toPx())
    }
    if (day.isToday) drawRoundRect(Color.White.copy(alpha = 0.4f), cornerRadius = corner, style = Stroke(1.dp.toPx()))
}

@Composable
private fun LegendKey(label: String, color: Color, height: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(11.dp, height).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = KeColors.Ink3)
    }
}

/** The latest videos watched today (text rows, nothing to act on), then how many more. */
@Composable
private fun WatchedToday(state: ParentReviewUiState, time: ShellTime) {
    val watched = state.watchedToday
    if (watched.isEmpty()) {
        Text(NOTHING_WATCHED_TODAY, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp), color = KeColors.Ink3)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        watched.take(WATCHED_TODAY_SHOWN).forEach { VideoLine(it.video, watchedLine(it, time.zone)) }
        val more = watched.size - WATCHED_TODAY_SHOWN
        if (more > 0) {
            Text(moreWatchedTodayText(more), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = KeColors.Ink3)
        }
    }
}

/** A video's thumbnail and title with [line] under it (`.lrow.sm`); [actions] below the line. */
@Composable
private fun VideoLine(video: VideoItem, line: String?, actions: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(ThumbWidth, ThumbHeight).clip(ThumbShape).background(KeColors.Surface2)) {
            Picture(video.thumbnailUrl)
            VideoBadge(video, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = KeColors.Ink2, maxLines = 1) }
            actions?.let { Box(Modifier.padding(top = 6.dp)) { it() } }
        }
    }
}

/**
 * The new uploads of the last [NEW_UPLOAD_DAYS] days with Keep and Block, newest first; while
 * they load, a line; when no channel answered, Try again.
 */
@Composable
private fun Uploads(kid: Profile, uploads: ReviewUploads, review: ParentReviewViewModel, parent: ParentViewModel, time: ShellTime) {
    when (uploads) {
        ReviewUploads.Loading -> UploadsLine(uploadsLoadingText(kid.name))
        ReviewUploads.Failed -> Column {
            UploadsLine(uploadsFailedText(kid.name))
            PillButton(R.drawable.ic_retry, "Try again", "review-retry", review::retryUploads, Modifier.padding(top = 11.dp), ControlStyle.Secondary)
        }
        is ReviewUploads.Ready -> {
            if (uploads.videos.isEmpty()) {
                UploadsLine(NO_NEW_UPLOADS_TEXT)
                return
            }
            val now = remember(uploads) { time.clock.now() }
            val keeps = remember { FocusRequesters() }
            val focusNext = { index: Int ->
                val next = uploads.videos.getOrNull(index + 1) ?: uploads.videos.getOrNull(index - 1)
                next?.let { keeps[it.id.value]?.requestFocus() }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(uploads.videos.size, key = { uploads.videos[it].id.value }) { index ->
                    val video = uploads.videos[index]
                    VideoLine(video, metaLine(video.uploaderName, video.ageText(now))) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillButton(
                                R.drawable.ic_check,
                                "Keep",
                                "keep-${video.id.value}",
                                {
                                    parent.ownerPass?.let { pass ->
                                        focusNext(index)
                                        review.onKeep(pass, video)
                                    }
                                },
                                Modifier.focusRequester(keeps.of(video.id.value)),
                                ControlStyle.Secondary,
                            )
                            PillButton(
                                R.drawable.ic_block,
                                "Block",
                                "block-${video.id.value}",
                                {
                                    parent.ownerPass?.let { pass ->
                                        focusNext(index)
                                        review.onBlock(pass, video)
                                    }
                                },
                                style = ControlStyle.Danger,
                            )
                        }
                    }
                }
                if (uploads.failedChannels > 0) {
                    item(key = "failed") {
                        UploadsLine(uploadsPartText(uploads.failedChannels, kid.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadsLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp), color = KeColors.Ink2)
}
