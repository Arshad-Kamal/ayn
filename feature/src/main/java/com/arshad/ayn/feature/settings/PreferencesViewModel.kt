package com.arshad.ayn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.DeviceSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.SegmentAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings' preference pages (`b3-settings`: Playback, SponsorBlock, DeArrow, Dislikes,
 * Interface, About): the session profile's settings and the TV's (B-D9). A change to a
 * profile's settings goes to the profile whose settings show (M7-D9); only the owner changes the
 * TV's (Q17); a failed write leaves the stored value showing. A sheet opens only from the page
 * (All languages from Captions, a licence's text from Licences, and back returns there), and a
 * choice in it closes it. Main thread.
 */
class PreferencesViewModel(
    private val profileSettings: ProfileSettingsStore,
    private val deviceSettings: DeviceSettingsStore,
    profileStore: ProfileStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PreferencesUiState())
    val uiState: StateFlow<PreferencesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { profileSettings.settings().collect { state -> _uiState.update { it.copy(profile = state) } } }
        viewModelScope.launch { deviceSettings.settings().collect { device -> _uiState.update { it.copy(device = device) } } }
        viewModelScope.launch {
            profileStore.profiles().collect { profiles ->
                val owner = profiles.firstOrNull { it.isOwner }
                _uiState.update { it.copy(ownerId = owner?.id, ownerName = owner?.name) }
            }
        }
    }

    fun onSwitch(switch: ProfileSwitch, on: Boolean) {
        updateProfile { it.with(switch, on) }
    }

    fun onSwitch(switch: DeviceSwitch, on: Boolean) {
        updateDevice { it.with(switch, on) }
    }

    fun onOpenSpeed() {
        if (_uiState.value.profile != null) open(PreferencesSheet.Speed)
    }

    /** [percent] is one of `SPEED_PERCENTS`. */
    fun onSpeedPicked(percent: Int) {
        if (sheet != PreferencesSheet.Speed) return
        close()
        updateProfile { it.copy(speedPercent = percent) }
    }

    fun onOpenQuality() {
        if (_uiState.value.changesTheTv && _uiState.value.device != null) open(PreferencesSheet.Quality)
    }

    /** [maxHeight] is one of `MAX_VIDEO_HEIGHTS`. */
    fun onQualityPicked(maxHeight: Int) {
        if (sheet != PreferencesSheet.Quality) return
        close()
        updateDevice { it.copy(maxVideoHeight = maxHeight) }
    }

    fun onOpenInterfaceSize() {
        if (_uiState.value.changesTheTv && _uiState.value.device != null) open(PreferencesSheet.InterfaceSize)
    }

    /** [percent] is one of `INTERFACE_SCALE_CHOICES`; it applies at once (Q59). */
    fun onInterfaceSizePicked(percent: Int) {
        if (sheet != PreferencesSheet.InterfaceSize) return
        close()
        updateDevice { it.copy(interfaceScalePercent = percent) }
    }

    fun onOpenCaptions() {
        if (_uiState.value.profile != null) open(PreferencesSheet.Captions)
    }

    fun onOpenAllLanguages() {
        if (sheet == PreferencesSheet.Captions) show(PreferencesSheet.AllLanguages)
    }

    /** [language] is a language tag, or `null` for Off; from Captions or All languages. */
    fun onCaptionsPicked(language: String?) {
        if (sheet != PreferencesSheet.Captions && sheet != PreferencesSheet.AllLanguages) return
        close()
        updateProfile { it.copy(captionsLanguage = language) }
    }

    fun onOpenLiveChat() {
        if (_uiState.value.profile != null) open(PreferencesSheet.LiveChat)
    }

    fun onChatPlacementPicked(placement: ChatPlacement) {
        if (sheet != PreferencesSheet.LiveChat) return
        close()
        updateProfile { it.copy(chatPlacement = placement) }
    }

    /** A category's sheet; its row is out of reach while SponsorBlock is off. */
    fun onOpenCategory(category: SegmentCategory) {
        if (_uiState.value.profile?.settings?.sponsorBlock == true) open(PreferencesSheet.Category(category))
    }

    fun onActionPicked(action: SegmentAction) {
        val category = (sheet as? PreferencesSheet.Category)?.category ?: return
        close()
        updateProfile { it.copy(segmentActions = it.segmentActions + (category to action)) }
    }

    fun onOpenLicences() {
        open(PreferencesSheet.Licences)
    }

    /** [part] is the part's name in the Licences sheet. */
    fun onOpenLicenceText(part: String) {
        if (sheet == PreferencesSheet.Licences) show(PreferencesSheet.LicenceText(part))
    }

    fun onOpenSourceCode() {
        open(PreferencesSheet.SourceCode)
    }

    /** A sheet swiped away: it closes from any stage (Back steps back instead). */
    fun onDismiss() {
        close()
    }

    fun onBack() {
        when (sheet) {
            PreferencesSheet.AllLanguages -> show(PreferencesSheet.Captions)
            is PreferencesSheet.LicenceText -> show(PreferencesSheet.Licences)
            else -> close()
        }
    }

    private val sheet: PreferencesSheet? get() = _uiState.value.sheet

    private fun open(sheet: PreferencesSheet) {
        _uiState.update { if (it.sheet == null) it.copy(sheet = sheet) else it }
    }

    private fun show(sheet: PreferencesSheet) {
        _uiState.update { it.copy(sheet = sheet) }
    }

    private fun close() {
        _uiState.update { it.copy(sheet = null) }
    }

    private fun updateProfile(transform: (ProfileSettings) -> ProfileSettings) {
        val profileId = _uiState.value.profile?.profileId ?: return
        viewModelScope.launch { profileSettings.update(profileId, transform) }
    }

    private fun updateDevice(transform: (DeviceSettings) -> DeviceSettings) {
        if (!_uiState.value.changesTheTv) return
        viewModelScope.launch { deviceSettings.update(transform) }
    }
}

private fun ProfileSettings.with(switch: ProfileSwitch, on: Boolean): ProfileSettings = when (switch) {
    ProfileSwitch.Resume -> copy(resume = on)
    ProfileSwitch.SponsorBlock -> copy(sponsorBlock = on)
    ProfileSwitch.SkipNote -> copy(skipNote = on)
    ProfileSwitch.DeArrowTitles -> copy(dearrowTitles = on)
    ProfileSwitch.DeArrowThumbnails -> copy(dearrowThumbnails = on)
    ProfileSwitch.Dislikes -> copy(dislikes = on)
    ProfileSwitch.ShowShorts -> copy(showShorts = on)
    ProfileSwitch.ForYou -> copy(forYou = on)
}

private fun DeviceSettings.with(switch: DeviceSwitch, on: Boolean): DeviceSettings = when (switch) {
    DeviceSwitch.FrameRateMatching -> copy(frameRateMatching = on)
    DeviceSwitch.ClockInPlayer -> copy(clockInPlayer = on)
    DeviceSwitch.HeroOnHome -> copy(heroOnHome = on)
    DeviceSwitch.BackgroundAudio -> copy(backgroundAudio = on)
}
