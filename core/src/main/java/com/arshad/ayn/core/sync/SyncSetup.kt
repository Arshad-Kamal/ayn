package com.arshad.ayn.core.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** A folder's sign-in as this device keeps it (M8-D15, sealed by [SyncSecrets]); made by
 *  [webDavCredential], [NextcloudSignIn] or [GoogleDriveSignIn]. */
@Serializable
sealed interface SyncCredential {
    /** A WebDAV folder (Nextcloud's or another server's). Not a data class: the password stays
     *  out of `toString`. */
    @Serializable
    @SerialName("webdav")
    class WebDav internal constructor(
        val folder: String,
        val user: String,
        val password: String,
        /** Signed in through Nextcloud's Login Flow, so Sign in again runs it again. */
        val nextcloud: Boolean = false,
    ) : SyncCredential

    /** The "Ayn sync" folder in [email]'s My Drive ([DriveStorage]). Not a data class: the token
     *  stays out of `toString`. */
    @Serializable
    @SerialName("drive")
    class GoogleDrive internal constructor(val refreshToken: String, val email: String) : SyncCredential
}

enum class SyncService { GOOGLE_DRIVE, NEXTCLOUD, WEBDAV }

/** Who this device syncs as, for Settings › Sync (M8-D18): not secret, kept beside the link
 *  ([StoredAccount]). [server] is the WebDAV host; empty for Google Drive, which the UI names. */
data class SyncAccount(val service: SyncService, val server: String, val user: String)

/** [SyncAccount] as the link stores it: [service] by name, read leniently, so a later build's
 *  service can't make the whole link unreadable here. */
@Serializable
internal data class StoredAccount(val service: String, val server: String, val user: String) {
    /** `null` for a service this build doesn't know. */
    fun account(): SyncAccount? = SyncService.entries.firstOrNull { it.name == service }?.let { SyncAccount(it, server, user) }
}

internal fun SyncAccount.stored(): StoredAccount = StoredAccount(service.name, server, user)

/** Another device in the folder, by its file (M8-D17, M8-D18). */
@Serializable
data class SyncDevice(
    val id: String,
    /** Its file's modified time, when the service gives one. */
    val syncedAtMillis: Long? = null,
) {
    /** The model its id was made from: `pixel-7-pro-1a2b3c4d` → "Pixel 7 Pro". */
    val name: String get() = deviceNameOf(id)
}

/** What [SyncRunner.look] found in a folder before the passphrase: new or joining. */
sealed interface FolderLook {
    /** [devices]: the other devices whose files are there; none = a new folder. */
    data class Ready(val devices: List<SyncDevice>) : FolderLook

    data class Failed(val failure: SyncFailure) : FolderLook
}

/** The Match your profiles screen's start (M8-D18): both sides' profiles and the pairs Ayn
 *  proposes ([pairs], local sync id → remote sync id), among them the [fixed] ones no choice
 *  changes (owner with owner, a shared sync id). */
data class MatchPreview(
    val local: List<MatchProfile>,
    val remote: List<MatchProfile>,
    val pairs: Map<String, String>,
    val fixed: Map<String, String>,
)

data class MatchProfile(
    val syncId: String,
    val name: String,
    /** A `ProfileTint` key. */
    val tint: String,
    val owner: Boolean,
    val kids: Boolean,
    val historyCount: Int,
    /** The other devices holding it; empty for this device's own profiles. */
    val devices: List<SyncDevice> = emptyList(),
)

sealed interface MatchLook {
    data class Ready(val preview: MatchPreview) : MatchLook

    data class Failed(val failure: SyncFailure) : MatchLook
}

/**
 * The owner's Match choices (M8-D18), by sync id: [pairs] local → remote (non-owners of one kind),
 * [apart] local profiles that sync as new ones, [skipped] remote profiles not added here (Q19).
 * Kept in the link: [skipped] holds for every later sync.
 */
@Serializable
data class MatchChoices(
    val pairs: Map<String, String> = emptyMap(),
    val apart: Set<String> = emptySet(),
    val skipped: Set<String> = emptySet(),
)

/** Why a typed address or a service's sign-in was refused before anything was linked. */
enum class SetupFailure {
    NOT_AN_ADDRESS,

    /** `http://`: Android's cleartext policy blocks it. */
    NEEDS_HTTPS,
    NO_CONNECTION,

    /** No Nextcloud login answered there. */
    NOT_NEXTCLOUD,

