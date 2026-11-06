package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.SettingSkip
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.SPEED_PERCENTS
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.kids.clockText
import java.util.Locale

// Backup & restore and Import from YouTube text both UIs show (`kp-backup` v1, `m6-settings` v1).

/** What a backup carries for each profile. */
const val PROFILE_CARRIES = "history, subscriptions, playlists, settings and filters"

const val BACKUP_SUB = "Back up or restore any of the profiles here. Only the owner can export or restore."
const val EXPORT_HINT = "Choose the profiles. Each goes with its $PROFILE_CARRIES"
const val IMPORT_SUB = "Adds the channels you follow on YouTube to your subscriptions here. Nothing is removed, and a channel you " +
    "already have keeps its place."

/** A saved backup's hint: its profile count once the file is read (Q8), then the day it was saved. */
fun savedHint(profileCount: Int?, date: String): String = metaLine(profileCount?.let { plural(it, "profile") }, "saved $date")

/** What a profile in a backup holds, as a row's hint: its subscriptions (a kids profile's approved
 *  channels and videos) and playlists when it has any, then its history. */
fun backupCounts(profile: BackupProfileSummary): String {
    val counts = profile.counts
    return listOfNotNull(
        counts.subscriptions.takeIf { it > 0 }?.let { plural(it, if (profile.kids) "approved channel" else "subscription") },
        counts.approvedVideos.takeIf { it > 0 && profile.kids }?.let { plural(it, "approved video") },
        counts.playlists.takeIf { it > 0 }?.let { plural(it, "playlist") },
        "${counts.history} in history",
    ).joinToString(" · ")
}

/** A profile here in the export sheet: its subscriptions (approved channels) and history. */
fun exportBrief(contents: ProfileContents, kids: Boolean): String = listOfNotNull(
    contents.subscriptionCount.takeIf { it > 0 }?.let { plural(it, if (kids) "approved channel" else "subscription") },
    "${contents.historyCount} in history",
).joinToString(" · ")

fun exportChosenLabel(count: Int): String = "Export ${plural(count, "profile")}"

/** A restore row's choice, as its value says it. */
fun choiceText(name: String, choice: RestoreChoice): String = when (choice) {
    is RestoreChoice.Replace -> "Replace $name"
    RestoreChoice.AddAsNew -> "Add as new"
    RestoreChoice.Skip -> "Don’t restore"
}

/** "Restore which profiles?" in Settings: what the backup holds and what each choice does. */
fun restorePickLede(date: String, count: Int): String =
    "The backup from $date holds ${plural(count, "profile")}. Replace swaps that profile’s $PROFILE_CARRIES for the " +
        "backup’s; its lock here stays. Add as new keeps both."

fun restoreButton(count: Int): String = "Restore ${plural(count, "profile")}"

/** Beside the restore button: how many profiles it replaces and adds; `null` when none. */
fun restorePickNote(rows: List<RestoreRow>): String? {
    val replaces = rows.count { it.choice is RestoreChoice.Replace }
    val adds = rows.count { it.choice == RestoreChoice.AddAsNew }
    return when {
        replaces > 0 && adds > 0 -> "Replaces ${plural(replaces, "profile")}, adds $adds"
        replaces > 0 -> "Replaces ${plural(replaces, "profile")}"
        adds > 0 -> "Adds ${plural(adds, "profile")}"
        else -> null
    }
}

fun replaceHint(name: String, date: String): String =
    "$name here takes the backup’s $PROFILE_CARRIES. What changed since $date is lost."

fun skipHint(name: String, replaces: Boolean): String = if (replaces) "$name here stays unchanged" else "$name isn’t added"

/** "Delete this backup?" (BKD-D1): the file, what it holds, and that it goes for good. */
fun deleteBackupSub(fileName: String?, count: Int): String =
    "${fileName ?: "This backup"} holds ${plural(count, "profile")}. Deleting it removes the file from Downloads. It can’t be undone."

fun exportedLede(count: Int): String =
    if (count == 1) {
        "1 profile, with its $PROFILE_CARRIES. Locks aren’t saved in backups."
    } else {
        "$count profiles, each with its $PROFILE_CARRIES. Locks aren’t saved in backups."
    }

fun restoredTitle(result: RestoreResult.Restored): String = if (result.partial) "Not everything was restored" else "Backup restored"

/** First launch's results: every profile arrived unlocked; [kids] wait for the owner's lock (KP-D14). */
fun firstRestoredLede(result: RestoreResult.Restored, date: String, kids: List<String>): String {
    val count = result.profiles.size
    val arrived = if (count == 1) "1 profile from $date, unlocked." else "$count profiles from $date, all unlocked."
    val lockFirst = when (kids.size) {
        0 -> ""
        1 -> " Lock your profile first: ${kids.single()}’s kids profile waits for it."
        else -> " Lock your profile first: the kids profiles wait for it."
    }
    return arrived + lockFirst + skippedTail(result)
}

