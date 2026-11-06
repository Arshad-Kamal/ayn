package com.arshad.ayn.ui.mobile.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.feature.settings.ResultRow
import com.arshad.ayn.feature.settings.ShownSkip
import com.arshad.ayn.feature.settings.monthDayText
import com.arshad.ayn.feature.settings.moreSkipsLine
import com.arshad.ayn.feature.settings.plural
import com.arshad.ayn.ui.mobile.common.Kicker
import com.arshad.ayn.ui.mobile.common.KickerIcon
import com.arshad.ayn.ui.mobile.common.LastShown
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.profiles.Body
import com.arshad.ayn.ui.mobile.profiles.FootButtons
import com.arshad.ayn.ui.mobile.profiles.Headline
import com.arshad.ayn.ui.mobile.profiles.PlateTile
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.weightSpan
import java.time.LocalDate
import java.time.ZoneId

// `m6-settings` 39, 41, 43–47 (px = dp): text 20 dp in from the sides, as Settings' rows; cards
// 16 dp corners, 10 dp apart.
internal val SetupSide = 20.dp
private val CardShape = RoundedCornerShape(16.dp)
private const val MAX_SKIPS_SHOWN = 3

/**
 * A page about a backup (`m6-settings` 39, 41, 43–47): [bar] (the file, with Back where the page has
 * one), [title] and [lede], then [content], scrolling; [note] centred over [foot]'s stacked buttons.
 * Rows in [content] bring their own side inset; the parts here keep [SetupSide].
 */
@Composable
internal fun SetupPage(
    title: String,
    lede: String,
    bar: @Composable () -> Unit,
    foot: @Composable ColumnScope.() -> Unit,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        bar()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp).risingIn()) {
            Column(Modifier.padding(horizontal = SetupSide)) {
                Headline(title)
                Body(lede)
            }
            content()
        }
        // The note comes and goes with the choices; it fades, keeping its last words while it leaves.
        val last = remember { LastShown<String>() }
        if (note != null) last.value = note
        AnimatedVisibility(note != null, enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
            Text(
                last.value.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = AynColors.Ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = SetupSide, end = SetupSide, top = 8.dp),
            )
        }
        FootButtons(foot)
    }
}

/** A results page's bar (no Back: Done leaves): the file after [icon]. */
@Composable
internal fun FileKicker(text: String, @DrawableRes icon: Int) {
    Kicker(text, Modifier.fillMaxWidth().height(56.dp).padding(horizontal = SetupSide)) { KickerIcon(icon) }
}

/** What went in or came back, one card per profile (`m6-settings` 39, 43): every count (Q56). */
@Composable
internal fun ResultCards(rows: List<ResultRow>) {
    Column(Modifier.padding(start = SetupSide, end = SetupSide, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { ResultCard(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(row: ResultRow) {
    Row(
        Modifier.fillMaxWidth().background(AynColors.Surface1, CardShape).padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileTile(row.name, row.tint, PlateTile)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ProfileTags(row.owner, row.kids)
                Spacer(Modifier.weight(1f))
                row.how?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = AynColors.Ink3, maxLines = 1) }
            }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                resultCounts(row).forEach { CountText(it) }
            }
        }
    }
}

/** One count on a card: [value] in ink, then [word]; [warn] when settings kept their values here. */
internal class ResultCount(val value: String, val word: String, val warn: Boolean = false)

/** A card's counts: subscriptions (a kids profile's approved channels), history, playlists with
 *  their videos, hidden, a kids profile's approved videos, then settings. */
internal fun resultCounts(row: ResultRow): List<ResultCount> {
    val counts = row.counts
    return listOfNotNull(
        ResultCount("${counts.subscriptions}", noun(counts.subscriptions, if (row.kids) "approved channel" else "subscription")),
        ResultCount("${counts.history}", "in history"),
        ResultCount(
            "${counts.playlists}",
            noun(counts.playlists, "playlist") + if (counts.playlists > 0) " · ${plural(counts.playlistVideos, "video")}" else "",
        ),
        ResultCount("${counts.hidden}", "hidden"),
        if (row.kids) ResultCount("${counts.approvedVideos}", noun(counts.approvedVideos, "approved video")) else null,
        ResultCount(row.settings, "settings", warn = row.settingsKept > 0),
    )
}

/** [singular]'s form for [count], without the number. */
private fun noun(count: Int, singular: String): String = plural(count, singular).substringAfter(' ')

@Composable
private fun CountText(count: ResultCount) {
    val ink = if (count.warn) AynColors.Warn else AynColors.Ink
    Text(
        buildAnnotatedString {
            withStyle(weightSpan(680, ink)) { append(count.value) }
            append(" ${count.word}")
        },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
        color = if (count.warn) AynColors.Warn else AynColors.Ink3,
    )
}

/** A profile's Owner and Kids tags after its name. */
@Composable
internal fun ProfileTags(owner: Boolean, kids: Boolean) {
    if (owner) SettingsTag("Owner")
    if (kids) SettingsTag("Kids")
}

/** The values a restore couldn't apply (`m6-settings` 44): whose and which setting, then what was
 *  kept; past three a line counts the rest. */
@Composable
internal fun SkipCards(skips: List<ShownSkip>) {
    if (skips.isEmpty()) return
    Column(Modifier.padding(start = SetupSide, end = SetupSide, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        skips.take(MAX_SKIPS_SHOWN).forEach { skip ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AynColors.Warn.copy(alpha = 0.1f), CardShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text("${skip.owner ?: PHONE_LEAD} · ${skip.text.label}", style = MaterialTheme.typography.titleSmall, color = AynColors.Ink)
                Text(skip.text.detail, style = MaterialTheme.typography.bodyMedium, color = AynColors.Ink2, modifier = Modifier.padding(top = 2.dp))
            }
        }
        val more = skips.size - MAX_SKIPS_SHOWN
        if (more > 0) {
            Text(
                moreSkipsLine(more),
                style = MaterialTheme.typography.bodySmall,
                color = AynColors.Ink3,
            )
        }
    }
}

/** [epochMillis] as a backup's day ("October 14"), in this phone's zone and locale. */
@Composable
internal fun monthDay(epochMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(epochMillis, locale) {
        val zone = ZoneId.systemDefault()
        monthDayText(epochMillis, LocalDate.now(zone), zone, locale)
    }
}

/** This phone's settings, as a line under a list or the cards (`.devline`): [PHONE_LEAD] in ink, then [text]. */
@Composable
internal fun PhoneLine(text: String) {
    Text(
        buildAnnotatedString {
            withStyle(weightSpan(650, AynColors.Ink2)) { append(PHONE_LEAD) }
            append(" $text")
        },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
        color = AynColors.Ink3,
        modifier = Modifier.padding(start = SetupSide, end = SetupSide, top = 16.dp),
    )
}
