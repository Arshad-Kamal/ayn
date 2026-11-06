package com.arshad.ayn.core.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A started sign-in: the user opens [loginUrl] on any device and grants access while Ayn polls. */
internal class LoginStart(val loginUrl: HttpUrl, internal val pollEndpoint: HttpUrl, internal val pollToken: String)

/** What the user granted: the [server] to use, the name they signed in with and Ayn's app password. */
internal class NextcloudGrant(val server: HttpUrl, val loginName: String, val appPassword: String)

/**
 * Nextcloud's Login Flow v2 (spec 20 Decisions, recorded against Nextcloud 31): [start] asks the
 * server for a login page and a poll token, [poll] asks whether the user has granted access yet.
 * Both calls are anonymous, so no answer of theirs means a rejected sign-in: any failure answer is
 * [StorageFailure.SERVER_ERROR]. Redirects aren't followed, as in [WebDavStorage]: OkHttp would
 * resend a redirected POST as a GET without the poll token, and a 404 there would read as pending.
 * The grant is a per-device app password the user can revoke from the server's web page; the
 * device then syncs in the [SYNC_FOLDER] of the user's files, named by the user id [account] asks
 * for.
 */
internal class NextcloudLogin(
    client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val client = client.newBuilder().followRedirects(false).build()

    /** Starts a sign-in at [server], the address the user gave (a subfolder install included).
     *  Any answer but a login page is [StorageFailure.SERVER_ERROR]: no Nextcloud there. */
    suspend fun start(server: HttpUrl): LoginStart {
        val request = syncRequest(server.newBuilder().addPathSegments("index.php/login/v2").build())
            .post(ByteArray(0).toRequestBody())
            .build()
        return client.exchange(request, io) { response ->
            if (!response.isSuccessful) failWith(StorageFailure.SERVER_ERROR)
            val answer = response.decode(StartAnswer.serializer(), LOGIN_DEPTH)
            LoginStart(
                loginUrl = answer.login.toHttpUrlOrNull() ?: failWith(StorageFailure.SERVER_ERROR),
                pollEndpoint = answer.poll.endpoint.toHttpUrlOrNull() ?: failWith(StorageFailure.SERVER_ERROR),
                pollToken = answer.poll.token,
            )
        }
    }

    /**
     * The grant once the user has granted access; `null` until then, and also after the grant was
     * collected or the token's 20 minutes ran out (the server answers all three alike), so the
     * caller bounds its polling. The endpoint is the one [start]'s answer gave.
     */
    suspend fun poll(start: LoginStart): NextcloudGrant? {
        val request = syncRequest(start.pollEndpoint)
            .post(FormBody.Builder().add("token", start.pollToken).build())
            .build()
        return client.exchange(request, io) { response ->
            when {
                response.code == HTTP_NOT_FOUND -> null
                !response.isSuccessful -> failWith(StorageFailure.SERVER_ERROR)
                else -> response.decode(GrantAnswer.serializer(), LOGIN_DEPTH).let { answer ->
                    NextcloudGrant(answer.server.toHttpUrlOrNull() ?: failWith(StorageFailure.SERVER_ERROR), answer.loginName, answer.appPassword)
                }
            }
        }
    }

    /**
     * [grant]'s account, its files folder named by the user id the OCS user call gives: an LDAP or
     * SSO login name can differ from it (a local account's email sign-in is granted the id
     * already). The call sends the app password: the caller checks [NextcloudGrant.server] is
     * https first. Any failure answer, or an id that can't name a folder, is
     * [StorageFailure.SERVER_ERROR].
     */
    suspend fun account(grant: NextcloudGrant): WebDavAccount {
        val url = grant.server.newBuilder().addPathSegments("ocs/v2.php/cloud/user").addQueryParameter("format", "json").build()
        val request = syncRequest(url)
            .header("Authorization", Credentials.basic(grant.loginName, grant.appPassword, Charsets.UTF_8))
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()
        return client.exchange(request, io) { response ->
            if (!response.isSuccessful) failWith(StorageFailure.SERVER_ERROR)
            val id = response.decode(UserAnswer.serializer(), USER_DEPTH).ocs.data.id
            // One path segment: empty, "." or ".." would name another folder than the user's.
            if (id.isBlank() || id == "." || id == ".." || '/' in id) failWith(StorageFailure.SERVER_ERROR)
            val files = grant.server.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(id).build()
            WebDavAccount(syncFolderIn(files), grant.loginName, grant.appPassword)
        }
    }

    private fun <T> Response.decode(deserializer: DeserializationStrategy<T>, maxDepth: Int): T =
        decodeWithin(deserializer, MAX_ANSWER_BYTES, maxDepth)

    @Serializable
    private class StartAnswer(val poll: Poll, val login: String) {
        @Serializable
        class Poll(val token: String, val endpoint: String)
    }

    @Serializable
    private class GrantAnswer(val server: String, val loginName: String, val appPassword: String)

    @Serializable
    private class UserAnswer(val ocs: Ocs) {
        @Serializable
        class Ocs(val data: Data)

        @Serializable
        class Data(val id: String)
    }

    private companion object {
        /** The recorded login answers were under 400 bytes, the user answers under 1.2 KB. */
        const val MAX_ANSWER_BYTES = 64L * 1024

        /** The start and poll answers nest at most 2 deep; 4 leaves headroom. */
        const val LOGIN_DEPTH = 4

        /** The user answer nests 4 deep (`ocs.data.quota`). */
        const val USER_DEPTH = 6
    }
}
