package com.arshad.ayn.ui.tv

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.arshad.ayn.core.access.SearchExtractor
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.core.sync.FakeDriveSignIn
import com.arshad.ayn.core.sync.FakeSyncControl
import com.arshad.ayn.core.sync.FakeSyncSignIn
import com.arshad.ayn.ui.tv.profiles.ShellOpening
import com.arshad.ayn.ui.tv.shell.SettingsModels
import kotlin.time.TimeSource
import org.junit.Assert.assertEquals

/** The player shows, under [NoPlayerSession]: its "didn't answer" statement, or a live card's slate. */
internal fun ComposeTestRule.assertPlayerShows() {
    val shown = onAllNodes(hasText("The player didn’t answer") or hasText("Live streams aren’t supported yet")).fetchSemanticsNodes()
    assertEquals("the player's statement", 1, shown.size)
}

/** The open profile a shell test runs as: unlocked, so its rail slot reads "Switch profile". */
internal val TestProfile = Profile(id = 1, name = "Arshad", tint = ProfileTint.Amber, isOwner = true, lock = null)

/** [TestProfile]'s shell opening (not after a restore). */
internal val TestOpening = ShellOpening(TestProfile, restored = null, settingsPickWaiting = false, onRestoredShown = {}, onRailProfile = {})

/** Settings' ViewModels for a shell test: [TestProfile] alone, unlocked, over [backups], with
 *  the gate's [picks]. */
internal fun testSettings(backups: FakeBackupStore = FakeBackupStore(), picks: TestPicks = TestPicks()): SettingsModels = SettingsModels(
    profiles = { ProfilesViewModel(FakeProfileStore(listOf(TestProfile)), FakeProfileLocks(), FakeFilterStore(), ProfileSession().apply { select(TestProfile.id) }, TimeSource.Monotonic) },
    backup = { profileId ->
        BackupViewModel(
            backups = backups,
            profileStore = FakeProfileStore(listOf(TestProfile)),
            locks = FakeProfileLocks(),
            playback = {},
            handover = { _, _ -> },
            picks = picks,
            profileId = profileId,
            timeSource = TimeSource.Monotonic,
        )
    },
    preferences = { PreferencesViewModel(FakeProfileSettingsStore(TestProfile.id), FakeDeviceSettingsStore(), FakeProfileStore(listOf(TestProfile))) },
    filters = { FiltersViewModel(FakeProfileSettingsStore(TestProfile.id), FakeFilterStore(), FakeProfileStore(listOf(TestProfile)), FakeProfileLocks()) },
    restrictions = { RestrictionsViewModel(FakeFilterStore(), FakeProfileStore(listOf(TestProfile)), FakeProfileLocks(), TimeSource.Monotonic) },
    kidsAdd = { testKidsAdd(FakeProfileStore(listOf(TestProfile)), FakeProfileLocks()) },
    sync = { profileId -> testSync(FakeProfileStore(listOf(TestProfile)), FakeProfileLocks(), profileId) },
    chooseFile = {},
)

/** A kids profile's add flow over [store] and [locks] for a Settings test: no subscriptions,
 *  and a search that answers nothing unless the test sets [extractor]'s answers. */
internal fun testKidsAdd(
    store: ProfileStore,
    locks: ProfileLocks,
    filters: FakeFilterStore = FakeFilterStore(),
    extractor: SearchExtractor = SearchExtractor(),
): KidsAddViewModel = KidsAddViewModel(store, locks, filters, FakeSubscriptionStore(), extractor, TimeSource.Monotonic)

/** Settings › Sync for [profileId] over [store] and [locks] for a Settings test: unlinked, over
 *  [sync], [signIn] and [drive] (the test's when it drives them; `null` hides Drive); [requestSync]
 *  counts Sync now. */
internal fun testSync(
    store: ProfileStore,
    locks: ProfileLocks,
    profileId: Long,
    sync: FakeSyncControl = FakeSyncControl(),
    signIn: FakeSyncSignIn = FakeSyncSignIn(),
    drive: FakeDriveSignIn? = null,
    requestSync: () -> Unit = {},
): SyncViewModel = SyncViewModel(sync, signIn, drive, store, locks, requestSync, profileId, TimeSource.Monotonic)

internal val TestSettings = testSettings()
