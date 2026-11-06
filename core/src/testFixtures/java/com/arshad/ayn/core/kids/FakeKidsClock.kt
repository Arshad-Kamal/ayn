package com.arshad.ayn.core.kids

import com.arshad.ayn.core.lock.OwnerPass
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [KidsClock]: tests set [time] and [warning] directly. More time is recorded in
 * [grants]; it lifts [time] to [KidsTime.Left] of the grant, as the timer's tick does, unless
 * [failGrants] is set; [grantGate], when set, holds a grant. [tomorrow] is [limitTomorrow]'s answer.
 */
class FakeKidsClock : KidsClock {
    override val time: MutableStateFlow<KidsTime> = MutableStateFlow(KidsTime.Unlimited)
    override val warning: MutableStateFlow<Int?> = MutableStateFlow(null)
    val grants: MutableList<Int> = mutableListOf()
    var failGrants = false
    var grantGate: CompletableDeferred<Unit>? = null
    var tomorrow: Int? = null

    override fun consumeWarning() {
        warning.value = null
    }

    override suspend fun addMoreTime(owner: OwnerPass, minutes: Int): Boolean {
        grantGate?.await()
        if (failGrants) return false
        grants += minutes
        time.value = KidsTime.Left(minutes.minutes)
        return true
    }

    override suspend fun limitTomorrow(): Int? = tomorrow
}
