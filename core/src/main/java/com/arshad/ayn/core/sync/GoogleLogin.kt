package com.arshad.ayn.core.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response

/** Ayn's OAuth client (a "TVs and Limited Input devices" one): its secret ships in the app and,
 *  in Google's words, "is not treated as a secret". Not a data class: it stays out of `toString`. */
class GoogleClient internal constructor(internal val id: String, internal val secret: String)

/** The build's Google client ([id] and [secret] from `local.properties`); `null` when it has none,
 *  which hides Google Drive (M8-D1). */
fun googleClientOrNull(id: String, secret: String): GoogleClient? = if (id.isBlank() || secret.isBlank()) null else GoogleClient(id, secret)

/** Where Google's sign-in and Drive answer; a test points both at its own server. */
internal class GoogleEndpoints(
    val oauth: HttpUrl = "https://oauth2.googleapis.com/".toHttpUrl(),
    val api: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
)

/** A started device sign-in: the user opens [verificationUrl] on a phone and types [userCode]
 *  within [expiresInSeconds], while Ayn polls every [intervalSeconds]. */
internal class DeviceStart(
    val userCode: String,
    val verificationUrl: HttpUrl,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
    internal val deviceCode: String,
)

internal sealed interface DevicePoll {
    /** The user hasn't answered yet. */
    data object Pending : DevicePoll

    /** Polled too often: wait longer between polls. */
    data object SlowDown : DevicePoll

    /** The user refused Ayn access. */
    data object Denied : DevicePoll

    /** The code's time ran out: start again. */
    data object Expired : DevicePoll

    /** Not a data class: the tokens stay out of `toString`. */
    class Granted(val refreshToken: String, val accessToken: String) : DevicePoll
}

/**
 * Google's OAuth 2.0 device flow (spec 20 Decisions, recorded 2026-11-02): [start] gets a code
 * for the user to approve on a phone, [poll] asks whether they did, [accessToken] turns the
 * device's refresh token into an hour's access token. Redirects aren't followed, as in
 * [WebDavStorage]: OkHttp would resend a redirected POST as a GET without its form.
 */
