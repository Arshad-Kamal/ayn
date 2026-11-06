package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.store.KidsReviewKeeps
import com.arshad.ayn.core.store.KidsTimeStore

/** What a kids profile's shell needs beyond the shared stores: the timer ([clock]), the time
 *  ledger, the review's keeps and YouTube's clock (KP-D8, D22, F12). */
class KidsStores(
    val clock: KidsClock,
    val time: KidsTimeStore,
    val reviewKeeps: KidsReviewKeeps,
    val serverClock: ServerClock,
)
