package com.arshad.ayn.ui.tv.theme

import androidx.compose.ui.graphics.Color
import com.arshad.ayn.core.access.SegmentCategory

/** A SponsorBlock category's colour on swatches and the seek bar (the master's `--sponsor` …, oklch to sRGB). */
internal val SegmentCategory.color: Color
    get() = when (this) {
        SegmentCategory.Sponsor -> Color(0xFF56D57B)
        SegmentCategory.SelfPromo -> Color(0xFFF0CF4C)
        SegmentCategory.Interaction -> Color(0xFFB28FEF)
        SegmentCategory.Intro -> Color(0xFF4CDBE3)
        SegmentCategory.Outro -> Color(0xFF60A7D6)
        SegmentCategory.Preview -> Color(0xFF2EA4DC)
        SegmentCategory.Hook -> Color(0xFF5A86CE)
        SegmentCategory.MusicOffTopic -> Color(0xFFF59740)
        SegmentCategory.Filler -> Color(0xFF7E4ED7)
    }
