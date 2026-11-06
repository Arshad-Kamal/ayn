package com.arshad.ayn.ui.tv.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.feature.settings.ResultRow
import com.arshad.ayn.feature.settings.ShownSkip
import com.arshad.ayn.feature.settings.moreSkipsLine
import com.arshad.ayn.feature.settings.plural
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.drawKeyline
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.PlateSize
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.controlRoomLight
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan

// `.setup` (px ÷ 2): 28 dp from the top, the foot 32 dp from the bottom; the title 36 sp, the lede
// 14 sp up to 650 dp wide.
private val SetupTop = 28.dp
private val SetupFootBottom = 32.dp
private val LedeWidth = 650.dp

/**
 * A full-screen setup page (`.setup`, `kp-backup` firstpick, restorepick and the results): [kicker]
 * after [icon], [title], [lede], then [content]; [foot]'s buttons along the bottom with [note] at
 * the right. It rises in (D8).
 */
@Composable
internal fun SetupPage(
    @DrawableRes icon: Int,
    kicker: String,
    title: String,
    lede: String,
    foot: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rise = rememberRise()
    val riseFoot = rememberRise(40)
    Box(
        modifier
            .fillMaxSize()
            .controlRoomLight()
            .padding(start = KeLayout.ContentStart, end = KeLayout.SafeHorizontal),
    ) {
        Column(Modifier.padding(top = SetupTop).rising(rise)) {
            KickerRow(kicker) { KickerIcon(icon) }
            Text(title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp), modifier = Modifier.padding(top = 9.dp))
            Text(
                text = lede,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.4.em),
                color = KeColors.Ink2,
                modifier = Modifier.padding(top = 9.dp).widthIn(max = LedeWidth),
            )
            content()
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = SetupFootBottom).rising(riseFoot),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            foot()
            note?.let {
                Spacer(Modifier.weight(1f))
                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp), color = KeColors.Ink3)
            }
        }
    }
}

/** A setup page's foot button (`.foot .btn`). */
@Composable
internal fun FootButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ControlStyle.Secondary,
) {
    PillButton(icon, label, focusKey = label, onClick = onClick, modifier = modifier, style = style)
}

/** The device section's line under a list or table (`.devline`): [DEVICE_LEAD] in bold, then [text]. */
@Composable
internal fun DeviceLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 13.dp).widthIn(max = LedeWidth), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(R.drawable.ic_interface), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(weightSpan(600, KeColors.Ink2)) { append(DEVICE_LEAD) }
                append(" $text")
            },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 1.4.em),
            color = KeColors.Ink3,
        )
    }
}

// `.rtab` (px ÷ 2): the profile column 220 dp, six right-aligned numbers, 8 dp padding over a hairline.
private val WhoWidth = 220.dp
private val CellPadding = 8.dp
private val TablePlate = PlateSize(22.dp, 6.dp, 13.5.sp, 1.dp)
private val Columns = listOf("Subscriptions", "History", "Playlists", "Hidden", "Approved", "Settings")

/** What a backup carried per profile (`.rtab`): subscriptions, history, playlists, hidden,
 *  approved (kids profiles) and settings. */
