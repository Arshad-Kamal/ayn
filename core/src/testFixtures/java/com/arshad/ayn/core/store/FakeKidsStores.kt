package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.lock.OwnerPass
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [FilterStore] for the layers above the store: [rulesById] is what [rules] emits per
 * profile (none for an id it lacks). Owner rules are appended as stored owner rules (no
 * validation, no caps); a rule already there answers [RuleEdit.AlreadyThere]. While [failWrites]
 * is set every write fails. Approved channels are [approved] per profile, the latest first.
 */
class FakeFilterStore : FilterStore {
    val rulesById: MutableStateFlow<Map<Long, ProfileRules>> = MutableStateFlow(emptyMap())
    val approved: MutableStateFlow<Map<Long, List<Subscription>>> = MutableStateFlow(emptyMap())
    var failWrites = false

    override fun rules(profileId: Long): Flow<ProfileRules> = rulesById.map { it[profileId] ?: ProfileRules() }

    override suspend fun addOwn(profileId: Long, rule: SelfRule): RuleEdit = add(profileId, rule, byOwner = false)

    override suspend fun removeOwn(profileId: Long, rule: SelfRule): Boolean = remove(profileId, rule, byOwner = false)

    override suspend fun addRestriction(owner: OwnerPass, profileId: Long, rule: ContentRule): RuleEdit =
        add(profileId, rule, byOwner = true)

    override suspend fun removeRestriction(owner: OwnerPass, profileId: Long, rule: ContentRule): Boolean =
        remove(profileId, rule, byOwner = true)

    override suspend fun updateRestrictions(
        owner: OwnerPass,
        profileId: Long,
        transform: (ProfileRestrictions) -> ProfileRestrictions,
    ): Boolean {
        if (failWrites) return false
        edit(profileId) { it.copy(restrictions = transform(it.restrictions)) }
        return true
    }

    override fun approvedChannels(profileId: Long): Flow<List<Subscription>> = approved.map { it[profileId].orEmpty() }

    override suspend fun approveChannel(owner: OwnerPass, profileId: Long, channel: ChannelItem): Boolean {
        if (failWrites) return false
        val key = canonicalChannelUrl(channel.url)
        val subscription = Subscription(key, channel.name, channel.avatarUrl, subscribedAtMillis = 0, updatedAtMillis = 0)
        val all = approved.value
        approved.value = all + (profileId to listOf(subscription) + all[profileId].orEmpty().filterNot { it.channelUrl == key })
        return true
    }

    override suspend fun removeApprovedChannel(owner: OwnerPass, profileId: Long, channelUrl: String): Boolean {
        if (failWrites) return false
        val key = canonicalChannelUrl(channelUrl)
        val all = approved.value
        val channels = all[profileId].orEmpty()
        if (channels.none { it.channelUrl == key }) return false
        approved.value = all + (profileId to channels.filterNot { it.channelUrl == key })
        return true
    }

    private fun add(profileId: Long, rule: ContentRule, byOwner: Boolean): RuleEdit {
        if (failWrites) return RuleEdit.Failed
        val current = rulesById.value[profileId] ?: ProfileRules()
        if (current.rules.any { it.rule == rule && it.byOwner == byOwner }) return RuleEdit.AlreadyThere
        edit(profileId) { it.copy(rules = it.rules + StoredRule(rule, byOwner, addedAtMillis = 0)) }
        return RuleEdit.Added
    }

    private fun remove(profileId: Long, rule: ContentRule, byOwner: Boolean): Boolean {
        if (failWrites) return false
        val current = rulesById.value[profileId] ?: return false
        // As the real store: by setter, kind and key, whatever name or title the rule carries.
        fun matches(stored: StoredRule): Boolean = stored.byOwner == byOwner && stored.rule::class == rule::class && stored.rule.key == rule.key
        if (current.rules.none(::matches)) return false
        edit(profileId) { rules -> rules.copy(rules = rules.rules.filterNot(::matches)) }
        return true
    }

    private fun edit(profileId: Long, change: (ProfileRules) -> ProfileRules) {
        val all = rulesById.value
        rulesById.value = all + (profileId to change(all[profileId] ?: ProfileRules()))
    }
}

/** In-memory [KidsTimeStore]: [daysById] is each profile's ledger, kept as the real store keeps
 *  it (More time per KP-D22), without dropping old days. */
class FakeKidsTimeStore : KidsTimeStore {
    val daysById: MutableStateFlow<Map<Long, Map<LocalDate, DayTime>>> = MutableStateFlow(emptyMap())

    override fun days(profileId: Long, from: LocalDate, through: LocalDate): Flow<Map<LocalDate, DayTime>> =
        daysById.map { all -> all[profileId].orEmpty().filterKeys { it in from..through } }

    override suspend fun addUsed(profileId: Long, day: LocalDate, seconds: Long): Boolean {
        edit(profileId, day) { it.copy(usedSeconds = it.usedSeconds + seconds) }
        return true
    }

    override suspend fun addMoreTime(owner: OwnerPass, profileId: Long, day: LocalDate, minutes: Int): Boolean {
        if (minutes !in 1..MINUTES_PER_DAY) return false
        edit(profileId, day) {
            it.copy(
                moreTimeMinutes = it.moreTimeMinutes + minutes,
                allowedUntilSeconds = maxOf(it.usedSeconds, it.allowedUntilSeconds) + minutes * 60L,
            )
        }
        return true
    }

    private fun edit(profileId: Long, day: LocalDate, change: (DayTime) -> DayTime) {
        val all = daysById.value
        val days = all[profileId].orEmpty()
        daysById.value = all + (profileId to days + (day to change(days[day] ?: DayTime())))
    }
}

/** In-memory [KidsReviewKeeps]: [keptById] per profile, never pruned; while [failWrites] is set a
 *  keep isn't written. */
class FakeKidsReviewKeeps : KidsReviewKeeps {
    val keptById: MutableMap<Long, MutableSet<String>> = mutableMapOf()
    var failWrites = false

    override suspend fun kept(profileId: Long): Set<String> = keptById[profileId].orEmpty().toSet()

    override suspend fun keep(owner: OwnerPass, profileId: Long, videoId: String): Boolean {
        if (failWrites) return false
        keptById.getOrPut(profileId) { mutableSetOf() } += videoId
        return true
    }
}
