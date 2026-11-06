package com.arshad.ayn.core.store

import android.util.Log
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.lock.OwnerPass
import com.google.re2j.PatternSyntaxException
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.google.re2j.Pattern as Re2Pattern

/**
 * [FilterStore] over Room. A rule is checked once here, at the write ([validRule]); a count check
 * and its insert share one write transaction, so two adds can't both pass the cap. A removed rule
 * or approved channel leaves a removal record for sync (M8-D6).
 */
class FilterRepository(
    private val database: KeDatabase,
    private val clock: Clock = Clock.System,
) : FilterStore {
    private val dao: FilterDao = database.filterDao()
    private val subscriptionDao: SubscriptionDao = database.subscriptionDao()

    /** A kids profile's approved channels are its subscriptions (KP-D10); other profiles carry none,
     *  so their subscribing re-emits nothing. */
    override fun rules(profileId: Long): Flow<ProfileRules> =
        combine(
            dao.restrictions(profileId),
            dao.rules(profileId),
            dao.kids(profileId),
            dao.subscribedChannels(profileId),
        ) { restrictions, rules, kids, channels ->
            val isKids = kids == true
            ProfileRules(
                restrictions = restrictions?.toRestrictions() ?: ProfileRestrictions(),
                rules = rules.mapNotNull { row -> row.toRule()?.let { StoredRule(it, row.byOwner, row.addedAtMillis) } },
                kids = isKids,
                approvedChannels = if (isKids) channels.toSet() else emptySet(),
            )
        }.distinctUntilChanged()

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
    ): Boolean = write("restrictions write", fallback = false) {
        database.withWriteTransaction {
            val current = dao.restrictionsRow(profileId)?.toRestrictions() ?: ProfileRestrictions()
            dao.upsertRestrictions(transform(current).toEntity(profileId, clock.now().toEpochMilliseconds()))
        }
        true
    }

    override fun approvedChannels(profileId: Long): Flow<List<Subscription>> =
        combine(dao.kids(profileId), subscriptionDao.subscriptions(profileId)) { kids, rows ->
            if (kids == true) rows.map(SubscriptionEntity::toSubscription) else emptyList()
        }

    override suspend fun approveChannel(owner: OwnerPass, profileId: Long, channel: ChannelItem): Boolean =
        write("channel approval", fallback = false) {
            database.withWriteTransaction {
                val kids = database.profileDao().isKids(profileId)
                if (kids) subscriptionDao.upsert(channel.toSubscriptionEntity(profileId, clock.now().toEpochMilliseconds()))
                kids
            }
        }

    override suspend fun removeApprovedChannel(owner: OwnerPass, profileId: Long, channelUrl: String): Boolean =
        write("channel approval removal", fallback = false) {
            database.withWriteTransaction {
                if (!database.profileDao().isKids(profileId)) return@withWriteTransaction false
                val key = canonicalChannelUrl(channelUrl)
                database.removalDao().subscription(profileId, key, clock.now().toEpochMilliseconds())
                subscriptionDao.delete(profileId, key) > 0
            }
        }

    private suspend fun add(profileId: Long, rule: ContentRule, byOwner: Boolean): RuleEdit {
        val valid = validRule(rule) ?: return RuleEdit.Invalid
        val cap = if (valid is Pattern) MAX_PATTERNS else MAX_RULES_PER_KIND
        return write<RuleEdit>("rule write", fallback = RuleEdit.Failed) {
            database.withWriteTransaction {
                when {
                    dao.count(profileId, byOwner, valid.kind) >= cap -> RuleEdit.Full
                    dao.insertRule(valid.toEntity(profileId, byOwner, clock.now().toEpochMilliseconds())) == NOT_INSERTED ->
                        RuleEdit.AlreadyThere
                    else -> RuleEdit.Added
                }
            }
        }
    }

    private suspend fun remove(profileId: Long, rule: ContentRule, byOwner: Boolean): Boolean {
        val key = validRule(rule)?.key ?: return false
        return write("rule removal", fallback = false) {
            database.withWriteTransaction {
                database.removalDao().rule(profileId, byOwner, rule.kind, key, clock.now().toEpochMilliseconds())
                dao.deleteRule(profileId, byOwner, rule.kind, key) > 0
            }
        }
    }

    private suspend fun <T> write(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: SQLiteException) {
        Log.w(LOG_TAG, "$what failed: ${e.javaClass.simpleName}")
        fallback
    }

    private companion object {
        const val LOG_TAG = "KeFilters"
    }
}

/**
 * [rule] as stored, or `null` when the store can't use it: channels by their canonical URL, video
 * ids a [VideoId] accepts, keywords trimmed and within [MAX_KEYWORD_LENGTH], patterns within
 * [MAX_PATTERN_LENGTH] that compile ([patternProblem]; kept as entered: spaces matter in a
 * pattern). Blank names read as none.
 */
internal fun validRule(rule: ContentRule): ContentRule? = when (rule) {
    is BlockedChannel -> rule.channelUrl.trim().takeIf { it.isNotEmpty() }
        ?.let { BlockedChannel(canonicalChannelUrl(it), rule.name.orNullIfBlank()) }
    is BlockedVideo -> rule.takeIf { VideoId.isValid(it.videoId) }
        ?.copy(title = rule.title.orNullIfBlank(), channelUrl = rule.channelUrl.orNullIfBlank()?.let(::canonicalChannelUrl))
    is Keyword -> rule.word.trim().takeIf { it.length in 1..MAX_KEYWORD_LENGTH }?.let(::Keyword)
    is Pattern -> rule.takeIf { it.pattern.isNotBlank() && it.pattern.length <= MAX_PATTERN_LENGTH && patternProblem(it.pattern) == null }
    is ApprovedVideo -> rule.takeIf { VideoId.isValid(it.videoId) }
        ?.copy(channelUrl = rule.channelUrl.orNullIfBlank()?.let(::canonicalChannelUrl))
}

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Why [pattern] can't be saved, or `null` when it compiles as the filter runs it (KP-D7:
 *  checked when saved). */
fun patternProblem(pattern: String): PatternProblem? = try {
    Re2Pattern.compile(pattern, Re2Pattern.CASE_INSENSITIVE)
    null
} catch (e: PatternSyntaxException) {
    PatternProblem(description = e.description, near = e.pattern.ifEmpty { null })
}

/** RE2/J's reason ("missing closing )") and the part of the pattern it stopped at, when known. */
data class PatternProblem(val description: String, val near: String?)
