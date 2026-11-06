package com.arshad.ayn.core.filter

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A filter read once and used by every [FilteringExtractor] call made under it, in place of a
 * fresh read per call: a subscriptions load of hundreds of channels reads the profile's filter
 * once (KP-D15's "captured when the call starts", stretched to the load). A rule change applies
 * from the next load; a load never mixes two filters.
 */
class PinnedFilter(val filter: ContentFilter) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PinnedFilter>
}
