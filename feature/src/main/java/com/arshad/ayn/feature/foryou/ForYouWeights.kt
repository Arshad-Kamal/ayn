package com.arshad.ayn.feature.foryou

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Every fixed weight and threshold of For you's seeds and ranking (FY-D2, FY-D3, FY-D7), in one
 * place; Flow's eight time buckets are a definition, kept with the seeds (`TimeBucket`). `ForYouRankerTest` and `ForYouSeedsTest` pin the orders they produce, worked by hand.
 */
object ForYouWeights {
    /** Seeds per build (FY-D2's 6–8). */
    const val SEED_COUNT = 8

    /** Seeds sharing one channel. */
    const val SEEDS_PER_CHANNEL = 2

    /** Below this many real watches, recent subscription uploads seed For you too. */
    const val FEW_WATCHES = 5

    /** Subscription uploads seeding a profile with fewer than [FEW_WATCHES] real watches. */
    const val FOLLOWED_SEEDS = 2

    /** Shorter videos are Shorts: FY never shows one, nor seeds from one. A watch entry seeds only
     *  with a known length this long, which also keeps out live streams (their length is unknown). */
    const val MIN_LENGTH_SECONDS = 60L

    /** A history seed watched to the end or this far in counts as finished. */
    const val MOSTLY_WATCHED = 0.5

    const val FINISHED_SEED = 1.0
    const val PARTLY_WATCHED_SEED = 0.6
    const val SAVED_SEED = 0.7
    const val FOLLOWED_SEED = 0.5

    /** A seed's weight halves every [SEED_HALF_LIFE] since it was watched, saved or uploaded. */
    val SEED_HALF_LIFE: Duration = 7.days

    /** Real watches in a time bucket before its seeds are boosted (FY-D2, Q8). */
    const val BUCKET_MIN_WATCHES = 5

    /** A seed last watched in the current time bucket, once that bucket has enough history. */
    const val BUCKET_BOOST = 1.5

    /** A candidate's place in a seed's list: rank r (0 first) adds `1 / (1 + r × PLACE_DECAY)` of
     *  the seed's weight, so a video in several seeds' lists sums them (seed overlap). */
    const val PLACE_DECAY = 0.1

    /** Channel affinity per real watch of the channel, for a subscription, and its cap. */
    const val AFFINITY_PER_WATCH = 0.1
    const val AFFINITY_SUBSCRIBED = 0.3
    const val AFFINITY_CAP = 0.5

    /** Freshness: up to this much more for a new upload, halving every [FRESH_HALF_LIFE]. */
    const val FRESH_BOOST = 0.3
    val FRESH_HALF_LIFE: Duration = 30.days

    /** Novelty's share of the score at no skips, and how far skips raise it (Flow's 0.2 → 0.7):
     *  each skip adds 1/[SKIPS_TO_FULL_RAMP] of [NOVELTY_RAMP]. */
    const val NOVELTY_BASE = 0.2
    const val NOVELTY_RAMP = 0.5
    const val SKIPS_TO_FULL_RAMP = 10

    /** Within novelty, a new channel's share; a less-used seed has the rest. */
    const val NEW_CHANNEL_SHARE = 0.5

    /** A channel's open rate counts once this many of its For you cards were shown. */
    const val CHANNEL_MIN_SHOWN = 3

    /** The multiplier of a channel whose shown cards were never opened (Flow's 0.4×); it rises
     *  along a sigmoid centred on [CHANNEL_RATE_MIDPOINT] to 1. */
    const val IGNORED_CHANNEL = 0.4
    const val CHANNEL_RATE_MIDPOINT = 0.25
    const val CHANNEL_RATE_STEEPNESS = 12.0

    /** Cards of one channel among the picks, the hero's included. */
    const val PICKS_PER_CHANNEL = 2

    /** The hero and the row's cards. */
    const val MAX_PICKS = 13

    /** An unopened card shown within this long drops for now (rotation). */
    val ROTATION: Duration = 12.hours

    /** The hero stays the same pick this long at most (FY-D7). */
    val PIN_HOLD: Duration = 6.hours
}
