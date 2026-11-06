package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.store.FetchOutcome
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.feature.forYouOn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException

/**
 * Tops up the related lists of a ranking's seeds (FY-D4, FY-D5) through the one-request related
 * call, each request asked of the [budget] and its outcome recorded there. What it fetches is kept
 * in [relatedLists] for the next ranking: Home never changes For you while it shows (FY-D9).
 */
class ForYouRefresher(
    private val extractor: Extractor,
    private val relatedLists: RelatedListStore,
    private val settings: ProfileSettingsStore,
    private val budget: FetchBudget,
    private val clock: Clock = Clock.System,
) {
    /**
     * Fetches the lists [seeds] (heaviest first) lack, in that order, until none is left, the
     * budget says no or the profile's For you is off (FY-D10). A seed is skipped while its kept
     * list is fresh ([RelatedList.isFresh]) or its last failed fetch is under [SEED_BACKOFF] old,
     * read just before its turn: playback may have kept its list meanwhile. A rate-limit answer
     * marks no seed: the budget's stop ends the run. A store or log that can't be read or written
     * throws.
     */
    suspend fun topUp(seeds: List<VideoId>) {
        for (seed in seeds.distinct()) {
            if (!isDue(seed)) continue
            if (!settings.forYouOn() || !budget.allows()) return
            fetch(seed)
        }
    }

    private suspend fun isDue(seed: VideoId): Boolean {
        val kept = relatedLists.lists(listOf(seed))[seed] ?: return true
        val now = clock.now()
        return !kept.isFresh(now) && kept.failedAt.let { it == null || now - it >= SEED_BACKOFF }
    }

    private suspend fun fetch(seed: VideoId) {
        val items = try {
            extractor.related(seed)
        } catch (e: AccessException) {
            val limited = e.failure == AccessFailure.RATE_LIMITED
            budget.record(if (limited) FetchOutcome.RateLimited else FetchOutcome.Failed, e.failure)
            if (!limited) relatedLists.recordFailure(seed)
            return
        } catch (e: CancellationException) {
            budget.recordCutOff()
            throw e
        }
        budget.record(FetchOutcome.Fetched)
        relatedLists.record(seed, items)
    }

    companion object {
        /** How long a seed whose fetch failed waits before the next (FY-D5). */
        val SEED_BACKOFF: Duration = 24.hours
    }
}
