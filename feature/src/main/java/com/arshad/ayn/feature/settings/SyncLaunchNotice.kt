package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** How long the launch notice stays up (M8-D11). */
const val LAUNCH_NOTICE_MS: Long = 8_000L

/**
 * A failed sync, told at launch over Home (M8-D11): [failure] and the link's [account]; whether the
 * open profile [isOwner], and the owner's name ([ownerName], `null` without an owner), whom anyone
 * else tells.
 */
data class LaunchNotice(val failure: SyncFailure, val account: SyncAccount?, val isOwner: Boolean, val ownerName: String?)

/**
 * One launch's failed-sync notice (M8-D11): once a sync has ended after it was built
 * ([SyncStatus.checks]), the last sync's outcome when Home shows, so a failure from before the
 * launch is told only if this launch's sync fails too, and one healed since isn't. Build one per
 * activity before its start asks for a sync; the notice is taken once.
 */
class SyncLaunchNotice(private val status: StateFlow<SyncStatus>, private val profiles: Flow<List<Profile>>) {
    private val checksAtLaunch = status.value.checks
    private var taken = false

    /**
     * Waits for this launch's sync, then the notice for the open [profileId]: `null` when the last
     * sync worked, while unlinked or awaiting Match, or once a notice was taken. Call it where Home
     * shows; a caller cancelled while waiting leaves the notice for the next.
     */
    suspend fun take(profileId: Long): LaunchNotice? {
        if (taken) return null
        val ended = status.first { it.checks > checksAtLaunch && !it.syncing }
        val all = profiles.first()
        if (taken) return null
        taken = true
        val failure = ended.failure?.takeIf { !ended.awaitingMatch } ?: return null
        val me = all.firstOrNull { it.id == profileId }
        return LaunchNotice(failure, ended.account, isOwner = me?.isOwner == true, ownerName = all.firstOrNull { it.isOwner }?.name)
    }
}
