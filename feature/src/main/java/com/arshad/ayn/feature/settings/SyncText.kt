package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.sync.MatchPreview
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncDevice
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.kids.ClockTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

// Settings › Sync text both UIs show (`m8-sync-tv`, `m8-sync-phone`). Lines the mockups don't draw
// (the other failures, Nextcloud and WebDAV) follow their pattern.

/**
 * How a shell names devices in the copy: [here] this one ("this Shield", "this phone"), [both]
 * the pair ("TV and phone"), [otherKind] the other kind ("phone", "TV").
 */
class SyncWords(val here: String, val both: String, val otherKind: String) {
    /** [here] starting a sentence. */
    val hereStart: String get() = here.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** The moment and place times are said for. */
class SyncTime(val now: Instant, val zone: ZoneId, val locale: Locale) {
    val today: LocalDate get() = now.toJavaInstant().atZone(zone).toLocalDate()
}

/** The service as rows and kickers name it. */
val SyncService.title: String
    get() = when (this) {
        SyncService.NEXTCLOUD -> NEXTCLOUD
        SyncService.WEBDAV -> "WebDAV"
        SyncService.GOOGLE_DRIVE -> GOOGLE_DRIVE
    }

/** Where the files are: Google Drive and Nextcloud by name, another server by its host. */
private val SyncAccount.where: String
    get() = when (service) {
        SyncService.GOOGLE_DRIVE -> GOOGLE_DRIVE
        SyncService.NEXTCLOUD -> NEXTCLOUD
        SyncService.WEBDAV -> server
    }

/** Who a failure line names as answering or full: Google Drive by name, else the server's host
 *  ("the server" with no account). [where] names the files' place in kickers instead. */
private fun failureSubject(account: SyncAccount?): String =
    if (account?.service == SyncService.GOOGLE_DRIVE) GOOGLE_DRIVE else account?.server ?: "the server"

/** The folder the files are in, as [service] names it (Drive's is "Ayn sync", M8-D1). */
private fun folderName(service: SyncService?): String = if (service == SyncService.GOOGLE_DRIVE) "the Ayn sync folder" else "the Ayn folder"

/** What holds the devices' files: "your Drive", else the Ayn folder. */
private fun filesPlace(service: SyncService?): String = if (service == SyncService.GOOGLE_DRIVE) "your Drive" else "the Ayn folder"

private const val GOOGLE_DRIVE = "Google Drive"

// The page, off

fun syncOffLede(words: SyncWords): String =
    "Keep every profile the same on your ${words.both}: subscriptions, history and where you stopped, playlists, Watch later, " +
        "settings and filters. Only the owner can set it up."

/** Turn on sync's hint: Google Drive first when this build can sign in to it. */
fun turnOnHint(drive: Boolean): String =
    if (drive) "Ayn keeps its sync files in your Google Drive or on your own Nextcloud" else "Ayn keeps its sync files on your own Nextcloud or another WebDAV server"

/** What stays on each device (M8-D4); [deviceSettings] lists the shell's own device settings. */
fun syncKeptNote(words: SyncWords, deviceSettings: String): String =
    "Profile locks, ${words.here}’s own settings ($deviceSettings) and kids’ time used today stay on each device."

/** Settings' Sync row: its state, or who may set it up. */
fun syncCategoryHint(isOwner: Boolean, status: SyncStatus, words: SyncWords, time: SyncTime): String = when {
    !isOwner -> "Only the owner can set up sync"
    status.isOff -> "Off · keep your ${words.both} the same"
    status.syncing -> "On · syncing now"
    status.failure != null -> notSyncedSince(status.lastSyncedMillis, time)
    else -> "On · " + syncedAgo(status.lastSyncedMillis, time).replaceFirstChar { it.lowercaseChar() }
}

// Choosing a service

const val SERVICES_KICKER = "Turn on sync"
const val SERVICES_TITLE = "Where should Ayn keep your sync files?"
const val NEXTCLOUD = "Nextcloud"

/** Google Drive's row after where the sign-in happens. */
const val DRIVE_SERVICE_FOLDER = "Ayn uses an Ayn sync folder in your Drive; nothing else there is touched"

/** Nextcloud's row; the TV adds where the sign-in happens. */
const val NEXTCLOUD_SERVICE_HINT = "Type your Nextcloud’s address, then sign in"
const val WEBDAV_SERVICE = "Another WebDAV server"
const val WEBDAV_SERVICE_HINT = "Type its address, user name and app password"

fun servicesNote(words: SyncWords): String =
    "Ayn locks the files with your sync passphrase before they leave ${words.here}, so the service can’t read them."

/** A sign-in step's kicker: turning sync on, or signing in again. */
fun setupKicker(service: SyncService, again: Boolean): String =
    (if (again) "Sign in again · " else "Turn on sync · ") + service.title

// Nextcloud

const val NEXTCLOUD_ADDRESS_TITLE = "Your Nextcloud’s address"
const val NEXTCLOUD_ADDRESS_LABEL = "Address"
const val NEXTCLOUD_WAITING = "Waiting for you to grant access · the code works for 20 minutes"
const val CHECKING_FOLDER = "Access granted. Checking the Ayn folder…"

/** While Continue asks the address for a sign-in. */
const val NEXTCLOUD_STARTING = "Asking your Nextcloud for a sign-in…"

// Google Drive

/** While Ayn asks Google for a code. */
const val GOOGLE_STARTING = "Asking Google for a code…"

fun googleWaiting(minutes: Long): String = "Waiting for you to allow Ayn · the code works for ${plural(minutes.toInt(), "minute")}"

/** Once allowed: the look in the Drive, or Sign in again's save. */
const val GOOGLE_CHECKING = "Allowed. Checking your Drive…"

/** Why Google's sign-in stopped; Try again starts it over. [account] is the link's, for Sign in again. */
fun googleProblemText(problem: SetupProblem, account: SyncAccount?): String = when (problem) {
    SetupProblem.NO_CONNECTION -> "Ayn couldn’t reach Google. Check your connection and try again"
    SetupProblem.EXPIRED -> "The code ran out of time. Try again for a new one"
    SetupProblem.DENIED -> "Ayn wasn’t allowed on Google’s page. Try again to start over"
    SetupProblem.OTHER_ACCOUNT -> account?.let { "That’s another Google account. Sign in as ${it.user}" } ?: "That’s another Google account"
    SetupProblem.WRONG_SIGN_IN -> "Google refused Ayn’s sign-in. Try again"
    SetupProblem.STORAGE_FULL -> "Your Google Drive is full, so Ayn couldn’t make its folder there"
    SetupProblem.NOT_AN_ADDRESS, SetupProblem.NEEDS_HTTPS, SetupProblem.NOT_NEXTCLOUD, SetupProblem.NO_FOLDER, SetupProblem.SERVER_ERROR ->
        "Google answered with an error. Try again later"
}

// Another WebDAV server

const val WEBDAV_TITLE = "Another WebDAV server"
const val WEBDAV_LEDE = "Use an app password if your server offers one, so you can remove Ayn’s access without changing your own password."
const val WEBDAV_ADDRESS = "Server address"
const val WEBDAV_ADDRESS_HINT = "The folder’s full WebDAV address"
const val WEBDAV_USER = "User name"
const val WEBDAV_PASSWORD = "App password"

/** The folder Ayn keeps its files in (M8-D18: one name for every service). */
const val WEBDAV_FOLDER_LINE = "Ayn makes an Ayn folder there and keeps one file per device in it."

/** While Connect tries the fields. */
const val WEBDAV_CHECKING = "Checking the Ayn folder there…"

/** Whether [problem] is the user name and password's rather than the address's. */
fun SetupProblem.namesSignIn(): Boolean = this == SetupProblem.WRONG_SIGN_IN || this == SetupProblem.OTHER_ACCOUNT

/** Under a sign-in step's field: why it stopped. [account] is the link's, for Sign in again. */
fun setupProblemText(problem: SetupProblem, account: SyncAccount?): String = when (problem) {
    SetupProblem.NOT_AN_ADDRESS -> "That isn’t a web address"
    SetupProblem.NEEDS_HTTPS -> "Ayn needs an https:// address"
    SetupProblem.NO_CONNECTION -> "Ayn couldn’t reach that address. Check it and your connection"
    SetupProblem.NOT_NEXTCLOUD -> "No Nextcloud at that address"
    SetupProblem.EXPIRED -> "The sign-in ran out of time. Continue to start again"
    // Only Google's sign-in ends this way, and its screens use googleProblemText.
    SetupProblem.DENIED -> "Ayn wasn’t allowed access. Try again"
    SetupProblem.OTHER_ACCOUNT ->
        account?.let { "That’s another account or folder. Sign in as ${it.user} on ${it.server}" } ?: "That’s another account or folder"
    SetupProblem.WRONG_SIGN_IN -> "Wrong user name or password"
    SetupProblem.NO_FOLDER -> "No WebDAV folder at that address"
    SetupProblem.STORAGE_FULL -> "The server is full, so Ayn couldn’t make its folder there"
    SetupProblem.SERVER_ERROR -> "The server answered with an error. Try again later"
}

// The passphrase

/** Who the sync files are locked from. */
private fun serviceWho(account: SyncAccount): String = when (account.service) {
    SyncService.GOOGLE_DRIVE -> "Google"
    SyncService.NEXTCLOUD -> NEXTCLOUD
    SyncService.WEBDAV -> "the server"
}

/** The passphrase's kicker: the service and who signed in. */
fun passphraseKicker(account: SyncAccount): String = metaLine(account.where, account.user)

const val NEW_PASSPHRASE_TITLE = "Choose a sync passphrase"
const val JOIN_PASSPHRASE_TITLE = "Enter your sync passphrase"
const val PASSPHRASE_LABEL = "Sync passphrase"
const val PASSPHRASE_HINT = "At least $MIN_PASSPHRASE characters. A few words you’ll remember work well."
const val TURN_ON_BUTTON = "Turn on sync"
const val CONTINUE_BUTTON = "Continue"

/** While the passphrase links: a join also reads the profiles to match. */
fun linkingLine(joining: Boolean): String = if (joining) "Opening the sync files…" else "Turning on sync…"

fun newPassphraseBody(words: SyncWords, account: SyncAccount): String =
    "Ayn locks your sync files with it before they leave ${words.here}, so ${serviceWho(account)} can’t read them. You’ll enter it " +
        "once on each device. Ayn can’t recover it: if it’s lost, turn sync off and on again with a new one."

/** A joining device's passphrase: the devices whose files are there and the latest one's sync;
 *  none when this device links again to a folder holding only its own file. */
fun joinPassphraseBody(devices: List<SyncDevice>, words: SyncWords, time: SyncTime, account: SyncAccount): String {
    if (devices.isEmpty()) return "Enter the passphrase ${words.here} synced with before."
    val latest = devices.mapNotNull { it.syncedAtMillis }.maxOrNull()?.let { ", last synced ${dayAndClock(it, time)}" }.orEmpty()
    val chose = if (devices.size == 1) "there" else "on them"
    val place = filesPlace(account.service).replaceFirstChar { it.titlecase(Locale.ROOT) }
    return "$place already holds sync files from ${spokenList(devices.map { it.name })}$latest. Enter the passphrase you chose $chose."
}

fun wrongPassphraseText(devices: List<SyncDevice>): String {
    val files = devices.singleOrNull()?.let { "${it.name}’s sync files" } ?: "the sync files there"
    return "That passphrase doesn’t open $files. Check for a typo and try again."
}

// The page, on

/** The status line: syncing, the last sync, or since when it fails. */
fun syncStatusLine(status: SyncStatus, time: SyncTime): String = when {
    status.syncing -> "Syncing…"
    status.failure != null -> notSyncedSince(status.lastSyncedMillis, time)
    else -> syncedAgo(status.lastSyncedMillis, time)
}

private fun syncedAgo(lastSyncedMillis: Long?, time: SyncTime): String =
    lastSyncedMillis?.let { "Synced " + ageText(Instant.fromEpochMilliseconds(it), time.now).replaceFirstChar { c -> c.lowercaseChar() } }
        ?: "Not synced yet"

private fun notSyncedSince(lastSyncedMillis: Long?, time: SyncTime): String =
    lastSyncedMillis?.let { "Not synced since ${clockOrDay(it, time)}" } ?: "Not synced yet"

/** "6:40 PM" today ("at 6:40 PM" with [at]), else the day ("October 3"). */
private fun clockOrDay(epochMillis: Long, time: SyncTime, at: Boolean = false): String {
    val moment = Instant.fromEpochMilliseconds(epochMillis).toJavaInstant().atZone(time.zone)
    if (moment.toLocalDate() != time.today) return monthDayText(epochMillis, time.today, time.zone, time.locale)
    return (if (at) "at " else "") + moment.format(ClockTime)
}

/** "today at 8:12 PM", "yesterday at 8:12 PM", else the day ("October 3"). */
private fun dayAndClock(epochMillis: Long, time: SyncTime): String {
    val at = Instant.fromEpochMilliseconds(epochMillis).toJavaInstant().atZone(time.zone)
    return when (at.toLocalDate()) {
        time.today -> "today at ${at.format(ClockTime)}"
        time.today.minusDays(1) -> "yesterday at ${at.format(ClockTime)}"
        else -> monthDayText(epochMillis, time.today, time.zone, time.locale)
    }
}

/** What a failed sync's notice says: what happened, then what to do (M8-D11). */
fun syncFailureNotice(failure: SyncFailure, account: SyncAccount?, words: SyncWords): Pair<String, String> {
    val server = failureSubject(account)
    val unchanged = "Nothing on ${words.here} has changed."
    return when (failure) {
        SyncFailure.SIGN_IN_REJECTED -> when (account?.service) {
            SyncService.GOOGLE_DRIVE -> "Google says Ayn’s access was removed."
            SyncService.NEXTCLOUD -> "Nextcloud says Ayn’s access was removed."
            SyncService.WEBDAV, null -> "$server refused Ayn’s user name or password."
        } to "Sign in again to keep syncing. $unchanged"
        SyncFailure.NO_CONNECTION -> "Ayn couldn’t reach $server." to "It tries again by itself. $unchanged"
        SyncFailure.STORAGE_FULL -> "$server is full." to "Make room there and Ayn sends ${words.here}’s changes at the next sync."
        SyncFailure.FOLDER_MISSING -> "${folderName(account?.service).replaceFirstChar { it.titlecase(Locale.ROOT) }} is gone from $server." to
            "Turn off sync here, then turn it on again. $unchanged"
        SyncFailure.SERVER_ERROR -> "$server answered with an error." to "Ayn tries again at the next sync. $unchanged"
        SyncFailure.WRONG_PASSPHRASE -> "Another device’s sync files don’t open with ${words.here}’s passphrase." to
            "If the passphrase changed there, turn off sync here and turn it on again with the new one. $unchanged"
        SyncFailure.NEWER_VERSION -> "Another device runs a newer Ayn." to "Update Ayn on ${words.here} to keep syncing. $unchanged"
        SyncFailure.LINK_LOST -> "Ayn can no longer read ${words.here}’s sign-in for sync." to "Link again with the same passphrase. $unchanged"
        SyncFailure.TOO_LARGE -> "${words.hereStart}’s profiles are too large to sync." to "Clearing some history lets them fit."
        SyncFailure.NOT_SAVED -> "Ayn couldn’t save on ${words.here}." to "It tries again at the next sync."
    }
}

/** The fix a failure's notice offers as the page's first row, if any. */
enum class SyncFix { SignInAgain, LinkAgain }

fun syncFix(failure: SyncFailure?): SyncFix? = when (failure) {
    SyncFailure.SIGN_IN_REJECTED -> SyncFix.SignInAgain
    SyncFailure.LINK_LOST -> SyncFix.LinkAgain
    null, SyncFailure.STORAGE_FULL, SyncFailure.NO_CONNECTION, SyncFailure.FOLDER_MISSING, SyncFailure.SERVER_ERROR,
    SyncFailure.WRONG_PASSPHRASE, SyncFailure.NEWER_VERSION, SyncFailure.TOO_LARGE, SyncFailure.NOT_SAVED,
    -> null
}

const val SIGN_IN_AGAIN = "Sign in again"
const val LINK_AGAIN = "Link again"
const val LINK_AGAIN_HINT = "Sign in, then enter the same passphrase"

fun signInAgainHint(account: SyncAccount): String =
    if (account.service == SyncService.GOOGLE_DRIVE) "As ${account.user}" else "As ${account.user} on ${account.server}"

/** A failed sync, link or Match read, in one sentence: what happened. */
fun syncFailureLine(failure: SyncFailure, account: SyncAccount?, words: SyncWords): String = syncFailureNotice(failure, account, words).first

const val SYNC_NOW = "Sync now"
const val SYNC_NOW_HINT = "Ayn also syncs when it opens, when you leave it, and every few minutes while it’s open"

/** Sync now's hint while a sync runs. */
fun syncingHint(words: SyncWords, devices: List<SyncDevice>): String {
    val reading = when (devices.size) {
        0 -> ""
        1 -> ", then reading ${devices.single().name}’s"
        else -> ", then reading the other devices’"
    }
    return "Sending ${words.here}’s changes$reading"
}

/** The service row's title. */
fun accountTitle(account: SyncAccount): String = account.service.title

/** The service row's hint: who, where, and the folder's size when known. */
fun accountHint(account: SyncAccount, folderBytes: Long?): String {
    val who = if (account.service == SyncService.GOOGLE_DRIVE) account.user else "${account.user} on ${account.server}"
    val folder = folderName(account.service)
    return metaLine(who, folderBytes?.let { "${sizeText(it)} in $folder" } ?: folder)
}

/** "640 KB", "2.4 MB". */
fun sizeText(bytes: Long): String = when {
    bytes < KB -> "${bytes.coerceAtLeast(0)} B"
    bytes < KB * KB -> "${bytes / KB} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (KB * KB))
}

private const val KB = 1024L

const val DEVICES_HEADING = "Devices"

/** This device's row hint. */
fun thisDeviceHint(status: SyncStatus, time: SyncTime): String = when {
    status.syncing -> "Syncing now"
    status.failure != null -> status.lastSyncedMillis?.let { "Last synced ${clockOrDay(it, time, at = true)}" } ?: "Not synced yet"
    else -> syncedAgo(status.lastSyncedMillis, time)
}

/** Another device's row hint: its last sync; `null` when the service gave no time. */
fun deviceHint(device: SyncDevice, time: SyncTime): String? = device.syncedAtMillis?.let { "Synced ${dayAndClock(it, time)}" }

/** Under this device while no other has joined. */
fun soloNote(words: SyncWords, account: SyncAccount?): String {
    val google = if (account?.service == SyncService.GOOGLE_DRIVE) "Google " else ""
    return "Your ${words.otherKind} joins when you turn on sync there with the same ${google}account and passphrase."
}

fun turnOffTitle(words: SyncWords): String = "Turn off sync on ${words.here}"

const val TURN_OFF_HINT = "Your profiles stay here as they are"

// Turning off

fun turnOffQuestion(words: SyncWords): String = "Turn off sync on ${words.here}?"

fun turnOffSub(words: SyncWords, devices: List<SyncDevice>, service: SyncService?): String {
    val stays = "Every profile stays on ${words.here} as it is now. ${words.hereStart}’s sync file is removed from ${filesPlace(service)}"
    return when (devices.size) {
        0 -> "$stays."
        1 -> "$stays, and ${devices.single().name} keeps syncing on its own."
        else -> "$stays, and the other devices keep syncing on their own."
    }
}

const val KEEP_SYNCING = "Keep syncing"
const val TURN_OFF_BUTTON = "Turn off sync"
const val TURN_OFF_AGAIN_HINT = "You can turn it on again any time with the same passphrase"

// The owner's lock

fun syncGateKicker(action: SyncAction): String = when (action) {
    SyncAction.TurnOn -> "Turning on sync"
    SyncAction.SignInAgain -> "Signing in again"
    SyncAction.TurnOff -> "Turning off sync"
}

const val SYNC_OWNER_ONLY = "Only the owner sets up sync"

// The launch notice (M8-D11)

// Its bold part: Ayn tries again by itself, the owner can fix it, or someone else is told.
const val COULDNT_SYNC = "Couldn’t sync"
const val SYNC_DIDNT_WORK = "Sync didn’t work"
const val SYNC_ISNT_WORKING = "Sync isn’t working"

/** Whether Ayn tries again by itself after [this], so nobody needs to act. */
val SyncFailure.retriesItself: Boolean
    get() = when (this) {
        SyncFailure.NO_CONNECTION, SyncFailure.SERVER_ERROR, SyncFailure.NOT_SAVED -> true
        SyncFailure.SIGN_IN_REJECTED, SyncFailure.STORAGE_FULL, SyncFailure.FOLDER_MISSING, SyncFailure.WRONG_PASSPHRASE,
        SyncFailure.NEWER_VERSION, SyncFailure.LINK_LOST, SyncFailure.TOO_LARGE,
        -> false
    }

/** What failed in a few words, to follow a bold part ("no connection", "Nextcloud removed Ayn’s access"). */
fun launchReason(failure: SyncFailure, account: SyncAccount?): String {
    val server = failureSubject(account)
    return when (failure) {
        SyncFailure.SIGN_IN_REJECTED -> when (account?.service) {
            SyncService.GOOGLE_DRIVE -> "Google removed Ayn’s access"
            SyncService.NEXTCLOUD -> "Nextcloud removed Ayn’s access"
            SyncService.WEBDAV, null -> "$server refused the sign-in"
        }
        SyncFailure.STORAGE_FULL -> "$server is full"
        SyncFailure.NO_CONNECTION -> "no connection"
        SyncFailure.FOLDER_MISSING -> "${folderName(account?.service)} is gone"
        SyncFailure.SERVER_ERROR -> "$server answered with an error"
        SyncFailure.WRONG_PASSPHRASE -> "another device’s passphrase differs"
        SyncFailure.NEWER_VERSION -> "another device runs a newer Ayn"
        SyncFailure.LINK_LOST -> "Ayn can’t read its sign-in"
        SyncFailure.TOO_LARGE -> "the profiles are too large"
        SyncFailure.NOT_SAVED -> "a save failed"
    }
}

/** Whom someone other than the owner tells. */
fun ownerToTell(notice: LaunchNotice): String = notice.ownerName ?: "the owner"

// Toasts

/** A toast's bold part, then the rest; `null` when there is none. */
fun syncToastText(toast: SyncToast, words: SyncWords): Pair<String, String?> = when (toast) {
    is SyncToast.TurnedOn if toast.profiles != null ->
        "Sync is on" to "${plural(toast.profiles, "profile")} match ${spokenList(toast.devices)}"
    is SyncToast.TurnedOn -> "Sync is on" to "turn it on on your ${words.otherKind} too"
    is SyncToast.TurnedOff if toast.filesLeft -> "Sync is off" to "${words.hereStart}’s file couldn’t be removed from ${filesPlace(toast.service)}"
    is SyncToast.TurnedOff -> "Sync is off" to null
}

// Match your profiles

/** The devices holding the folder's profiles, each once. */
fun matchDevices(preview: MatchPreview): List<SyncDevice> = preview.remote.flatMap { it.devices }.distinctBy { it.id }

private fun devicesText(devices: List<SyncDevice>): String = spokenList(devices.map { it.name }).ifEmpty { "the other devices" }

fun matchKicker(account: SyncAccount?, words: SyncWords): String =
    metaLine(account?.where, "first sync on ${words.here}")

const val MATCH_TITLE = "Match your profiles"

fun matchLede(preview: MatchPreview): String {
    val devices = matchDevices(preview)
    val sync = if (devices.size == 1) "syncs" else "sync"
    return "${devicesText(devices)} $sync ${plural(preview.remote.size, "profile")}. A profile matched with one there becomes one " +
        "profile on both: their subscriptions, history and playlists are put together, nothing is replaced."
}

/** A local row's value: the profile it is With, or kept apart. */
fun hereValue(match: ProfileMatch, profile: MatchProfile): String =
    match.with[profile.syncId]?.let(match::remote)?.let(::withOption) ?: KEEP_APART_HINT

/** A local row's hint: why it matches, or what keeping it apart does. */
fun hereHint(match: ProfileMatch, profile: MatchProfile): String? {
    val theirs = match.with[profile.syncId]?.let(match::remote)
        ?: return "Not on ${devicesText(matchDevices(match.preview))}. It appears there at its next sync"
    val why = when {
        profile.owner -> "The owner always matches the owner"
        profile.syncId == theirs.syncId -> "Already the same profile"
        profile.name.equals(theirs.name, ignoreCase = true) -> "Same name"
        else -> null
    }
    return metaLine(why, "restrictions sync too".takeIf { profile.kids }).ifEmpty { null }
}

/** A With choice: [remote] and the devices holding it. */
fun withOption(remote: MatchProfile): String = "With ${remote.name} on ${devicesText(remote.devices)}"

fun onlyThereHeading(preview: MatchPreview): String = "Only on ${devicesText(matchDevices(preview))}"

fun thereValue(match: ProfileMatch, profile: MatchProfile, words: SyncWords): String =
    if (profile.syncId in match.skipped) "Not added here" else addHere(words)

fun addHere(words: SyncWords): String = "Add to ${words.here}"

/** A remote-only row's hint: its history, and the room left here. */
fun thereHint(match: ProfileMatch, profile: MatchProfile, words: SyncWords): String {
    val added = profile.syncId !in match.skipped
    val room = when {
        added && match.countHere == MAX_PROFILES -> "${words.hereStart} will have $MAX_PROFILES profiles, the most"
        !added && match.countHere >= MAX_PROFILES -> "No room: $MAX_PROFILES profiles is the most"
        else -> null
    }
    return metaLine("${profile.historyCount} in history", room)
}

const val KEEP_APART = "Keep apart"
const val KEEP_APART_HINT = "Syncs as a new profile"
const val DONT_ADD = "Don’t add"

fun dontAddHint(profile: MatchProfile): String = "It stays on ${devicesText(profile.devices)} and keeps syncing there"

fun matchLocksLine(preview: MatchPreview): String =
    "Locks stay on each device: a matched profile keeps its lock here, and its own on ${devicesText(matchDevices(preview))}."

const val START_SYNCING = "Start syncing"

/** Beside Start syncing: how many match, which sync as new and which are added here. */
fun matchNote(match: ProfileMatch): String {
    val apart = match.preview.local.filter { it.syncId !in match.with }.map { it.name }
    val added = match.added.map { it.name }
    return listOfNotNull(
        "Matches ${match.with.size}",
        apart.takeIf { it.isNotEmpty() }?.let { "adds ${spokenList(it)} to sync" },
        added.takeIf { it.isNotEmpty() }?.let { "adds ${spokenList(it)} here" },
    ).joinToString(", ")
}

const val READING_MATCH = "Reading the other devices’ profiles…"
const val MATCH_UNREAD_TITLE = "Ayn couldn’t read the profiles to match"
const val TRY_AGAIN = "Try again"
