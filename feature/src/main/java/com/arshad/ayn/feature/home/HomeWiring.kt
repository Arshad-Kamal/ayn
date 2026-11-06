package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.foryou.FetchBudget
import com.arshad.ayn.feature.foryou.ForYouBuilder
import com.arshad.ayn.feature.foryou.ForYouRefresher
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** How long a For you card sits in view before it counts as shown (FY-D3), in both UIs. */
const val SHOWN_AFTER_MS: Long = 2_000L

/** Home's ViewModel over the app's stores, with For you's builder, request budget and refresher
 *  (FY-D5); [kidsPicks] for a kids profile's Home; [compute] for For you's ranking and the watch
 *  store's reads. Both UIs' shells build it here. */
fun homeViewModel(
    extractor: Extractor,
    feed: SubscriptionsFeed,
    subscriptionStore: SubscriptionStore,
    watchStore: WatchStore,
    playlistStore: PlaylistStore,
    relatedLists: RelatedListStore,
    forYouStore: ForYouStore,
    fetchAttempts: FetchAttemptLog,
    profileSettings: ProfileSettingsStore,
    kidsPicks: Flow<KidsPicks>? = null,
    compute: CoroutineDispatcher = Dispatchers.Default,
): HomeViewModel {
    val builder = ForYouBuilder(watchStore, playlistStore, subscriptionStore, relatedLists, forYouStore, profileSettings, compute = compute)
    val budget = FetchBudget(fetchAttempts)
    val refresher = ForYouRefresher(extractor, relatedLists, profileSettings, budget)
    return HomeViewModel(extractor, feed, subscriptionStore, watchStore, playlistStore, relatedLists, builder, forYouStore, budget, refresher, kidsPicks, compute = compute)
}