@Composable
internal fun ResultsTable(rows: List<ResultRow>, modifier: Modifier = Modifier) {
    val head = MaterialTheme.typography.bodyLarge.atWeight(560).copy(fontSize = 11.5.sp)
    Column(modifier.padding(top = 20.dp).fillMaxWidth()) {
        Row(Modifier.padding(bottom = 7.dp)) {
            Text("Profile", style = head, color = KeColors.Ink3, modifier = Modifier.width(WhoWidth))
            Columns.forEach { Text(it, style = head, color = KeColors.Ink3, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
        }
        rows.forEach { ResultLine(it) }
    }
}

@Composable
private fun ResultLine(row: ResultRow) {
    val counts = row.counts
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(KeColors.Line, size = size.copy(height = 1.dp.toPx())) }
            .padding(vertical = CellPadding),
    ) {
        Row(Modifier.width(WhoWidth), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ProfilePlate(row.name, row.tint, TablePlate)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, style = MaterialTheme.typography.labelMedium.copy(fontSize = 15.sp), maxLines = 1)
                    if (row.owner) TableTag("Owner")
                    if (row.kids) TableTag("Kids")
                }
                row.how?.let { Text(it, style = SmallStyle, color = KeColors.Ink3) }
            }
        }
        Cell(counts.subscriptions.toString(), if (row.kids) "approved channels" else null)
        Cell(counts.history.toString())
        Cell(counts.playlists.toString(), counts.playlistVideos.takeIf { counts.playlists > 0 }?.let { plural(it, "video") })
        Cell(counts.hidden.toString())
        if (row.kids) Cell(counts.approvedVideos.toString(), if (counts.approvedVideos == 1) "video" else "videos") else Cell("–", muted = true)
        Cell(row.settings, row.settingsKept.takeIf { it > 0 }?.let { "${plural(it, "setting")} kept" }, warn = row.settingsKept > 0)
    }
}

private val SmallStyle: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)

@Composable
private fun RowScope.Cell(value: String, small: String? = null, muted: Boolean = false, warn: Boolean = false) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (warn) Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = KeColors.Warn, modifier = Modifier.padding(end = 4.dp).size(13.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.atWeight(if (muted) 450 else 620).copy(fontSize = 15.sp, fontFeatureSettings = "tnum"),
                color = when {
                    warn -> KeColors.Warn
                    muted -> KeColors.Ink3
                    else -> KeColors.Ink
                },
            )
        }
        small?.let { Text(it, style = SmallStyle, color = KeColors.Ink3) }
    }
}

private val TagShape = RoundedCornerShape(3.5.dp)

@Composable
private fun TableTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.atWeight(650).copy(fontSize = 10.sp),
        modifier = Modifier
            .padding(start = 6.dp)
            .drawBehind { drawKeyline(TagShape, 1.dp, Color.White.copy(alpha = 0.3f), 1f) }
            .padding(horizontal = 4.5.dp, vertical = 1.dp),
    )
}

// `.skips` (px ÷ 2): warn-tinted cards 7 × 11 dp in, 8 dp corners, 5 dp apart. Past [MaxSkipsShown]
// a line counts the rest, so the table under them stays on screen.
private val SkipShape = RoundedCornerShape(8.dp)
private const val MaxSkipsShown = 3

/** The values a restore couldn't apply (`.skips`): whose, which setting, what was kept. */
@Composable
internal fun SkipsList(skips: List<ShownSkip>, modifier: Modifier = Modifier) {
    if (skips.isEmpty()) return
    Column(modifier.padding(top = 11.dp).widthIn(max = LedeWidth), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        skips.take(MaxSkipsShown).forEach { skip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawInflated(SkipShape, 0.dp, KeColors.Warn.copy(alpha = 0.06f), 1f)
                        drawKeyline(SkipShape, 1.dp, KeColors.Warn.copy(alpha = 0.32f), 1f)
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                val owner = skip.owner
                val tint = skip.tint
                if (owner != null && tint != null) {
                    ProfilePlate(owner, tint, TablePlate)
                } else {
                    Icon(painterResource(R.drawable.ic_interface), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = buildAnnotatedString {
                        withStyle(weightSpan(620, KeColors.Ink)) {
                            append(listOfNotNull(skip.owner ?: DEVICE_LEAD, skip.text.label).joinToString(" · "))
                        }
                        append("  ${skip.text.detail}")
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp, lineHeight = 1.35.em),
                    color = KeColors.Ink2,
                )
            }
        }
        val more = skips.size - MaxSkipsShown
        if (more > 0) {
            Text(moreSkipsLine(more), style = SmallStyle, color = KeColors.Ink3)
        }
    }
}
