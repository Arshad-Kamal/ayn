package com.arshad.ayn.core.sync

import android.content.Context
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.ProfileSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * The app's [SyncRunner]: secrets in the Keystore-sealed `sync` preferences, the link in
 * `sync-state`, folders reached on [httpClient]. [deviceName] (the model) names this device's
 * files; [openProfileId] is the session's profile; [beforeSnapshot] saves the player's place;
 * [google] is the build's Google client ([googleClientOrNull]), `null` without one.
 */
fun syncRunner(
    context: Context,
    database: KeDatabase,
    httpClient: OkHttpClient,
    profileDefaults: ProfileSettings,
    deviceName: String,
    google: GoogleClient?,
    openProfileId: () -> Long?,
    beforeSnapshot: suspend () -> Unit,
): SyncRunner = SyncRunner(
    secrets = SyncSecrets(context),
    state = SyncState(context),
    data = SyncDatabase(database, profileDefaults = profileDefaults),
    storageFor = { credential -> storageFor(credential, httpClient, google) },
    deviceName = deviceName,
    openProfileId = openProfileId,
    beforeSnapshot = beforeSnapshot,
)

/** `null` for a stored folder address that no longer reads as one, or a Drive link in a build
 *  without a Google client. */
private fun storageFor(credential: SyncCredential, httpClient: OkHttpClient, google: GoogleClient?): SyncStorage? = when (credential) {
    is SyncCredential.WebDav -> credential.folder.toHttpUrlOrNull()?.let { WebDavStorage(WebDavAccount(it, credential.user, credential.password), httpClient) }
    is SyncCredential.GoogleDrive -> google?.let { client ->
        val login = GoogleLogin(httpClient, client)
        DriveStorage({ login.accessToken(credential.refreshToken) }, httpClient)
    }
}
