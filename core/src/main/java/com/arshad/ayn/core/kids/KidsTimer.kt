package com.arshad.ayn.core.kids

import android.util.Log
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.DayTime
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.KidsTimeStore
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSession
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A kids profile's watch time (KP-D8, D22). While [run] runs (Ayn is on screen) it ticks every
 * [TICK_INTERVAL]: a tick counts the time since the last one when the session profile is a kids
 * profile, its time isn't up, and a key was pressed or a video played within [ACTIVE_WINDOW]; it
 * writes the count under the day [clock] gives (until the clock is known the count waits) and
 * publishes [time]. Reaching Time's up or bedtime ends playback ([endPlayback]); the 5 and 1
 * minute marks publish a [warning]. The resolve asks [current], which reads the stores afresh.
 * Main thread only.
 */
class KidsTimer(
    private val session: ProfileSession,
    private val filters: FilterStore,
    private val ledger: KidsTimeStore,
    private val clock: ServerClock,
    /** A monotonic clock that keeps counting through deep sleep (`elapsedRealtime`). */
    private val elapsed: () -> Duration,
    private val endPlayback: () -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : KidsClock {
    private val published = MutableStateFlow<KidsTime>(KidsTime.Unlimited)

    override val time: StateFlow<KidsTime> = published.asStateFlow()

    private val pendingWarning = MutableStateFlow<Int?>(null)

    override val warning: StateFlow<Int?> = pendingWarning.asStateFlow()

    private val ticking = Mutex()
    private var lastTickAt: Duration = elapsed()
    private var lastActiveAt: Duration = lastTickAt
    private var playing = false

    /** The profile [uncounted] belongs to. */
    private var countedProfile: Long? = null
    private var uncounted: Duration = Duration.ZERO
    private var lastWarned: Int? = null

    fun onUserInteraction() {
        lastActiveAt = elapsed()
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        lastActiveAt = elapsed()
    }

    override fun consumeWarning() {
        pendingWarning.value = null
    }

    /** Counts while Ayn is on screen; call it from the started lifecycle. The time before it isn't
     *  counted, and its cancellation counts and writes the last stretch. A profile switch ticks at
     *  once, so its time shows without waiting for the next tick. */
    suspend fun run(): Unit = coroutineScope {
        val startedAt = elapsed()
        lastTickAt = startedAt
        lastActiveAt = startedAt // the key that brought Ayn back
        launch { session.selectedId.collect { tick() } }
        try {
            while (true) {
                delay(TICK_INTERVAL)
                tick()
            }
        } finally {
            withContext(NonCancellable) { tick() }
        }
    }

    /** The session profile's time now, for the resolve gate (KP-D22): not counted, read afresh. */
    suspend fun current(): KidsTime {
        val profileId = session.currentId()
        val rules = filters.rules(profileId).first()
        if (!rules.kids) return KidsTime.Unlimited
        val now = localNow() ?: return KidsTime.Unknown
        return timeOf(profileId, rules, now)
    }

    override suspend fun addMoreTime(owner: OwnerPass, minutes: Int): Boolean {
        val profileId = session.currentIdOrNull ?: return false
        val today = localNow()?.toLocalDate() ?: return false
        val added = ledger.addMoreTime(owner, profileId, today, minutes)
        if (added) tick()
        return added
    }

    override suspend fun limitTomorrow(): Int? {
        val profileId = session.currentIdOrNull ?: return null
        val tomorrow = localNow()?.toLocalDate()?.plusDays(1) ?: return null
        return filters.rules(profileId).first().restrictions.dailyLimitMinutes[tomorrow.dayOfWeek]
    }

    /** A failed store read or write leaves the last published time and is logged; the resolve's
     *  own read ([current]) fails closed. */
    internal suspend fun tick() {
        try {
            ticking.withLock { tickLocked() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "tick failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun tickLocked() {
        val at = elapsed()
        // Capped: a stretch longer than two ticks is time the loop didn't run (the device slept).
        val stretch = (at - lastTickAt).coerceIn(Duration.ZERO, TICK_INTERVAL * 2)
        lastTickAt = at
        val profileId = session.currentIdOrNull
        // The stretch since the last tick was the last profile's: a switch doesn't count it here.
        val switched = profileId != countedProfile
        if (switched) switchTo(profileId)
        if (profileId == null) return publish(KidsTime.Unlimited)
        val rules = filters.rules(profileId).first()
        if (!rules.kids) return publish(KidsTime.Unlimited)
        val active = playing || at - lastActiveAt <= ACTIVE_WINDOW
        if (active && !switched && !published.value.stopped) uncounted += stretch
        val now = localNow() ?: return publish(KidsTime.Unknown)
        val seconds = uncounted.inWholeSeconds
        if (seconds > 0 && ledger.addUsed(profileId, now.toLocalDate(), seconds)) uncounted -= seconds.seconds
        publish(timeOf(profileId, rules, now))
    }

    /** Time counted for the last profile and not yet written is dropped: before the clock is
     *  known nothing plays (only browsing time), and a write that keeps failing loses at most what
     *  it held when the profile changed. */
    private fun switchTo(profileId: Long?) {
        countedProfile = profileId
        uncounted = Duration.ZERO
        lastWarned = null
        pendingWarning.value = null
        published.value = KidsTime.Unlimited
    }

    /** Counted time a failed write left [uncounted] still counts: a store that can't be written
     *  never holds the limit off. */
    private suspend fun timeOf(profileId: Long, rules: ProfileRules, now: LocalDateTime): KidsTime {
        val today = now.toLocalDate()
        val stored = ledger.days(profileId, today, today).first()[today] ?: DayTime()
        val unwritten = if (profileId == countedProfile) uncounted.inWholeSeconds else 0
        return kidsTime(rules.restrictions, stored.copy(usedSeconds = stored.usedSeconds + unwritten), now)
    }

    private fun publish(time: KidsTime) {
        val before = published.value
        published.value = time
        if (time.stopped && !before.stopped) endPlayback()
        // More time lifted the time above the first mark: its warnings come again.
        if (time is KidsTime.Left && time.left > WARNING_MINUTES.first().minutes) lastWarned = null
        dueWarning(time, lastWarned)?.let { due ->
            lastWarned = due
            pendingWarning.value = due
        }
    }

    private fun localNow(): LocalDateTime? = clock.now()?.toJavaInstant()?.atZone(zone())?.toLocalDateTime()

    companion object {
        private const val LOG_TAG = "KeKidsTime"

        val TICK_INTERVAL: Duration = 15.seconds

        /** Time counts while a key was pressed or a video played this recently (KP-D8). */
        val ACTIVE_WINDOW: Duration = 5.minutes
    }
}
