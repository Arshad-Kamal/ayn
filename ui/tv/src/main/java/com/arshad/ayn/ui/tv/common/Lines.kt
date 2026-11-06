package com.arshad.ayn.ui.tv.common

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arshad.ayn.ui.tv.theme.KeColors

/** A 1 dp [color] outline just inside the bounds, corners [radius] (the master's inset hairlines). */
internal fun Modifier.hairlineBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(stroke),
    )
}

/** A 1 dp [KeColors.Line] rule down the start edge, then [gap] before the content (`border-left`). */
internal fun Modifier.leadingRule(gap: Dp): Modifier =
    drawBehind { drawRect(KeColors.Line, size = size.copy(width = 1.dp.toPx())) }.padding(start = gap)
