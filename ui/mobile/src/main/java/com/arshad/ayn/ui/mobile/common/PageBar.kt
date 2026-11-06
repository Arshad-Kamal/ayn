package com.arshad.ayn.ui.mobile.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion

/** Whether [list] has scrolled off its top (a bar's hairline, the top bar's lift). */
@Composable
internal fun rememberScrolled(list: LazyListState): Boolean {
    val scrolled by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    return scrolled
}

/** The bar's own height, under the status bar. */
internal val PageBarHeight = 60.dp

/** Where a page's bar ends: the status bar and the bar. */
@Composable
internal fun pageBarBottom(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PageBarHeight

private val GlassFill = Color(0xFF0E0B08).copy(alpha = 0.58f)

/**
 * The bar over a page opened on a tab (`.cbar`): back, see-through over a picture until [solid]
 * (a dark glass circle behind back), then the ground with [title] (150 ms); [lifted] draws a
 * hairline at its foot over scrolled rows. [trail] sits at its end (History's Clear history).
 */
@Composable
internal fun PageBar(
    title: String?,
    solid: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
    trail: @Composable RowScope.() -> Unit = {},
) {
    val ground by animateFloatAsState(if (solid) 1f else 0f, AynMotion.press(), label = "bar")
    val line by animateFloatAsState(if (lifted) 1f else 0f, AynMotion.press(), label = "barLine")
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(AynColors.Ground.copy(alpha = ground))
                val hairline = 1.dp.toPx()
                drawRect(AynColors.Line.copy(alpha = AynColors.Line.alpha * line), Offset(0f, size.height - hairline), Size(size.width, hairline))
            }
            .padding(WindowInsets.statusBars.asPaddingValues())
            .height(PageBarHeight)
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.drawBehind {
                drawCircle(GlassFill.copy(alpha = GlassFill.alpha * (1f - ground)), radius = size.minDimension / 2 - 4.dp.toPx())
            },
        ) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = AynColors.Ink)
        }
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 28.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        trail()
    }
}