    /** The service refused the sign-in, or failed the account lookup after the user granted
     *  access. */
    SERVER_ERROR,

    /** The user refused Ayn access on Google's page. */
    DENIED,
}

sealed interface CredentialCheck {
    class Ready(val credential: SyncCredential) : CredentialCheck

    data class Refused(val failure: SetupFailure) : CredentialCheck
}

/** Another WebDAV server's credential: the `Ayn` folder inside [address] (M8-D18). */
fun webDavCredential(address: String, user: String, password: String): CredentialCheck = when (val read = readAddress(address)) {
    is AddressRead.Https -> CredentialCheck.Ready(SyncCredential.WebDav(syncFolderIn(read.url).toString(), user, password))
    is AddressRead.Refused -> CredentialCheck.Refused(read.failure)
}

/**
 * Settings › Sync's hold on this device's link ([SyncRunner]; M8-D18): the folder before the
 * passphrase, linking, Match, signing in again and unlinking, and the status the page shows.
 */
interface SyncControl {
    val status: StateFlow<SyncStatus>

    suspend fun look(credential: SyncCredential): FolderLook

    /** Wipes [passphrase]. */
    suspend fun link(credential: SyncCredential, passphrase: CharArray): LinkResult

    suspend fun matchPreview(): MatchLook

    suspend fun startSyncing(choices: MatchChoices)

    /** Saves [credential] in place of the link's, running no round; false, with nothing changed,
     *  when it names another folder than the link's. */
    suspend fun replaceSignIn(credential: SyncCredential): Boolean

    /** False when this device's files couldn't be removed from the folder (it is unlinked anyway). */
    suspend fun unlink(): Boolean
}

/** Nextcloud's sign-in as the setup screens run it ([NextcloudSignIn]). */
interface SyncSignIn {
    suspend fun start(address: String): SignInStart

    suspend fun awaitGrant(started: SignInStart.Started): SignInEnd
}

sealed interface SignInStart {
    /** Open [loginUrl] (the user grants access there) while [NextcloudSignIn.awaitGrant] polls. */
    class Started internal constructor(val loginUrl: String, val server: String, internal val start: LoginStart) : SignInStart

    data class Refused(val failure: SetupFailure) : SignInStart
}

sealed interface SignInEnd {
    class Granted(val credential: SyncCredential) : SignInEnd

    /** The sign-in's time ran out (Nextcloud's 20 minutes, Google's code): start again. */
    data object Expired : SignInEnd

    /** The server named an `http://` address for Ayn to use ([SetupFailure.NEEDS_HTTPS]), the
     *  user refused Ayn ([SetupFailure.DENIED]), or the account lookup after the grant failed. */
    data class Refused(val failure: SetupFailure) : SignInEnd
}

/**
 * Nextcloud's sign-in for the setup screens: [start] at the typed address, then [awaitGrant]
 * polls every [pollEvery] until the token's 20 minutes are up by [timeSource]. A poll under way
 * always finishes: the server hands the grant out once, so one cut off mid-answer loses it. Every
 * address the server names must be https, as the typed one must, and the grant's is checked before
 * its app password is sent there for the account ([NextcloudLogin.account]).
 */
