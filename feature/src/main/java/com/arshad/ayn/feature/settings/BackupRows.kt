package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.ProfileCounts
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.SETTINGS_PER_PROFILE
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint

// A backup's results as rows both UIs draw (`kp-backup` v1 `.rtab`/`.skips`, `m6-settings` v1).

/** A profile's row in a results table: [how] it came back (none for an export), its [counts], and
 *  its settings cell. */
class ResultRow(
    val name: String,
    val tint: ProfileTint,
    val owner: Boolean,
    val kids: Boolean,
    val how: String?,
    val counts: ProfileCounts,
    val settings: String,
    val settingsKept: Int = 0,
)

/** An export's rows: each profile as written, every setting with it. */
fun exportedRows(profiles: List<BackupProfileSummary>): List<ResultRow> = profiles.map {
    ResultRow(it.name, it.tint, it.owner, it.kids, how = null, counts = it.counts, settings = SETTINGS_PER_PROFILE.toString())
}

/** A restore's rows: each restored profile with how it came back, its look from the profile now here. */
fun restoredRows(result: RestoreResult.Restored, here: List<Profile>, firstLaunch: Boolean): List<ResultRow> =
    result.profiles.map { restored ->
        val profile = here.firstOrNull { it.id == restored.profileId }
        ResultRow(
            name = restored.name,
            tint = profile?.tint ?: ProfileTint.Amber,
            owner = profile?.isOwner == true,
            kids = profile?.isKids == true,
            how = restoredHow(restored.replaced, firstLaunch),
            counts = restored.counts,
            settings = "${restored.settingsApplied} of ${restored.settingsTotal}",
            settingsKept = restored.settingsTotal - restored.settingsApplied,
        )
    }

/** A skipped value to name: [owner] and [tint] are its profile's (`null` for the device's settings). */
class ShownSkip(val owner: String?, val tint: ProfileTint?, val text: SkipText)

/** A restore's skipped values, profile by profile then the device's (KP-D17). */
fun shownSkips(result: RestoreResult.Restored, here: List<Profile>): List<ShownSkip> =
    result.profiles.flatMap { restored ->
        val tint = here.firstOrNull { it.id == restored.profileId }?.tint ?: ProfileTint.Amber
        restored.skips.map { ShownSkip(restored.name, tint, skipText(it)) }
    } + result.deviceSkips.map { ShownSkip(owner = null, tint = null, text = skipText(it)) }
