package com.arshad.ayn.ui.tv.player

import androidx.compose.ui.geometry.Size

/** [content] scaled to fit inside [slot] at its own shape (a square or portrait video's storyboard
 *  frames aren't 16:9). */
internal fun fittedSize(content: Size, slot: Size): Size {
    val scale = minOf(slot.width / content.width, slot.height / content.height)
    return Size(content.width * scale, content.height * scale)
}