class NextcloudSignIn internal constructor(
    private val login: NextcloudLogin,
    private val pollEvery: Duration = 5.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : SyncSignIn {
    constructor(httpClient: OkHttpClient) : this(NextcloudLogin(httpClient))

    override suspend fun start(address: String): SignInStart {
        val url = when (val read = readAddress(address)) {
            is AddressRead.Https -> read.url
            is AddressRead.Refused -> return SignInStart.Refused(read.failure)
        }
        return try {
            startedAt(login.start(url), url.host)
        } catch (e: SyncStorageException) {
            SignInStart.Refused(if (e.failure == StorageFailure.NO_CONNECTION) SetupFailure.NO_CONNECTION else SetupFailure.NOT_NEXTCLOUD)
        }
    }

    /** Polls until the user grants access or the token's time is up. A failed poll (a dropped
     *  connection) doesn't end the wait. */
    override suspend fun awaitGrant(started: SignInStart.Started): SignInEnd {
        val begun = timeSource.markNow()
        while (true) {
            val grant = withContext(NonCancellable) {
                try {
                    login.poll(started.start)
                } catch (_: SyncStorageException) {
                    null
                }
            }
            if (grant != null) return granted(grant)
            if (begun.elapsedNow() + pollEvery >= LOGIN_TOKEN_LIFE) return SignInEnd.Expired
            delay(pollEvery)
        }
    }

    private suspend fun granted(grant: NextcloudGrant): SignInEnd {
        if (!grant.server.isHttps) return SignInEnd.Refused(SetupFailure.NEEDS_HTTPS)
        return try {
            val account = login.account(grant)
            SignInEnd.Granted(SyncCredential.WebDav(account.folder.toString(), account.user, account.password, nextcloud = true))
        } catch (e: SyncStorageException) {
            SignInEnd.Refused(setupFailureOf(e.failure))
        }
    }
}

/** A failed sign-in step's [failure] as the setup screens tell it. */
internal fun setupFailureOf(failure: StorageFailure): SetupFailure =
    if (failure == StorageFailure.NO_CONNECTION) SetupFailure.NO_CONNECTION else SetupFailure.SERVER_ERROR

/** The service, server and user [this] signs in as. */
fun SyncCredential.account(): SyncAccount = when (this) {
    is SyncCredential.WebDav -> SyncAccount(
        service = if (nextcloud) SyncService.NEXTCLOUD else SyncService.WEBDAV,
        server = folder.toHttpUrlOrNull()?.host.orEmpty(),
        user = user,
    )
    is SyncCredential.GoogleDrive -> SyncAccount(SyncService.GOOGLE_DRIVE, server = "", user = email)
}

/** Whether [this] reaches [other]'s folder; false when [other] is unknown. */
internal fun SyncCredential.sameFolder(other: SyncCredential?): Boolean = when (this) {
    is SyncCredential.WebDav -> {
        val mine = folder.toHttpUrlOrNull()
        mine != null && mine == (other as? SyncCredential.WebDav)?.folder?.toHttpUrlOrNull()
    }
    // Google treats an account's address without regard to case.
    is SyncCredential.GoogleDrive -> email.equals((other as? SyncCredential.GoogleDrive)?.email, ignoreCase = true)
}

internal sealed interface AddressRead {
    class Https(val url: HttpUrl) : AddressRead

    data class Refused(val failure: SetupFailure) : AddressRead
}

/** A typed server address: `https://` added when it names no scheme; `http://` refused. */
internal fun readAddress(address: String): AddressRead {
    val text = address.trim()
    val url = when (text.substringBefore("://", missingDelimiterValue = "").lowercase()) {
        "" -> "https://$text".takeIf { text.isNotEmpty() }?.toHttpUrlOrNull()
        "https" -> text.toHttpUrlOrNull()
        "http" -> return AddressRead.Refused(SetupFailure.NEEDS_HTTPS)
        else -> null
    }
    return url?.let { AddressRead.Https(it) } ?: AddressRead.Refused(SetupFailure.NOT_AN_ADDRESS)
}

/** A login page the TV can show as a QR code: https and at most [MAX_LOGIN_URL] characters (a
 *  longer one wouldn't fit a QR code), else a server Ayn doesn't take. */
internal fun startedAt(start: LoginStart, host: String): SignInStart = when {
    !start.loginUrl.isHttps || !start.pollEndpoint.isHttps -> SignInStart.Refused(SetupFailure.NEEDS_HTTPS)
    start.loginUrl.toString().length > MAX_LOGIN_URL -> SignInStart.Refused(SetupFailure.NOT_NEXTCLOUD)
    else -> SignInStart.Started(start.loginUrl.toString(), host, start)
}

/** Well inside a QR code's 2,331 bytes at medium error correction; Nextcloud's are ~100. */
internal const val MAX_LOGIN_URL = 2_000

/** The sync folder in [parent], as a collection's address (ending in `/`). */
internal fun syncFolderIn(parent: HttpUrl): HttpUrl = parent.newBuilder().addPathSegment(SYNC_FOLDER).addPathSegment("").build()

/** `<model slug>-<8 hex>` back to words, short letter runs (TV) in capitals. */
internal fun deviceNameOf(deviceId: String): String =
    deviceId.substringBeforeLast('-').split('-').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        if (word.length <= 2 && word.all { it.isLetter() }) word.uppercase() else word.replaceFirstChar { it.titlecase() }
    }

/** The one folder every service syncs in (M8-D18). */
internal const val SYNC_FOLDER = "Ayn"

/** Login Flow v2's poll token lasts 20 minutes (spec 20 Decisions). */
private val LOGIN_TOKEN_LIFE = 20.minutes
