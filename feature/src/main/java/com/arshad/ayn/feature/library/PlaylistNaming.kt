package com.arshad.ayn.feature.library

import com.arshad.ayn.core.store.MAX_PLAYLIST_NAME

/**
 * A playlist name being typed (`b3-library` newpl, newplsave, plrename). The field holds at most
 * [MAX_PLAYLIST_NAME] characters; Done does nothing while the name is blank or a save is under way.
 */
data class PlaylistNaming(val name: String = "", val saving: Boolean = false) {
    val canSave: Boolean get() = name.isNotBlank() && !saving

    /** [typed], or this naming unchanged when [typed] is too long or a save is under way. */
    fun edited(typed: String): PlaylistNaming = if (saving || typed.length > MAX_PLAYLIST_NAME) this else copy(name = typed)
}
