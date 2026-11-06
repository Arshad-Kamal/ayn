package com.arshad.ayn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.session.SessionToken
import com.arshad.ayn.core.lock.RELOCK_CHECK_INTERVAL
import com.arshad.ayn.core.lock.RelockMonitor
import com.arshad.ayn.feature.kids.KidsStores
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.playback.PlaybackService
import com.arshad.ayn.ui.mobile.MobileApp
import com.arshad.ayn.ui.tv.TvApp
import com.arshad.ayn.ui.tv.common.registerFilePicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single launcher activity (D3): a TV gets the TV UI, anything else the phone's (M6-D7, one flag:
 * [AppContainer.isTv]). On a phone a re-lock keeps the playing video under the unlock (M6-D8), and
 * the playback notification's tap ([ACTION_OPEN_PLAYER]) opens the watch page. Its start, stop,
 * key presses and leaves feed the re-lock rules (M5-D9), its start and stop each ask for a sync
 * (M8-D8; each creation is a launch whose failed sync Home tells, M8-D11), and while it is started a kids profile's time counts (KP-D8): one activity, so its
 * start and stop are Ayn's. It registers the file picker on every creation, so a pick made while
 * Ayn's process was gone still lands (M5-D20). A gate first built from saved state (the process
 * died while away) judges the time away by the re-lock rules rather than as a cold start (M5-D21).
 */
class MainActivity : ComponentActivity() {
    private val relockMonitor: RelockMonitor by lazy { (application as KeApplication).container.relockMonitor }

    /** Set in [onCreate] before the gate is first read; the gate outlives a configuration change. */
    private var restarted = false

    /** The notification asked for the watch page: the phone's shell expands its player and
     *  consumes it, after an unlock if one stands between (CMP-STATE-04). Leaving Ayn drops it,
     *  so a later return doesn't open the watch page unasked. */
    private var expandPlayer by mutableStateOf(false)