/** A Settings restore's results: replaced profiles keep their locks, added ones have none. */
fun restoredLede(result: RestoreResult.Restored, date: String): String {
    if (result.partial) return "${spokenList(result.profiles.map { it.name })} came back from $date.${skippedTail(result)}"
    val replaced = result.profiles.count { it.replaced }
    val added = result.profiles.filterNot { it.replaced }.map { it.name }
    val base = "${plural(result.profiles.size, "profile")} from $date."
    val kept = when (replaced) {
        0 -> null
        1 -> "The one you replaced keeps its lock here"
        else -> "The $replaced you replaced keep their locks here"
    }
    val none = when {
        added.isEmpty() -> null
        kept != null -> "${spokenList(added)}, added as new, ${if (added.size == 1) "has" else "have"} none"
        else -> "${spokenList(added)}, added as new, ${if (added.size == 1) "has no lock" else "have no locks"}"
    }
    val locks = listOfNotNull(kept, none)
    return if (locks.isEmpty()) base else "$base ${locks.joinToString("; ")}."
}

/** After a partial restore's first sentence: how many settings kept their values, before the list. */
private fun skippedTail(result: RestoreResult.Restored): String {
    val skipped = result.profiles.sumOf { it.skips.size } + result.deviceSkips.size
    return when {
        skipped == 0 -> ""
        skipped == 1 -> " One setting doesn’t work in this version of Ayn, so it kept its value here:"
        result.profiles.size > 1 -> " ${countWord(skipped)} of their settings don’t work in this version of Ayn, so those kept their values here:"
        else -> " ${countWord(skipped)} settings don’t work in this version of Ayn, so those kept their values here:"
    }
}

private val COUNT_WORDS = listOf("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine")

private fun countWord(count: Int): String = COUNT_WORDS.getOrNull(count - 1) ?: count.toString()

/** A restored profile's line in the results: how it came back. */
fun restoredHow(replaced: Boolean, firstLaunch: Boolean): String = when {
    firstLaunch -> "Added"
    replaced -> "Replaced"
    else -> "Added as new"
}

/** A skipped value as the results name it: what it is, then what happened to it. */
class SkipText(val label: String, val detail: String)

/** [skip] said for a person: its setting's name, the backup's value and the value it kept. */
fun skipText(skip: SettingSkip): SkipText {
    val restrictions = skip.key.startsWith(RESTRICTIONS_PREFIX)
    val key = skip.key.removePrefix(RESTRICTIONS_PREFIX)
    val label = settingLabel(key)?.let { if (restrictions) "Restrictions › $it" else it } ?: skip.key
    val backup = shownValue(key, skip.backupValue, kept = false)
    val kept = skip.keptValue?.let { shownValue(key, it, kept = true) } ?: unsetValue(key)
    val detail = when {
        key == "speedPercent" && (skip.backupValue.toIntOrNull() ?: 0) > SPEED_PERCENTS.max() ->
            "$backup in the backup, and Ayn goes up to ${speedLabel(SPEED_PERCENTS.max())}. Kept $kept."
        key.startsWith(SEGMENT_PREFIX) && SegmentCategory.of(key.removePrefix(SEGMENT_PREFIX)) == null ->
            "This version of Ayn doesn’t have this category, so it wasn’t restored."
        key.startsWith(SEGMENT_PREFIX) -> "$backup isn’t a choice in this version. Kept $kept."
        key.startsWith(ISLAMIC_CHANNEL_PREFIX) -> "This version of Ayn doesn’t list this channel, so it wasn’t restored."
        key == "restrictedMode" -> "$backup isn’t a level in this version. Set to $kept."
        kept == null -> "$backup in the backup doesn’t work in this version of Ayn, so it wasn’t restored."
        else -> "$backup in the backup doesn’t work in this version of Ayn. Kept $kept."
    }
    return SkipText(label, detail)
}

private const val RESTRICTIONS_PREFIX = "restrictions."
private const val SEGMENT_PREFIX = "segmentActions."
private const val DAILY_PREFIX = "dailyLimitMinutes."
private const val ISLAMIC_CHANNEL_PREFIX = "filterPacks.islamicChannels."

private fun settingLabel(key: String): String? = when {
    key == "speedPercent" -> "Playback speed"
    key == "captionsLanguage" -> "Captions"
    key == "chatPlacement" -> "Live chat"
    key == "restrictedMode" -> "Restricted Mode"
    key == "minLengthMinutes" -> "Hide videos shorter than"
    key == "maxLengthMinutes" -> "Hide videos longer than"
    key == "bedtime" -> "Bedtime"
    key == "device.maxVideoHeight" -> "Quality cap"
    key == "device.interfaceScalePercent" -> "Interface size"
    key.startsWith(DAILY_PREFIX) -> "Daily limit › " + key.removePrefix(DAILY_PREFIX).replaceFirstChar { it.titlecase(Locale.ROOT) }
    key.startsWith(SEGMENT_PREFIX) -> {
        val category = key.removePrefix(SEGMENT_PREFIX)
        "SponsorBlock › " + (SegmentCategory.of(category)?.text?.name ?: category)
    }
    key.startsWith(ISLAMIC_CHANNEL_PREFIX) -> "Islamic content only › " + key.removePrefix(ISLAMIC_CHANNEL_PREFIX)
    else -> null
}

