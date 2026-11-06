package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.backup.ProfileCounts
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.RestoredProfile
import com.arshad.ayn.core.backup.SettingSkip
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import org.junit.Assert.assertEquals
import org.junit.Test

/** A restore's results as the rows and skip cards draw them. */
class BackupRowsTest {

    private val skip = SettingSkip("speedPercent", "225", "125")
    private val result = RestoreResult.Restored(
        ownerId = 1,
        profiles = listOf(restoredProfile(1, "Arshad", replaced = true), restoredProfile(9, "Zaid", replaced = false, skip)),
        deviceSkips = listOf(SettingSkip("device.maxVideoHeight", "4320", "2160")),
    )
    private val here = listOf(Profile(1, "Arshad", ProfileTint.Teal, isOwner = true, lock = null))

    @Test
    fun aRestoredRowTakesItsLookFromTheProfileHereAndAmberWhenItIsGone() {
        val rows = restoredRows(result, here, firstLaunch = false)

        assertEquals(listOf("Arshad", "Zaid"), rows.map { it.name })
        assertEquals(listOf(ProfileTint.Teal, ProfileTint.Amber), rows.map { it.tint })
        assertEquals(listOf(true, false), rows.map { it.owner })
        assertEquals(listOf("Replaced", "Added as new"), rows.map { it.how })
        assertEquals(listOf("24 of 24", "23 of 24"), rows.map { it.settings })
        assertEquals(listOf(0, 1), rows.map { it.settingsKept })
    }

    @Test
    fun skipsListEachProfilesThenTheDevicesWithNoOwner() {
        val skips = shownSkips(result, here)

        assertEquals(listOf("Zaid", null), skips.map { it.owner })
        assertEquals(listOf(ProfileTint.Amber, null), skips.map { it.tint })
        assertEquals(listOf("Playback speed", "Quality cap"), skips.map { it.text.label })
    }

    private fun restoredProfile(id: Long, name: String, replaced: Boolean, vararg skips: SettingSkip) =
        RestoredProfile(id, name, replaced, ProfileCounts(0, 0, 0, 0, 0, 0), settingsApplied = 24 - skips.size, settingsTotal = 24, skips = skips.toList())
}