    private val gate: ProfileGateViewModel by viewModels {
        val container = (application as KeApplication).container
        viewModelFactory {
            initializer {
                ProfileGateViewModel(
                    profileStore = container.profileStore,
                    locks = container.profileLocks,
                    session = container.profileSession,
                    relock = container.relockMonitor,
                    lastUsed = container.lastUsedProfile,
                    backups = container.backupStore,
                    playback = container.playbackHandle,
                    awayRecords = container.awayRecords,
                    kidsPin = container.kidsPin,
                    kidsClock = container.kidsTimer,
                    restarted = restarted,
                    timeSource = container.elapsedRealtime,
                    keepsPlaybackOnRelock = !container.isTv,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restarted = savedInstanceState != null
        val container = (application as KeApplication).container
        val chooseFile = registerFilePicker(gate::onFilePicked)
        val playerSession = { context: Context -> SessionToken(context, ComponentName(context, PlaybackService::class.java)) }
        val kids = KidsStores(container.kidsTimer, container.kidsTimeStore, container.kidsReviewKeeps, container.serverClock)
        // Before onStart asks for this launch's sync, so that sync can't end before the count it waits past.
        val syncNotice = SyncLaunchNotice(container.syncRunner.status, container.profileStore.profiles())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { container.kidsTimer.run() }
                while (true) {
                    delay(RELOCK_CHECK_INTERVAL)
                    relockMonitor.check()
                }
            }
        }
        // A recreated activity gets its first intent again: only a fresh start reads it.
        if (savedInstanceState == null) readExpandRequest(intent)
        setContent {
            if (container.isTv) {
                TvApp(
                    gate = gate,
                    extractor = container.extractor,
                    subscriptionsFeed = container.subscriptionsFeed,
                    subscriptionStore = container.subscriptionStore,
                    watchStore = container.watchStore,
                    playlistStore = container.playlistStore,
                    relatedLists = container.relatedListStore,
                    forYouStore = container.forYouStore,
                    fetchAttempts = container.fetchAttemptLog,
                    playerSession = playerSession,
                    profileStore = container.profileStore,
                    profileLocks = container.profileLocks,
                    profileSession = container.profileSession,
                    backupStore = container.backupStore,
                    profileSettings = container.profileSettingsStore,
                    filterStore = container.filterStore,
                    contentFilter = container.contentFilters.current,
                    votes = container.voteSource,
                    captions = container.captionSource,
                    liveChat = container.liveChat,
                    branding = container.cardBranding,
                    deviceSettings = container.deviceSettingsStore,
                    playback = container.playbackHandle,
                    lockTime = container.elapsedRealtime,
                    chooseFile = chooseFile,
                    syncControl = container.syncRunner,
                    syncSignIn = container.syncSignIn,
                    driveSignIn = container.driveSignIn,
                    requestSync = container.syncTriggers::request,
                    syncNotice = syncNotice,
                    approvedSearch = container.approvedSearch,
                    kids = kids,
                )
            } else {
                MobileApp(
                    gate = gate,
                    extractor = container.extractor,
                    subscriptionsFeed = container.subscriptionsFeed,
                    subscriptionStore = container.subscriptionStore,
                    watchStore = container.watchStore,
                    playlistStore = container.playlistStore,
                    relatedLists = container.relatedListStore,
                    forYouStore = container.forYouStore,
                    fetchAttempts = container.fetchAttemptLog,
                    profileSettings = container.profileSettingsStore,
                    deviceSettings = container.deviceSettingsStore,
                    branding = container.cardBranding,
                    contentFilter = container.contentFilters.current,
                    filterStore = container.filterStore,
                    profileStore = container.profileStore,
                    profileLocks = container.profileLocks,
                    lockTime = container.elapsedRealtime,
                    chooseFile = chooseFile,
                    backupStore = container.backupStore,
                    profileSession = container.profileSession,
                    playerSession = playerSession,
                    playback = container.playbackHandle,
                    votes = container.voteSource,
                    captions = container.captionSource,
                    liveChat = container.liveChat,
                    syncControl = container.syncRunner,
                    syncSignIn = container.syncSignIn,
                    driveSignIn = container.driveSignIn,
                    requestSync = container.syncTriggers::request,
                    syncNotice = syncNotice,
                    approvedSearch = container.approvedSearch,
                    kids = kids,
                    expandPlayer = expandPlayer,
                    onPlayerExpanded = { expandPlayer = false },
                )
            }
        }
    }

    /** singleTask: the notification's tap on a running Ayn lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExpandRequest(intent)
    }

    private fun readExpandRequest(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) expandPlayer = true
    }

    override fun onStart() {
        super.onStart()
        relockMonitor.onStart()
        screenStarted(true)
    }

    /** After [onStart]'s re-lock decision, which the first frame back already shows. */
    override fun onResume() {
        super.onResume()
        content.alpha = 1f
    }

    /** The Shield's recents keeps a snapshot of KE's last screen (M5-D9): for a locked profile
     *  the system is told not to take one, and the content goes too, so a return shows nothing
     *  before the re-lock decision. Alpha, not visibility: an invisible content view would drop
     *  Compose's focus. */
    override fun onPause() {
        val locked = relockMonitor.sessionLocked
        if (!setRecentsPreviewEnabled(!locked) && locked) Log.w(LOCK_TAG, "recents snapshot not disabled")
        if (locked) content.alpha = 0f
        super.onPause()
    }

    override fun onStop() {
        expandPlayer = false
        relockMonitor.onStop()
        screenStarted(false)
        super.onStop()
    }

    private fun screenStarted(started: Boolean) {
        (application as KeApplication).container.run {
            screenStarted = started
            syncTriggers.request()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        relockMonitor.onUserInteraction()
        (application as KeApplication).container.kidsTimer.onUserInteraction()
    }

    private val content: View get() = findViewById(android.R.id.content)

    /** HOME reaches no key callback; leaving on purpose is activity, a screensaver isn't. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        relockMonitor.onUserLeaveHint()
    }

    companion object {
        /** The playback notification's tap: the phone shows the watch page, not the mini player. */
        const val ACTION_OPEN_PLAYER = "com.arshad.ayn.action.OPEN_PLAYER"

        private const val LOCK_TAG = "KE-lock"
    }
}