/** What a setting [key] holds when it is unset (a kept `null`); `null` for a key nothing here holds. */
private fun unsetValue(key: String): String? = when {
    key == "captionsLanguage" || key.endsWith("LengthMinutes") -> "Off"
    key.startsWith(DAILY_PREFIX) -> "No limit"
    key == "bedtime" -> "No bedtime"
    else -> null
}

/** A stored value as the setting's page names it; one this build can't read, in quotes. */
private fun shownValue(key: String, value: String, kept: Boolean): String {
    val number = value.toIntOrNull()
    return when {
        key == "speedPercent" && number != null -> speedLabel(number)
        key == "captionsLanguage" -> captionsLabel(value)
        key == "chatPlacement" -> ChatPlacement.of(value)?.label ?: quoted(value)
        key.startsWith(SEGMENT_PREFIX) -> SegmentAction.entries.firstOrNull { it.key == value }?.label ?: quoted(value)
        key == "restrictedMode" && kept -> value.replaceFirstChar { it.titlecase(Locale.ROOT) }
        (key.endsWith("LengthMinutes") || key.startsWith(DAILY_PREFIX)) && number != null -> "$number min"
        key == "bedtime" -> bedtimeText(value) ?: quoted(value)
        key == "device.maxVideoHeight" && number != null -> "${number}p"
        key == "device.interfaceScalePercent" && number != null -> interfaceSizeLabel(number)
        else -> quoted(value)
    }
}

private fun quoted(value: String): String = "“${value.replaceFirstChar { it.titlecase(Locale.ROOT) }}”"

/** A stored bedtime ("1170-420", minutes of the day) in 12-hour times; `null` when it isn't one. */
private fun bedtimeText(value: String): String? {
    val (start, end) = value.split('-').takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
    if (start == null || end == null || start !in 0..<MINUTES_A_DAY || end !in 0..<MINUTES_A_DAY) return null
    return "${clockText(start)}–${clockText(end)}"
}

private const val MINUTES_A_DAY = 24 * 60

/** An import's toast: the channels added, then how many were already there. */
fun importedText(added: Int, alreadySubscribed: Int): Pair<String, String?> {
    val already = if (alreadySubscribed == 1) "was" else "were"
    return if (added == 0) {
        val all = if (alreadySubscribed == 1) "the 1 channel" else "all $alreadySubscribed channels"
        "Nothing new" to "$all $already already in your subscriptions"
    } else {
        "${plural(added, "channel")} added" to alreadySubscribed.takeIf { it > 0 }?.let { "$it $already already in your subscriptions" }
    }
}

/** Why an import added nothing: its heading, then what to do. */
fun importProblemCopy(problem: ImportProblem): Pair<String, String> = when (problem) {
    ImportProblem.NoChannels -> "No YouTube channels in this file" to
        "Ayn imports the subscriptions.csv file from Google Takeout (in YouTube and YouTube Music › subscriptions) or a " +
        "NewPipe subscriptions export. Nothing was added."
    ImportProblem.Unreadable -> "Ayn couldn’t open this file" to "Check that it’s still there, or choose another."
    ImportProblem.TooLarge -> "This file is too large to be a subscriptions list" to "Choose the subscriptions.csv file from Google Takeout."
    ImportProblem.Failed -> "The import didn’t finish" to "Nothing was added. Try again, or choose another file."
}

/** The owner's lock before an export or a restore: what it is for. */
fun backupGateKicker(action: BackupAction): String = when (action) {
    BackupAction.Export -> "Exporting a backup"
    is BackupAction.Read -> "Restoring a backup"
}

/** Who may do [action], before the lock is asked for. */
fun backupOwnerOnly(action: BackupAction): String = when (action) {
    BackupAction.Export -> "Only the owner exports backups"
    is BackupAction.Read -> "Only the owner restores backups"
}

/** Why a file picked in Settings isn't a backup: the profile exists by now, so it points at the import row. */
const val NOT_A_BACKUP_IN_SETTINGS = "Ayn restores its own backups, named like Ayn-backup-2026-10-03.json, and KE-backup files from " +
    "before the rename. To add subscriptions from a Google Takeout file, use Import subscriptions from YouTube."

/** Under the first skipped values: how many more kept theirs. */
fun moreSkipsLine(more: Int): String = "${plural(more, "more setting")} kept ${if (more == 1) "its value" else "their values"} here."
