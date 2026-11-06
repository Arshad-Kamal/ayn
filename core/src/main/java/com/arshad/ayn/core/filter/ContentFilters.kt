package com.arshad.ayn.core.filter

import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The session profile's [ContentFilter] (KP-D20) and whether it shows Shorts (PAF-D6). The settings
 * store follows the session, so the toggles and the rules read are always one profile's. A failed read fails the caller: nothing
 * passes a filter that couldn't be read (KP-D1). The last filter built is reused while its
 * settings and rules stay equal, so its keywords and patterns compile once per rules snapshot.
 */
class ContentFilters(
    private val session: ProfileSession,
    settings: ProfileSettingsStore,
    private val filters: FilterStore,
) {
    private val last = AtomicReference<Built?>(null)

    /** The filter and whether Shorts show, from one settings emission, so the two are always one
     *  profile's. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val snapshot: Flow<FilterSnapshot> = settings.settings()
        .flatMapLatest { state ->
            filters.rules(state.profileId).map { rules ->
                val filter = built(state.settings, rules)
                FilterSnapshot(filter, shortsShown = !filter.hidesShorts && state.settings.showShorts)
            }
        }
        .distinctUntilChanged()

    val current: Flow<ContentFilter> = snapshot.map { it.filter }.distinctUntilChanged()

    /** The filter for a request starting now; [ContentFilter.REFUSE_ALL] before any profile opens. */
    suspend fun now(): ContentFilter = if (session.currentIdOrNull == null) ContentFilter.REFUSE_ALL else current.first()

    /** [FilterSnapshot.shortsShown] for a request starting now; `false` before any profile opens. */
    suspend fun shortsShownNow(): Boolean = session.currentIdOrNull != null && snapshot.first().shortsShown

    private fun built(settings: ProfileSettings, rules: ProfileRules): ContentFilter {
        last.get()?.takeIf { it.settings == settings && it.rules == rules }?.let { return it.filter }
        return ContentFilter.of(settings, rules).also { last.set(Built(settings, rules, it)) }
    }

    private class Built(val settings: ProfileSettings, val rules: ProfileRules, val filter: ContentFilter)
}

/**
 * [filter] and [shortsShown]: whether the profile shows Shorts (PAF-D6), its Show Shorts on and its
 * filter not hiding them (the owner's Hide Shorts, a kids profile). The full rule; the feature
 * layer's `showsShorts()` reads the switch alone, for lists whose filter already drops Shorts.
 */
internal data class FilterSnapshot(val filter: ContentFilter, val shortsShown: Boolean)
