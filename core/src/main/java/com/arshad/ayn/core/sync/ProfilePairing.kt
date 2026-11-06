package com.arshad.ayn.core.sync

import com.arshad.ayn.core.backup.BackupPlaylist
import com.arshad.ayn.core.backup.BackupProfile
import com.arshad.ayn.core.backup.foldAsciiCase

/**
 * This device's profiles against the linked devices' at its first sync (M8-D5, M8-D13), as sync
 * ids. A pair whose ids differ is one profile under two ids until the apply rewrites the local one
 * (M8.6).
 */
internal data class Pairing(
    /** Local profile → the remote profile it is. */
    val profiles: Map<String, String>,
    /** The pairs in [profiles] no choice changes: the same sync id, owner with owner. */
    val fixed: Map<String, String>,
    /** By paired local profile: local playlist → the remote playlist it is. */
    val playlists: Map<String, Map<String, String>>,
    /** Local profiles no remote one matched, in local order: the ones to ask about. */
    val unpairedLocal: List<String>,
    /** Remote profiles no local one matched, in remote order. */
    val unpairedRemote: List<String>,
)

/**
 * Pairs [local] with [remote] (every linked device's profiles; a profile on several devices comes
 * once per device): the same sync id first and owner with owner, which no choice changes; then the
 * owner's [choices] (M8-D18: a pair of two other non-owners of one kind; a profile kept apart
 * pairs with none; a skipped remote profile pairs with none); then the same name (ASCII case
 * folded, as the database compares names) and kind, as a restore's Replace target is (KP-D24).
 * Inside a pair, playlists by sync id, then by the same name. A remote profile or playlist on
 * several devices matches as its newest copy, as the merge would keep it.
 */
internal fun pairProfiles(local: List<BackupProfile>, remote: List<BackupProfile>, choices: MatchChoices = MatchChoices()): Pairing {
    val remoteCopies = remote.groupBy { it.requireSyncId() }
    val remoteRows = remoteCopies.values.map { it.newestRow() }
    val fixed = pairInOrder(
        local,
        remoteRows,
        BackupProfile::requireSyncId,
        { mine, theirs -> mine.syncId == theirs.syncId },
        { mine, theirs -> mine.owner && theirs.owner },
    )
    val profiles = pairInOrder(
        local,
        remoteRows.filterNot { it.syncId in choices.skipped },
        BackupProfile::requireSyncId,
        { mine, theirs -> !mine.owner && !theirs.owner && mine.kids == theirs.kids && mine.name.foldAsciiCase() == theirs.name.foldAsciiCase() },
        chosen = fixed + chosenPairs(local, remoteRows, choices, fixed),
        apart = choices.apart,
    )
    return Pairing(
        profiles = profiles,
        fixed = fixed,
        playlists = local.filter { it.requireSyncId() in profiles }.associate { mine ->
            val theirs = remoteCopies.getValue(profiles.getValue(mine.requireSyncId()))
                .flatMap { it.playlists }
                .groupBy { it.requireSyncId() }
                .values.map { it.newestRow() }
            mine.requireSyncId() to pairInOrder(
                mine.playlists,
                theirs,
                BackupPlaylist::requireSyncId,
                { a, b -> a.syncId == b.syncId },
                { a, b -> a.name == b.name },
            )
        },
        unpairedLocal = local.map { it.requireSyncId() }.filter { it !in profiles },
        unpairedRemote = remoteCopies.keys.filter { it !in profiles.values },
    )
}

/** The [choices] pairs of two non-owners of one kind, neither of them already in [fixed] and the
 *  remote one not skipped, each remote profile once; none for a profile kept apart. */
private fun chosenPairs(local: List<BackupProfile>, remote: List<BackupProfile>, choices: MatchChoices, fixed: Map<String, String>): Map<String, String> {
    val localById = local.filter { !it.owner && it.syncId !in fixed }.associateBy { it.requireSyncId() }
    val remoteById = remote.filter { !it.owner && it.syncId !in fixed.values && it.syncId !in choices.skipped }.associateBy { it.requireSyncId() }
    val pairs = LinkedHashMap<String, String>()
    choices.pairs.forEach { (localId, remoteId) ->
        val mine = localById[localId] ?: return@forEach
        val theirs = remoteById[remoteId] ?: return@forEach
        if (mine.kids == theirs.kids && remoteId !in pairs.values && localId !in choices.apart) pairs[localId] = remoteId
    }
    return pairs
}

/** Local id → remote id: [chosen] as given, then each rule in turn pairs every still unpaired
 *  [local] item not [apart], in order, with the first still unpaired [remote] one it matches. */
private fun <T> pairInOrder(
    local: List<T>,
    remote: List<T>,
    id: (T) -> String,
    vararg rules: (T, T) -> Boolean,
    chosen: Map<String, String> = emptyMap(),
    apart: Set<String> = emptySet(),
): Map<String, String> {
    val pairs = LinkedHashMap(chosen)
    for (matches in rules) {
        for (mine in local) {
            if (id(mine) in pairs || id(mine) in apart) continue
            val theirs = remote.firstOrNull { id(it) !in pairs.values && matches(mine, it) } ?: continue
            pairs[id(mine)] = id(theirs)
        }
    }
    return pairs
}
