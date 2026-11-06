package com.arshad.ayn.ui.tv

import com.arshad.ayn.feature.profiles.PickedFile
import com.arshad.ayn.feature.profiles.SettingsPicks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The gate's side of [SettingsPicks] for screen tests: it offers the open shell [waiting]. */
internal class TestPicks : SettingsPicks {
    val waiting = MutableStateFlow<PickedFile?>(null)
    override val settingsPick: StateFlow<PickedFile?> = waiting

    override fun takeSettingsPick(pick: PickedFile): Boolean = waiting.compareAndSet(pick, null)
}
