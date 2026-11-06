package com.arshad.ayn.ui.tv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.feature.settings.monthDayText
import java.time.LocalDate
import java.time.ZoneId

// The TV's copy shared by every Settings category (`b3-profiles` v8); shared text: feature/settings/SettingsText.kt.

/** Asking for [ownerName]'s lock: a sequence ends with OK, a PIN submits itself. */
internal fun enterLock(ownerName: String, kind: LockKind): String = when (kind) {
    LockKind.Sequence -> "Enter $ownerName’s sequence, then press OK."
    LockKind.Pin -> "Enter $ownerName’s PIN."
}

/** A lock entry's hint over Settings: BACK leaves it. */
internal fun cancelHint(kind: LockKind): String = if (kind == LockKind.Pin) "BACK cancels · number keys work too" else "BACK cancels"

/** The end of a lock entry's wait line over Settings. */
internal const val SettingsWaitTail = ", or press BACK to cancel."

/** A date as the Settings rows say it ([monthDayText] in the configured locale). */
@Composable
internal fun monthDay(epochMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(epochMillis, locale) {
        val zone = ZoneId.systemDefault()
        monthDayText(epochMillis, LocalDate.now(zone), zone, locale)
    }
}
