package com.arshad.ayn.core.backup

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.INTERFACE_SCALE_PERCENTS
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.MAX_VIDEO_HEIGHTS
import com.arshad.ayn.core.store.MINUTES_PER_DAY
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.SPEED_PERCENTS
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.core.store.captionsLanguageOrOff
import com.arshad.ayn.core.store.listedSwitches
import java.time.DayOfWeek

/** A restored value with the backup values it couldn't apply (KP-D17, D24). */
internal class Restored<T>(val value: T, val skips: List<SettingSkip>)

/** The setting values every profile has: 17 (its filter packs count as one) plus one action per
 *  SponsorBlock category (KP-D24); an export writes them all, as its results say. */
val SETTINGS_PER_PROFILE: Int = 17 + SegmentCategory.entries.size

/**
 * These settings applied over [base] (the replaced profile's, or the defaults), one field at a
 * time: a value this build can't apply keeps [base]'s and is skipped; an unknown Restricted Mode
 * level applies Strict (fails closed, KP-D18) and is skipped too; an unknown SponsorBlock category
 * is skipped with nothing kept; a known one the file lacks keeps [base]'s, as a missing chat
 * placement does. Filter packs as [restorePacks] reads them.
 */
internal fun BackupSettings.restoreOnto(base: ProfileSettings): Restored<ProfileSettings> {
    val skips = mutableListOf<SettingSkip>()
    val speed = speedPercent.takeIf { it in SPEED_PERCENTS }
        ?: base.speedPercent.also { skips += SettingSkip("speedPercent", speedPercent.toString(), it.toString()) }
    val captions = if (captionsLanguage == null || captionsLanguageOrOff(captionsLanguage) != null) {
        captionsLanguage
    } else {
        base.captionsLanguage.also { skips += SettingSkip("captionsLanguage", captionsLanguage, it) }
    }
    val actions = SegmentCategory.entries.associateWith { category ->
        val key = segmentActions[category.key] ?: return@associateWith base.segmentActions.getValue(category)
        SegmentAction.entries.firstOrNull { it.key == key }
            ?: base.segmentActions.getValue(category).also { skips += SettingSkip("segmentActions.${category.key}", key, it.key) }
    }
    segmentActions.filterKeys { SegmentCategory.of(it) == null }
        .forEach { (category, key) -> skips += SettingSkip("segmentActions.$category", key, keptValue = null) }
    val chat = chatPlacement?.let { key ->
        ChatPlacement.of(key) ?: base.chatPlacement.also { skips += SettingSkip("chatPlacement", key, it.key) }
    } ?: base.chatPlacement
    val level = restrictedModeOf("restrictedMode", restrictedMode, skips)
    val lengths = lengthLimitsOf("", minLengthMinutes, maxLengthMinutes, base.lengthLimits, skips)
    val settings = ProfileSettings(
        speedPercent = speed,
        resume = resume,
        captionsLanguage = captions,
        chatPlacement = chat,
        sponsorBlock = sponsorBlock,
        segmentActions = actions,
        skipNote = skipNote,
        dearrowTitles = dearrowTitles,
        dearrowThumbnails = dearrowThumbnails,
        dislikes = dislikes,
        showShorts = showShorts,
        forYou = forYou,
        restrictedMode = level,
        hideLive = hideLive,
        hideMembersOnly = hideMembersOnly,
        lengthLimits = lengths,
        filterPacks = filterPacks.restorePacks("filterPacks", skips),
    )
    return Restored(settings, skips)
}

/** How many setting values [settings] carries: every profile's plus each unknown category's and
 *  each unlisted Islamic channel's. */
internal fun settingsTotal(settings: BackupSettings?): Int =
    SETTINGS_PER_PROFILE +
        (settings?.segmentActions?.keys?.count { SegmentCategory.of(it) == null } ?: 0) +
        (settings?.filterPacks?.islamicChannels?.let { it.size - listedSwitches(it).size } ?: 0)

/** These packs, or all off when the file has none (FPK-D21); a channel switch for a channel this
 *  build doesn't list is skipped with nothing kept (key `<key>.islamicChannels.<id>`). */
private fun BackupFilterPacks?.restorePacks(key: String, skips: MutableList<SettingSkip>): FilterPacks {
    if (this == null) return FilterPacks()
    val listed = listedSwitches(islamicChannels)
    (islamicChannels - listed.keys).forEach { (id, on) -> skips += SettingSkip("$key.islamicChannels.$id", on.toString(), keptValue = null) }
    return FilterPacks(
        harmfulForKids = harmfulForKids,
        overstimulating = overstimulating,
        challengesAndVlogs = challengesAndVlogs,
        halalOnly = halalOnly,
        stageMagic = stageMagic,
        festivals = festivals,
        lootBoxes = lootBoxes,
        islamicOnly = islamicOnly,
        islamicChannels = listed,
    )
}

