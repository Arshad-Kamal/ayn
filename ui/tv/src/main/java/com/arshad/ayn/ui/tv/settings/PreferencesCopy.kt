package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.SegmentAction

// The TV's own copy of the preference pages (`b3-settings` v13); shared text: feature/settings/PreferencesText.kt.

/** What an action does, under its name in the sheet. */
internal val SegmentAction.consequence: String
    get() = when (this) {
        SegmentAction.Skip -> "Jumps past it and shows a note with Undo"
        SegmentAction.Button -> "You choose, with one press of OK"
        SegmentAction.Bar -> "Marked in colour, never skipped"
        SegmentAction.Off -> "Not fetched or shown"
    }

/** What a Live chat choice does, under its name in the sheet, where it says something. */
internal val ChatPlacement.consequence: String?
    get() = when (this) {
        ChatPlacement.Beside -> "The video moves aside; chat can be scrolled back"
        ChatPlacement.Side -> "The video stays full screen; chat floats over it with no backdrop"
        ChatPlacement.TopLeft, ChatPlacement.TopRight, ChatPlacement.BottomLeft, ChatPlacement.BottomRight -> null
    }

/** Playback's Quality row: "Best available, up to 2160p". */
internal fun qualityLabel(maxHeight: Int): String = "Best available, up to ${maxHeight}p"

/** What an Interface size choice is, under it, where it says something (Q59). */
internal fun interfaceSizeHint(percent: Int): String? = when (percent) {
    80 -> "More fits on the screen"
    100 -> "As Ayn is drawn"
    120 -> "The largest text"
    else -> null
}

internal const val EVERYONE_SCOPE = "Everyone on this Shield"
internal const val EACH_PROFILE = " · each profile sets its own"

/** Who changes the TV's settings, for anyone else (Q17). */
internal fun ownerOnly(ownerName: String): String = "Only the owner, $ownerName, can change these"
