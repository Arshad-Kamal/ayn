package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.feature.R
import com.arshad.ayn.feature.format.lengthText
import java.util.Locale

/**
 * The preference pages' samples are one real video, recorded for the approved mockups
 * (`b3-settings` Q14, `m6-settings`): Veritasium's Enigma video (JsBZOcqZerk), its SponsorBlock
 * segments, its DeArrow title and thumbnail and its votes, as they were on 2026-10-03.
 */
object SettingsSample {
    const val LENGTH_S: Float = 2862f
    const val PLAYED_S: Float = 2091f
    const val TITLE: String = "History and operating principles of the Enigma decryption machine from World War 2"
    const val ORIGINAL_TITLE: String = "The Insane Real Engineering of the Nazi Enigma Machine"
    val ORIGINAL_PICTURE: Int = R.drawable.sample_original
    val DEARROW_PICTURE: Int = R.drawable.sample_dearrow
    const val LIKES: String = "121K"
    const val DISLIKES: String = "1.3K"
    const val LIKED: Float = 0.989f
    const val PLAYED_FRACTION: Float = PLAYED_S / LENGTH_S
    val LIKED_TEXT: String = "%.1f%%".format(Locale.ENGLISH, LIKED * 100)

    val SEGMENTS: List<SampleSegment> = listOf(
        SampleSegment(SegmentCategory.SelfPromo, 309.888f, 320.62f, withEnd = false),
        SampleSegment(SegmentCategory.Sponsor, 1394.75f, 1480.22f, withEnd = true),
    )
}

/** A segment of the sample; the legend gives a short one ([withEnd] false) only its start. */
class SampleSegment(val category: SegmentCategory, val startS: Float, val endS: Float, val withEnd: Boolean) {
    val startFraction: Float get() = startS / SettingsSample.LENGTH_S
    val widthFraction: Float get() = (endS - startS) / SettingsSample.LENGTH_S

    val legend: String
        get() = "${category.text.name} ${clock(startS)}" + if (withEnd) "–${clock(endS)}" else ""
}

private fun clock(seconds: Float): String = lengthText(seconds.toLong()).orEmpty()
