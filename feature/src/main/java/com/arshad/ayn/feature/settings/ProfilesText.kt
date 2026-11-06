package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.ProfileContents

// Settings › Profiles text both UIs show (`b3-profiles` v8; Q7: the history count reads "in history").

/** What a profile keeps: its subscriptions and playlists when it has any, then its history. */
fun countsText(contents: ProfileContents): String = listOfNotNull(
    contents.subscriptionCount.takeIf { it > 0 }?.let { plural(it, "subscription") },
    contents.playlistCount.takeIf { it > 0 }?.let { plural(it, "playlist") },
    "${contents.historyCount} in history",
).joinToString(" · ")

/** The Add a profile row's hint: how many of the five there are. */
fun profilesCountText(count: Int): String = "$count of $MAX_PROFILES"

/** What removing [row]'s profile deletes (`b3-profiles` remove): the counts it has, Watch later
 *  always; a kids profile's subscriptions are its approved channels, then its approved videos. */
fun removeText(row: ProfileRow): String {
    val contents = row.contents
    val parts = listOfNotNull(
        contents.subscriptionCount.takeIf { it > 0 }?.let { plural(it, if (row.profile.isKids) "approved channel" else "subscription") },
        row.kid?.approvedVideos?.takeIf { it > 0 }?.let { plural(it, "approved video") },
        contents.playlistCount.takeIf { it > 0 }?.let { plural(it, "playlist") },
        "Watch later",
        contents.historyCount.takeIf { it > 0 }?.let { "${plural(it, "video")} from history" },
    )
    return "This deletes ${row.profile.name}’s ${spokenList(parts)}. It can’t be undone."
}

/** The row of profile [id], `null` once it has gone. */
fun ProfilesUiState.rowOf(id: Long): ProfileRow? = rows.firstOrNull { it.profile.id == id }

/** What a profile keeps, for a row by id ([ProfileContents.Empty] when not counted). */
fun ProfilesUiState.contentsOf(id: Long): ProfileContents = rowOf(id)?.contents ?: ProfileContents.Empty
