package com.arshad.ayn.ui.mobile.kids

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.kids.NOTHING_WATCHED_TODAY
import com.arshad.ayn.feature.kids.NO_NEW_UPLOADS_TEXT
import com.arshad.ayn.feature.kids.ParentReviewUiState
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.kids.ReviewDay
import com.arshad.ayn.feature.kids.ReviewUploads
import com.arshad.ayn.feature.kids.WATCHED_TODAY_SHOWN
import com.arshad.ayn.feature.kids.barTimeText
import com.arshad.ayn.feature.kids.moreWatchedTodayText
import com.arshad.ayn.feature.kids.newUploadsAside
import com.arshad.ayn.feature.kids.reviewAside
import com.arshad.ayn.feature.kids.reviewDaySpoken
import com.arshad.ayn.feature.kids.reviewScaleMinutes
import com.arshad.ayn.feature.kids.short
import com.arshad.ayn.feature.kids.todayAgainstLimit
import com.arshad.ayn.feature.kids.uploadsFailedText
import com.arshad.ayn.feature.kids.uploadsLoadingText
import com.arshad.ayn.feature.kids.uploadsPartText
import com.arshad.ayn.feature.kids.watchedLine
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.NoticeLine
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.home.LengthBadge
import com.arshad.ayn.ui.mobile.profiles.color
import com.arshad.ayn.ui.mobile.settings.PageFoot
import com.arshad.ayn.ui.mobile.settings.PageLede
import com.arshad.ayn.ui.mobile.settings.SettingsFrame
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlin.math.sqrt
import kotlin.time.Instant

// `.wk`/`.wbar` (px = dp): wells 120 dp tall with 8 dp corners, 6 dp apart; the watched bar 6 dp in
// from each side with 5 dp top corners; the limit a 2 dp dash; today's well ringed 1.5 dp.
private val WellHeight = 120.dp
private val WellCorner = 8.dp
private val BarInset = 6.dp
private val BarCorner = 5.dp
private val LimitStroke = 2.dp
private val TodayRing = 1.5.dp

// The More time stripes (`repeating-linear-gradient(135deg, …)`): 4 dp on, 4 dp off across them,
// so 8 dp × √2 apart along a row; the legend's swatch has 3 dp on, 3 dp off.
private val StripeWidth = 4.dp
private val StripeStep = 8.dp * sqrt(2f)
private val LegendStripeWidth = 3.dp
private val LegendStripeStep = 6.dp * sqrt(2f)
private val LegendCorner = 3.dp

// `.rrow` in the review: a 168 dp picture with 11 dp corners, 12 dp before the text.
private val PictureWidth = 168.dp
private val PictureShape = RoundedCornerShape(11.dp)

/**
 * The parent review (`m6-kids` 15–16; F12, KP-D10): the week's watch time against the limits
 * (bars in [kid]'s colour, More time striped over the limit, the limit a dashed line), what the kid
 * watched today, and the approved channels' new uploads with Keep and Block, which take [parent]'s
 * pass. A kept or blocked video leaves the list; a block's notice offers Undo. Back (the page's
 * own handler) returns to the Parent page.
 */
@Composable
internal fun ReviewPage(kid: Profile, state: ParentReviewUiState, review: ParentReviewViewModel, parent: ParentViewModel, time: ShellTime) {
    // A block's notice belongs to this page: leaving it, the block stands.
    DisposableEffect(review) { onDispose { review.onBlockNoticeClosed() } }
    val tint = kid.tint.color
    // The uploads' ages, against one instant per load.
    val now = remember(state.uploads) { time.clock.now() }
    Box(Modifier.fillMaxSize()) {
        SettingsFrame("Review", onBack = { parent.onBack() }) {
            state.week.firstOrNull()?.let { monday -> item(key = "lede") { PageLede(reviewAside(kid.name, monday.date), kid) } }
            item(key = "week-head") { ReviewHeading("Time this week", todayAgainstLimit(state.today)) }
            item(key = "week") { WeekBars(state.week, tint) }
            item(key = "watched-head") { ReviewHeading("Watched today", null) }
            watchedToday(state, time)
            val ready = state.uploads as? ReviewUploads.Ready
            item(key = "uploads-head") { ReviewHeading("New from ${kid.name}’s channels", ready?.let { newUploadsAside(it.videos.size) }) }
            uploads(kid, state.uploads, now, review, parent)
        }
        NoticeSnackbar(
            notice = state.blocked,
            line = { NoticeLine("${it.title} blocked", "in ${kid.name}") },
            onAction = { parent.ownerPass?.let(review::onUndoBlock) },
            onClose = review::onBlockNoticeClosed,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            identity = { it.id },
        )
    }
}