internal fun FilterPacks.toBackup(): BackupFilterPacks = BackupFilterPacks(
    harmfulForKids = harmfulForKids,
    overstimulating = overstimulating,
    challengesAndVlogs = challengesAndVlogs,
    halalOnly = halalOnly,
    stageMagic = stageMagic,
    festivals = festivals,
    lootBoxes = lootBoxes,
    islamicOnly = islamicOnly,
    islamicChannels = islamicChannels,
)

/**
 * The owner's restrictions applied over [base] (the replaced profile's, or none) as settings are
 * ([restoreOnto]); keys are `restrictions.*`. A day the file leaves out has no limit; a limit out
 * of range keeps [base]'s for that day; an unknown day is skipped with nothing kept; a bedtime
 * that isn't a window keeps [base]'s; filter packs as [restorePacks] reads them.
 */
internal fun BackupRestrictions.restoreOnto(base: ProfileRestrictions): Restored<ProfileRestrictions> {
    val skips = mutableListOf<SettingSkip>()
    val level = restrictedModeOf("restrictions.restrictedMode", restrictedMode, skips)
    val lengths = lengthLimitsOf("restrictions.", minLengthMinutes, maxLengthMinutes, base.lengthLimits, skips)
    val limits = buildMap {
        dailyLimitMinutes.forEach { (key, minutes) ->
            val day = DayOfWeek.entries.firstOrNull { it.backupKey() == key }
            when {
                day == null -> skips += SettingSkip("restrictions.dailyLimitMinutes.$key", minutes.toString(), keptValue = null)
                minutes in 0..MINUTES_PER_DAY -> put(day, minutes)
                else -> {
                    val kept = base.dailyLimitMinutes[day]
                    kept?.let { put(day, it) }
                    skips += SettingSkip("restrictions.dailyLimitMinutes.$key", minutes.toString(), kept?.toString())
                }
            }
        }
    }
    val bedtime = when {
        bedtimeStartMinute == null && bedtimeEndMinute == null -> null
        else -> Bedtime.orNull(bedtimeStartMinute, bedtimeEndMinute) ?: base.bedtime.also { kept ->
            skips += SettingSkip("restrictions.bedtime", "$bedtimeStartMinute-$bedtimeEndMinute", kept?.let { "${it.startMinute}-${it.endMinute}" })
        }
    }
    val restrictions = ProfileRestrictions(
        restrictedMode = level,
        hideShorts = hideShorts,
        hideLive = hideLive,
        hideMembersOnly = hideMembersOnly,
        lengthLimits = lengths,
        dailyLimitMinutes = limits,
        bedtime = bedtime,
        filterPacks = filterPacks.restorePacks("restrictions.filterPacks", skips),
    )
    return Restored(restrictions, skips)
}

/** The device's settings applied over [base] (this device's) as settings are; keys are `device.*`. */
internal fun BackupDevice.restoreOnto(base: DeviceSettings): Restored<DeviceSettings> {
    val skips = mutableListOf<SettingSkip>()
    val height = maxVideoHeight.takeIf { it in MAX_VIDEO_HEIGHTS }
        ?: base.maxVideoHeight.also { skips += SettingSkip("device.maxVideoHeight", maxVideoHeight.toString(), it.toString()) }
    val scale = interfaceScalePercent.takeIf { it in INTERFACE_SCALE_PERCENTS }
        ?: base.interfaceScalePercent.also { skips += SettingSkip("device.interfaceScalePercent", interfaceScalePercent.toString(), it.toString()) }
    val device = DeviceSettings(
        maxVideoHeight = height,
        frameRateMatching = frameRateMatching,
        interfaceScalePercent = scale,
        clockInPlayer = clockInPlayer,
        heroOnHome = heroOnHome,
        backgroundAudio = backgroundAudio,
    )
    return Restored(device, skips)
}

internal fun DeviceSettings.toBackup(): BackupDevice =
    BackupDevice(maxVideoHeight, frameRateMatching, interfaceScalePercent, clockInPlayer, heroOnHome, backgroundAudio)

/** `monday` … `sunday`. */
internal fun DayOfWeek.backupKey(): String = name.lowercase()

/** Off when absent; a level this build doesn't know applies Strict and is skipped. */
private fun restrictedModeOf(key: String, stored: String?, skips: MutableList<SettingSkip>): RestrictedMode {
    val level = RestrictedMode.of(stored)
    if (stored != null && stored != level.key) skips += SettingSkip(key, stored, level.key)
    return level
}

/** The pair applies only together: limits that don't make valid [LengthLimits] keep [base]'s,
 *  each value the file set skipped. */
private fun lengthLimitsOf(
    prefix: String,
    min: Int?,
    max: Int?,
    base: LengthLimits,
    skips: MutableList<SettingSkip>,
): LengthLimits {
    runCatching { LengthLimits(min, max) }.getOrNull()?.let { return it }
    min?.let { skips += SettingSkip("${prefix}minLengthMinutes", it.toString(), base.minMinutes?.toString()) }
    max?.let { skips += SettingSkip("${prefix}maxLengthMinutes", it.toString(), base.maxMinutes?.toString()) }
    return base
}