internal class GoogleLogin(
    client: OkHttpClient,
    private val oauthClient: GoogleClient,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val client = client.newBuilder().followRedirects(false).build()

    /** Any answer but a code is [StorageFailure.SERVER_ERROR] (a quota or a refused client). */
    suspend fun start(): DeviceStart {
        val form = FormBody.Builder().add("client_id", oauthClient.id).add("scope", DRIVE_FILE_SCOPE).build()
        return client.exchange(syncRequest(oauthUrl("device/code")).post(form).build(), io) { response ->
            if (!response.isSuccessful) failWith(StorageFailure.SERVER_ERROR)
            val answer = response.decode(CodeAnswer.serializer())
            DeviceStart(
                userCode = answer.userCode,
                verificationUrl = answer.verificationUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: failWith(StorageFailure.SERVER_ERROR),
                expiresInSeconds = answer.expiresIn,
                intervalSeconds = answer.interval,
                deviceCode = answer.deviceCode,
            )
        }
    }

    /** The user's answer so far, read from the error code alone (each is unique to one answer,
     *  RFC 8628 3.5). A 5xx is Google's hiccup, so the user hasn't answered as far as Ayn knows
     *  ([DevicePoll.Pending]); any other answer is [StorageFailure.SERVER_ERROR]. */
    suspend fun poll(start: DeviceStart): DevicePoll {
        val form = clientForm().add("device_code", start.deviceCode).add("grant_type", DEVICE_CODE_GRANT).build()
        return client.exchange(syncRequest(oauthUrl("token")).post(form).build(), io) { response ->
            if (response.isSuccessful) {
                val tokens = response.decode(TokenAnswer.serializer())
                return@exchange DevicePoll.Granted(tokens.refreshToken ?: failWith(StorageFailure.SERVER_ERROR), tokens.accessToken)
            }
            if (response.code >= HTTP_SERVER_ERROR) return@exchange DevicePoll.Pending
            when (response.decode(ErrorAnswer.serializer()).error) {
                "authorization_pending" -> DevicePoll.Pending
                "slow_down" -> DevicePoll.SlowDown
                "access_denied" -> DevicePoll.Denied
                "expired_token" -> DevicePoll.Expired
                else -> failWith(StorageFailure.SERVER_ERROR)
            }
        }
    }

    /** A fresh access token for [refreshToken]; one revoked, expired (7 days while the Google
     *  project is in Testing) or unused for 6 months is [StorageFailure.SIGN_IN_REJECTED]. */
    suspend fun accessToken(refreshToken: String): String {
        val form = clientForm().add("refresh_token", refreshToken).add("grant_type", "refresh_token").build()
        return client.exchange(syncRequest(oauthUrl("token")).post(form).build(), io) { response ->
            when {
                response.isSuccessful -> response.decode(TokenAnswer.serializer()).accessToken
                response.code == HTTP_BAD_REQUEST && response.decode(ErrorAnswer.serializer()).error == "invalid_grant" ->
                    failWith(StorageFailure.SIGN_IN_REJECTED)
                else -> failWith(StorageFailure.SERVER_ERROR)
            }
        }
    }

    /** The address of the account [accessToken] signs in to (Drive's `about`, which any Drive scope
     *  may read): Settings › Sync shows it, and it tells two accounts' folders apart. */
    suspend fun accountEmail(accessToken: String): String {
        val url = endpoints.api.newBuilder().addPathSegments("drive/v3/about").addQueryParameter("fields", "user(emailAddress)").build()
        val request = syncRequest(url).header("Authorization", "Bearer $accessToken").build()
        return client.exchange(request, io) { response ->
            when {
                response.isSuccessful -> response.decodeWithin(AboutAnswer.serializer(), MAX_ANSWER_BYTES, ABOUT_DEPTH).user.emailAddress
                    .takeIf { it.isNotBlank() } ?: failWith(StorageFailure.SERVER_ERROR)
                response.code == HTTP_UNAUTHORIZED -> failWith(StorageFailure.SIGN_IN_REJECTED)
                else -> failWith(StorageFailure.SERVER_ERROR)
            }
        }
    }

    private fun clientForm(): FormBody.Builder = FormBody.Builder().add("client_id", oauthClient.id).add("client_secret", oauthClient.secret)

    private fun oauthUrl(path: String): HttpUrl = endpoints.oauth.newBuilder().addPathSegments(path).build()

    private fun <T> Response.decode(deserializer: DeserializationStrategy<T>): T = decodeWithin(deserializer, MAX_ANSWER_BYTES, ANSWER_DEPTH)

    @Serializable
    private class CodeAnswer(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String,
        @SerialName("expires_in") val expiresIn: Long,
        val interval: Long = DEFAULT_INTERVAL_SECONDS,
    )

    @Serializable
    private class TokenAnswer(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
    )

    @Serializable
    private class ErrorAnswer(val error: String? = null)

    @Serializable
    private class AboutAnswer(val user: User) {
        @Serializable
        class User(val emailAddress: String)
    }

    private companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

        /** RFC 8628 3.2's default when the answer names none. */
        const val DEFAULT_INTERVAL_SECONDS = 5L

        /** The recorded sign-in answers were under 400 bytes and nest 1 deep; 2 leaves headroom. */
        const val MAX_ANSWER_BYTES = 64L * 1024
        const val ANSWER_DEPTH = 2

        /** `{"user": {"emailAddress": …}}` nests 2 deep; 3 leaves headroom. */
        const val ABOUT_DEPTH = 3

        const val HTTP_BAD_REQUEST = 400
        const val HTTP_SERVER_ERROR = 500
    }
}