/** A section's title (`.rh`), its [aside] after it, wrapping under it when there's no room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewHeading(title: String, aside: String?) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.atWeight(680).copy(fontSize = 17.sp), modifier = Modifier.alignByBaseline().semantics { heading() })
        if (aside != null) {
            Text(aside, style = MaterialTheme.typography.bodySmall.atWeight(560).copy(fontSize = 12.5.sp), color = AynColors.Ink3, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

/** A bar per day, Monday first, its time and its day under it; then what each mark means. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekBars(week: List<ReviewDay>, tint: Color) {
    val scale = reviewScaleMinutes(week)
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            week.forEach { day ->
                val label = if (day.isToday) "Today" else day.date.dayOfWeek.short()
                val spoken = reviewDaySpoken(day)
                Column(
                    Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(WellHeight)
                            .clip(RoundedCornerShape(WellCorner))
                            .background(AynColors.Surface1)
                            .drawBehind { drawDay(day, scale, tint) },
                    )
                    // Wraps rather than clips at a large font ("1 h" over "15").
                    Text(
                        barTimeText(day.usedSeconds),
                        style = MaterialTheme.typography.labelSmall.atWeight(650).copy(fontSize = 11.5.sp, fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.let { if (day.isToday) it.atWeight(700) else it }.copy(fontSize = 11.5.sp),
                        color = if (day.isToday) AynColors.Ink else AynColors.Ink3,
                    )
                }
            }
        }
        FlowRow(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LegendKey("Watched") { drawRoundRect(tint, cornerRadius = CornerRadius(LegendCorner.toPx())) }
            LegendKey("More time you added") {
                drawStripes(Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(LegendCorner.toPx()))) }, tint, LegendStripeWidth.toPx(), LegendStripeStep.toPx())
            }
            LegendKey("Limit") { drawLimit(size.height / 2, size.width) }
        }
    }
}

private fun Size.toRect(): Rect = Rect(Offset.Zero, this)

@Composable
private fun LegendKey(label: String, mark: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(14.dp, 10.dp), onDraw = mark)
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = AynColors.Ink3)
    }
}

/** A day's well: watched up to the limit, More time striped above it, the limit dashed across. */
private fun DrawScope.drawDay(day: ReviewDay, scale: Int, tint: Color) {
    val perMinute = size.height / scale
    val inset = BarInset.toPx()
    val corner = CornerRadius(BarCorner.toPx())
    val used = (day.usedSeconds / 60).toInt()
    val limit = day.limitMinutes
    val watched = if (limit == null) used else minOf(used, limit)
    fun bar(bottom: Float, height: Float): Path = Path().apply {
        addRoundRect(RoundRect(Rect(inset, bottom - height, size.width - inset, bottom), topLeft = corner, topRight = corner))
    }
    if (watched > 0) drawPath(bar(size.height, watched * perMinute), tint)
    if (limit != null && day.moreTimeMinutes > 0) {
        drawStripes(bar(size.height - limit * perMinute, day.moreTimeMinutes * perMinute), tint, StripeWidth.toPx(), StripeStep.toPx())
    }
    if (limit != null) drawLimit(size.height - limit * perMinute, size.width)
    if (day.isToday) {
        val ring = TodayRing.toPx()
        drawRoundRect(
            AynColors.Ink3,
            topLeft = Offset(ring / 2, ring / 2),
            size = Size(size.width - ring, size.height - ring),
            cornerRadius = CornerRadius(WellCorner.toPx()),
            style = Stroke(ring),
        )
    }
}

/** [shape] in the kid's colour, striped darker and lighter (`.more`). */
private fun DrawScope.drawStripes(shape: Path, tint: Color, stripe: Float, step: Float) {
    clipPath(shape) {
        drawRect(tint.copy(alpha = 0.45f))
        val bounds = shape.getBounds()
        var x = bounds.left - bounds.height
        while (x < bounds.right) {
            drawLine(tint.copy(alpha = 0.9f), Offset(x, bounds.bottom), Offset(x + bounds.height, bounds.top), strokeWidth = stripe)
            x += step
        }
    }
}

