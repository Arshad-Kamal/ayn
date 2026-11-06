package com.arshad.ayn.core.kids

import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.flow.StateFlow

/** The session profile's watch time as the gate and the shell read it (KP-D8, D22); [KidsTimer]
 *  keeps it. */
interface KidsClock {
    /** The session profile's time as of the last tick. */
    val time: StateFlow<KidsTime>

    /** Minutes left that the screen should note (a [WARNING_MINUTES] mark); it stays until
     *  [consumeWarning] (CMP-STATE-04). */
    val warning: StateFlow<Int?>

    fun consumeWarning()

    /** The owner gives the session's kids profile [minutes] more today; `false` when no clock is
     *  known yet or the write failed. */
    suspend fun addMoreTime(owner: OwnerPass, minutes: Int): Boolean

    /** The session kids profile's daily limit tomorrow, in minutes; `null` for no limit, or while
     *  no clock is known. */
    suspend fun limitTomorrow(): Int?
}
