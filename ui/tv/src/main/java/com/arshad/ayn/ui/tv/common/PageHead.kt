package com.arshad.ayn.ui.tv.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout

// The kit's `.page-head` (px ÷ 2): 38 dp tall from the safe line, the aside 13 sp.
internal val PageHeadHeight = 38.dp
private val AsideSize = 13.sp

/** A rail destination's page title with [aside] (counts, the last update) at its right (`.page-head`). */
@Composable
internal fun PageHead(title: String, aside: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = KeLayout.ContentStart, top = KeLayout.SafeVertical, end = KeLayout.SafeHorizontal)
            .height(PageHeadHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
        aside?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = AsideSize, fontFeatureSettings = "tnum"),
                color = KeColors.Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, bottom = 3.dp),
            )
        }
    }
}