private fun DrawScope.drawLimit(y: Float, width: Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
    drawLine(AynColors.Ink2, Offset(0f, y), Offset(width, y), strokeWidth = LimitStroke.toPx(), pathEffect = dash)
}

/** Today's latest videos (nothing to act on), then how many more. */
private fun LazyListScope.watchedToday(state: ParentReviewUiState, time: ShellTime) {
    val watched = state.watchedToday
    if (watched.isEmpty()) {
        item(key = "watched-none") { ReviewLine(NOTHING_WATCHED_TODAY) }
        return
    }
    items(watched.take(WATCHED_TODAY_SHOWN), key = { "watched-${it.video.id.value}" }) {
        ReviewRow(it.video, watchedLine(it, time.zone), Modifier.animateItem())
    }
    val more = watched.size - WATCHED_TODAY_SHOWN
    if (more > 0) item(key = "watched-more") { ReviewLine(moreWatchedTodayText(more)) }
}

/**
 * The new uploads with Keep and Block, newest first; while they load, a line; when no channel
 * answered, Try again. Under the list, what Keep and Block do.
 */
private fun LazyListScope.uploads(kid: Profile, uploads: ReviewUploads, now: Instant, review: ParentReviewViewModel, parent: ParentViewModel) {
    when (uploads) {
        ReviewUploads.Loading -> item(key = "uploads-loading") { ReviewLine(uploadsLoadingText(kid.name)) }
        ReviewUploads.Failed -> item(key = "uploads-failed") {
            Column {
                ReviewLine(uploadsFailedText(kid.name))
                PillButton(
                    "Try again",
                    review::retryUploads,
                    Modifier.padding(start = 20.dp, top = 12.dp),
                    kind = ButtonKind.Tonal,
                    icon = R.drawable.ic_retry,
                )
            }
        }
        is ReviewUploads.Ready -> {
            if (uploads.videos.isEmpty()) {
                item(key = "uploads-none") { ReviewLine(NO_NEW_UPLOADS_TEXT) }
            } else {
                items(uploads.videos, key = { "upload-${it.id.value}" }) { video ->
                    ReviewRow(video, metaLine(video.uploaderName, video.ageText(now)), Modifier.animateItem()) {
                        UploadActions(video, review, parent)
                    }
                }
            }
            if (uploads.failedChannels > 0) item(key = "uploads-part") { ReviewLine(uploadsPartText(uploads.failedChannels, kid.name)) }
            if (uploads.videos.isNotEmpty()) {
                item(key = "uploads-foot") {
                    PageFoot("Keep leaves it allowed and takes it off this list; Block hides it from ${kid.name}, even on an approved channel.")
                }
            }
        }
    }
}

@Composable
private fun UploadActions(video: VideoItem, review: ParentReviewViewModel, parent: ParentViewModel) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(
            "Keep",
            { parent.ownerPass?.let { review.onKeep(it, video) } },
            Modifier.semantics { contentDescription = "Keep ${video.title}" },
            kind = ButtonKind.Tonal,
            icon = R.drawable.ic_check,
            small = true,
        )
        PillButton(
            "Block",
            { parent.ownerPass?.let { review.onBlock(it, video) } },
            Modifier.semantics { contentDescription = "Block ${video.title}" },
            kind = ButtonKind.DangerTonal,
            icon = R.drawable.ic_block,
            small = true,
        )
    }
}

/** A video's picture (with its length) and title over [line] (`.rrow`); [actions] under the line. */
@Composable
private fun ReviewRow(video: VideoItem, line: String, modifier: Modifier = Modifier, actions: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(PictureWidth).aspectRatio(16f / 9f).clip(PictureShape).background(AynColors.Surface2)) {
            Picture(video.thumbnailUrl)
            LengthBadge(video, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyMedium.atWeight(620).copy(fontSize = 14.5.sp, lineHeight = 18.3.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 4.dp))
            actions?.invoke()
        }
    }
}

@Composable
private fun ReviewLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
        color = AynColors.Ink2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}
