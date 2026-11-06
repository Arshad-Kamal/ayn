package com.arshad.ayn.ui.tv.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.session.SessionToken
import com.arshad.ayn.feature.player.PlaybackView
import com.arshad.ayn.feature.player.PlayerConnection

/**
 * The destination's controller on the session [sessionToken] names, bound again for each [attempt];
 * released when the destination leaves, pausing first so no video plays outside this screen (D1/TVU-9).
 */
@Composable
internal fun rememberPlayerConnection(sessionToken: (Context) -> SessionToken, attempt: Int): PlayerConnection {
    val context = LocalContext.current
    val connection = remember(attempt) { PlayerConnection() }
    DisposableEffect(connection) {
        val release = connection.bind(context, sessionToken)
        onDispose { release() }
    }
    return connection
}

@Composable
internal fun rememberPlaybackView(player: Player, videoId: String): PlaybackView {
    val view = remember(player) { PlaybackView(player) }
    DisposableEffect(view) {
        view.sync()
        player.addListener(view)
        onDispose { player.removeListener(view) }
    }
    LaunchedEffect(view, videoId) { view.request(videoId) }
    return view
}
