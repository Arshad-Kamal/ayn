package com.arshad.ayn.ui.tv.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.feature.settings.CHECKING_FOLDER
import com.arshad.ayn.feature.settings.CONTINUE_BUTTON
import com.arshad.ayn.feature.settings.DriveCode
import com.arshad.ayn.feature.settings.GOOGLE_CHECKING
import com.arshad.ayn.feature.settings.GOOGLE_STARTING
import com.arshad.ayn.feature.settings.JOIN_PASSPHRASE_TITLE
import com.arshad.ayn.feature.settings.MATCH_TITLE
import com.arshad.ayn.feature.settings.MATCH_UNREAD_TITLE
import com.arshad.ayn.feature.settings.MIN_PASSPHRASE
import com.arshad.ayn.feature.settings.MatchSheet
import com.arshad.ayn.feature.settings.NEW_PASSPHRASE_TITLE
import com.arshad.ayn.feature.settings.NEXTCLOUD_ADDRESS_TITLE
import com.arshad.ayn.feature.settings.NEXTCLOUD_STARTING
import com.arshad.ayn.feature.settings.NEXTCLOUD_WAITING
import com.arshad.ayn.feature.settings.PASSPHRASE_HINT
import com.arshad.ayn.feature.settings.READING_MATCH
import com.arshad.ayn.feature.settings.START_SYNCING
import com.arshad.ayn.feature.settings.SyncScreen
import com.arshad.ayn.feature.settings.SyncTime
import com.arshad.ayn.feature.settings.SyncUiState
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.feature.settings.TRY_AGAIN
import com.arshad.ayn.feature.settings.TURN_ON_BUTTON
import com.arshad.ayn.feature.settings.WEBDAV_ADDRESS
import com.arshad.ayn.feature.settings.WEBDAV_ADDRESS_HINT
import com.arshad.ayn.feature.settings.WEBDAV_CHECKING
import com.arshad.ayn.feature.settings.WEBDAV_FOLDER_LINE
import com.arshad.ayn.feature.settings.WEBDAV_LEDE
import com.arshad.ayn.feature.settings.WEBDAV_PASSWORD
import com.arshad.ayn.feature.settings.WEBDAV_TITLE
import com.arshad.ayn.feature.settings.WEBDAV_USER
import com.arshad.ayn.feature.settings.googleProblemText
import com.arshad.ayn.feature.settings.googleWaiting
import com.arshad.ayn.feature.settings.hereHint
import com.arshad.ayn.feature.settings.hereValue
import com.arshad.ayn.feature.settings.joinPassphraseBody
import com.arshad.ayn.feature.settings.linkingLine
import com.arshad.ayn.feature.settings.matchKicker
import com.arshad.ayn.feature.settings.matchLede
import com.arshad.ayn.feature.settings.matchLocksLine
import com.arshad.ayn.feature.settings.matchNote
import com.arshad.ayn.feature.settings.namesSignIn
import com.arshad.ayn.feature.settings.newPassphraseBody
import com.arshad.ayn.feature.settings.onlyThereHeading
import com.arshad.ayn.feature.settings.passphraseKicker
import com.arshad.ayn.feature.settings.setupKicker
import com.arshad.ayn.feature.settings.setupProblemText
import com.arshad.ayn.feature.settings.syncFailureLine
import com.arshad.ayn.feature.settings.syncGateKicker
import com.arshad.ayn.feature.settings.thereHint
import com.arshad.ayn.feature.settings.thereValue
import com.arshad.ayn.feature.settings.wrongPassphraseText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.player.Spinner
import com.arshad.ayn.ui.tv.profiles.EntryCopy
import com.arshad.ayn.ui.tv.profiles.EntryKicker
import com.arshad.ayn.ui.tv.profiles.GuidedBody
import com.arshad.ayn.ui.tv.profiles.GuidedTitle
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.LockEntryScreen
import com.arshad.ayn.ui.tv.profiles.GuidedField
import com.arshad.ayn.ui.tv.profiles.ProfileGuided
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.UnlockActions
import com.arshad.ayn.ui.tv.profiles.controlRoomLight
import com.arshad.ayn.ui.tv.profiles.rememberInitialFocus
import com.arshad.ayn.ui.tv.theme.KeCodeStyle
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion

// `.guided.pass .field` (px ÷ 2): the address and passphrase fields are 380 dp wide.
private val FieldWidth = 380.dp


/**
 * A screen covering the shell over Settings › Sync (`m8-sync-tv` google, ncaddr, nextcloud, webdav,
 * passnew, passjoin, passwrong, pairing): the owner's lock, Google's and Nextcloud's sign-ins on
 * the phone (with Nextcloud's address), another WebDAV server's fields, the passphrase, and Match
 * your profiles (with reading them first). [owner] is the open profile, whose lock the owner gate asks for.
 */
@Composable
internal fun SyncCover(shown: SyncScreen, state: SyncUiState, owner: Profile?, sync: SyncViewModel, time: SyncTime) {
    val account = state.status.account
    when (shown) {
        is SyncScreen.OwnerGate -> owner?.let { SyncOwnerGate(shown, it, sync) }
        is SyncScreen.NextcloudAddress -> NextcloudAddressCover(shown, account, sync)
        is SyncScreen.NextcloudWaiting -> NextcloudWaitingCover(shown, sync)
        is SyncScreen.GoogleSignIn -> GoogleSignInCover(shown, account, sync)
        is SyncScreen.WebDav -> WebDavCover(shown, account, sync)
        is SyncScreen.Passphrase -> PassphraseCover(shown, sync, time)
        is SyncScreen.ReadingMatch -> ReadingMatchCover(shown, account, sync)
        is SyncScreen.Match -> MatchCover(shown, sync)
        // The page itself, and the sheets over it (SettingsScreen's layers).
        SyncScreen.Page, SyncScreen.Services, is SyncScreen.TurnOff -> Unit
    }
}

/** The owner's lock before Turn on, Sign in again or Turn off ("Turning on sync"). */
@Composable
private fun SyncOwnerGate(gate: SyncScreen.OwnerGate, owner: Profile, sync: SyncViewModel) {
    val actions = remember(sync) {
        UnlockActions(
            onPress = sync::onPress,
            onSubmitSequence = sync::onSubmitSequence,
            onDigit = sync::onDigit,
            onErase = sync::onErase,
            onBack = sync::onBack,
            onRejectionShown = sync::onRejectionShown,
        )
    }
    val copy = EntryCopy(
        kicker = EntryKicker(R.drawable.ic_cloud, syncGateKicker(gate.action)),
        line = syncGateLine(owner.name, gate.lockKind),
        hint = cancelHint(gate.lockKind),
        waitTail = SettingsWaitTail,
    )
    LockEntryScreen(owner, gate.lockKind, gate.entry, copy, actions)
}

/**
 * A guided step (`.guided.pass`): [kicker] after [icon], [title], [body], then [below] (a field and
 * its message); [actions] at the right.
 */
@Composable
private fun SyncGuided(
    @DrawableRes icon: Int,
    kicker: String,
    title: String,
    body: String?,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    below: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            copy = {
                KickerRow(kicker, Modifier.padding(bottom = 11.dp)) { KickerIcon(icon) }
                GuidedTitle(title, maxWidth = 560.dp)
                body?.let { GuidedBody(it, Modifier.widthIn(max = 480.dp)) }
                below()
            },
            actions = { actions() },
        )
    }
}

/** What a step's field says under it (`.fmsg`), fading as it changes (D8). */
private sealed interface StepMessage {
    val text: String

    data class Info(override val text: String) : StepMessage

    data class Problem(override val text: String) : StepMessage

    data class Waiting(override val text: String) : StepMessage
}

@Composable
private fun StepMessageLine(message: StepMessage?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = message,
        modifier = modifier,
        transitionSpec = { fadeIn(KeMotion.base()) togetherWith fadeOut(KeMotion.fast()) },
        label = "stepMessage",
    ) { shown ->
        if (shown == null) return@AnimatedContent
        Row(Modifier.padding(top = 11.dp).widthIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (shown) {
                is StepMessage.Waiting -> Spinner(15.dp, 2.dp, Modifier.padding(top = 2.dp))
                is StepMessage.Problem -> MessageIcon(R.drawable.ic_alert, KeColors.Warn)
                is StepMessage.Info -> MessageIcon(R.drawable.ic_info, KeColors.Ink3)
            }
            Text(
                shown.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 1.4.em),
                color = if (shown is StepMessage.Problem) KeColors.Ink else KeColors.Ink2,
            )
        }
    }
}

@Composable
private fun MessageIcon(@DrawableRes icon: Int, tint: Color) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(15.dp))
}

/** Nextcloud's address (`ncaddr`): the keyboard types it; Continue (or the keyboard's Done) asks it for a sign-in. */
@Composable
private fun NextcloudAddressCover(shown: SyncScreen.NextcloudAddress, account: SyncAccount?, sync: SyncViewModel) {
    var address by remember { mutableStateOf(shown.address) }
    val field = remember { FocusRequester() }
    val proceed = remember { FocusRequester() }
    // Sign in again comes with the address filled in: Continue is the next step.
    val startsOnField = remember { shown.address.isEmpty() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        (if (startsOnField) field else proceed).requestFocus()
    }
    val next = {
        proceed.requestFocus()
        sync.onNextcloudContinue(address)
    }
    val message = if (shown.checking) {
        StepMessage.Waiting(NEXTCLOUD_STARTING)
    } else {
        shown.problem?.let { StepMessage.Problem(setupProblemText(it, account)) }
    }
    SyncGuided(
        icon = R.drawable.ic_cloud,
        kicker = setupKicker(SyncService.NEXTCLOUD, shown.again),
        title = NEXTCLOUD_ADDRESS_TITLE,
        body = NEXTCLOUD_ADDRESS_LEDE,
        actions = {
            GuidedButton(R.drawable.ic_check, CONTINUE_BUTTON, ControlStyle.Primary, next, Modifier.focusRequester(proceed))
            GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Secondary, sync::onBack)
        },
    ) {
        GuidedField(
            value = address,
            onValueChange = { address = it },
            onDone = next,
            onDown = { proceed.requestFocus() },
            modifier = Modifier.padding(top = 17.dp).focusRequester(field),
            width = FieldWidth,
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Uri,
            warn = shown.problem != null,
        )
        StepMessageLine(message)
    }
}

/**
 * Nextcloud's sign-in on the phone (`nextcloud`, Login Flow v2): the steps, the login page's QR
 * code (the server named under it), waiting for the grant, then checking the Ayn folder. Change
 * address goes back to the field; Cancel to the page.
 */
@Composable
private fun NextcloudWaitingCover(shown: SyncScreen.NextcloudWaiting, sync: SyncViewModel) {
    val first = rememberInitialFocus()
    Box(Modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            actionsAlignment = Alignment.CenterHorizontally,
            copy = {
                KickerRow(setupKicker(SyncService.NEXTCLOUD, shown.again), Modifier.padding(bottom = 11.dp)) { KickerIcon(R.drawable.ic_cloud) }
                GuidedTitle(SIGN_IN_ON_PHONE, maxWidth = 560.dp)
                Steps(nextcloudSteps(shown.server), Modifier.padding(top = 15.dp))
                WaitLine(if (shown.checking) CHECKING_FOLDER else NEXTCLOUD_WAITING, Modifier.padding(top = 20.dp))
            },
            actions = {
                AddressQr(shown.loginUrl, shown.server, Modifier.padding(bottom = 13.dp))
                GuidedButton(R.drawable.ic_edit, "Change address", ControlStyle.Primary, sync::onChangeAddress, Modifier.focusRequester(first))
                GuidedButton(R.drawable.ic_back, "Cancel", ControlStyle.Secondary, sync::onCancel)
            },
        )
    }
}

/**
 * Google's sign-in on the phone (`google`, device flow): the steps with the code, the QR code of
 * Google's page (named under it), waiting until the user allows Ayn, then checking the Drive.
 * First Ayn asks Google for the code; a problem says why, with Try again (focused) and Cancel.
 */
@Composable
private fun GoogleSignInCover(shown: SyncScreen.GoogleSignIn, account: SyncAccount?, sync: SyncViewModel) {
    val first = rememberInitialFocus()
    val problem = shown.problem
    val code = shown.code?.takeIf { problem == null }
    Box(Modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            actionsAlignment = Alignment.CenterHorizontally,
            copy = {
                KickerRow(setupKicker(SyncService.GOOGLE_DRIVE, shown.again), Modifier.padding(bottom = 11.dp)) { KickerIcon(R.drawable.ic_gdrive) }
                GuidedTitle(SIGN_IN_ON_PHONE, maxWidth = 560.dp)
                FadingWith(code) { shownCode ->
                    StepColumn(Modifier.padding(top = 15.dp)) {
                        Step(1) { StepText(googleOpenStep(shownCode.page)) }
                        Step(2, Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StepText(AnnotatedString(GOOGLE_ENTER))
                                UserCode(shownCode.userCode)
                            }
                        }
                        Step(3) { StepText(AnnotatedString(GOOGLE_ALLOW_STEP)) }
                    }
                }
                val message = when {
                    problem != null -> StepMessage.Problem(googleProblemText(problem, account))
                    code == null -> StepMessage.Waiting(GOOGLE_STARTING)
                    shown.checking -> StepMessage.Waiting(GOOGLE_CHECKING)
                    else -> StepMessage.Waiting(googleWaiting(code.minutes))
                }
                StepMessageLine(message, Modifier.padding(top = 9.dp))
            },
            actions = {
                FadingWith(code) { AddressQr(it.url, it.page, Modifier.padding(bottom = 13.dp)) }
                if (problem != null) {
                    GuidedButton(R.drawable.ic_retry, TRY_AGAIN, ControlStyle.Primary, sync::onGoogleTryAgain, Modifier.focusRequester(first))
                }
                GuidedButton(R.drawable.ic_back, "Cancel", ControlStyle.Secondary, sync::onCancel, if (problem == null) Modifier.focusRequester(first) else Modifier)
            },
        )
    }
}

/** [content] for [code] while there is one, fading in and out as it comes and goes (D8: alpha
 *  only, the layout taking the new size at once). */
@Composable
private fun FadingWith(code: DriveCode?, content: @Composable (DriveCode) -> Unit) {
    AnimatedContent(
        targetState = code,
        transitionSpec = { fadeIn(KeMotion.base()) togetherWith fadeOut(KeMotion.fast()) using null },
        label = "googleCode",
    ) { shownCode -> shownCode?.let { content(it) } }
}

// `.ucode` (px ÷ 2): the code in a well with a faint rim, 7 dp corners.
private val CodeShape = RoundedCornerShape(7.dp)
private val CodeRim = Color.White.copy(alpha = 0.22f)

/** The code to type on the phone (`.ucode`). */
@Composable
private fun UserCode(code: String) {
    Text(
        code,
        style = KeCodeStyle,
        color = KeColors.Ink,
        modifier = Modifier
            .background(KeColors.Well, CodeShape)
            .border(1.dp, CodeRim, CodeShape)
            .padding(start = 9.dp, end = 9.dp, top = 2.dp, bottom = 3.dp),
    )
}

// `ol.steps` (px ÷ 2): a 24 dp ring holds each number, 13 dp before its text, 11 dp between steps.
private val StepRing = 24.dp

@Composable
private fun Steps(steps: List<AnnotatedString>, modifier: Modifier = Modifier) {
    StepColumn(modifier) {
        steps.forEachIndexed { index, step -> Step(index + 1) { StepText(step) } }
    }
}

@Composable
private fun StepColumn(modifier: Modifier = Modifier, steps: @Composable () -> Unit) {
    Column(modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) { steps() }
}

/** Step [number]'s ring, then [content]; a tall step ([alignment] centred) keeps its ring level with it. */
@Composable
private fun Step(number: Int, alignment: Alignment.Vertical = Alignment.Top, content: @Composable () -> Unit) {
    Row(verticalAlignment = alignment, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.size(StepRing).border(1.dp, KeColors.Ink3, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp))
        }
        Box(Modifier.padding(top = if (alignment == Alignment.Top) 2.dp else 0.dp)) { content() }
    }
}

@Composable
private fun StepText(text: AnnotatedString) {
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 1.4.em), color = KeColors.Ink2)
}

/** A step under way (`.wait`): a turning ring and what Ayn waits for. */
@Composable
private fun WaitLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.widthIn(max = 480.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Spinner(15.dp, 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 1.4.em), color = KeColors.Ink2)
    }
}

/** Another WebDAV server's fields, each edited in place. */
private enum class WebDavField(val label: String, val hint: String?, val keyboardType: KeyboardType = KeyboardType.Unspecified) {
    Address(WEBDAV_ADDRESS, WEBDAV_ADDRESS_HINT, KeyboardType.Uri),
    User(WEBDAV_USER, null),
    Password(WEBDAV_PASSWORD, null),
}

/**
 * Another WebDAV server (`webdav`): its address, user name and app password, each a row that OK
 * edits in place with the keyboard (Done or BACK ends the edit, focus staying on the row); Connect
 * tries them. A refusal shows under the field it names. The password stays in this screen, never
 * in the ViewModel's state.
 */
@Composable
private fun WebDavCover(shown: SyncScreen.WebDav, account: SyncAccount?, sync: SyncViewModel) {
    var address by remember { mutableStateOf(shown.address) }
    var user by remember { mutableStateOf(shown.user) }
    var password by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<WebDavField?>(null) }
    var edited by remember { mutableStateOf<WebDavField?>(null) }
    val first = rememberInitialFocus()
    val rows = remember(first) { mapOf(WebDavField.Address to first, WebDavField.User to FocusRequester(), WebDavField.Password to FocusRequester()) }
    BackHandler(enabled = editing != null) { editing = null }
    LaunchedEffect(editing) {
        val back = edited ?: return@LaunchedEffect
        if (editing != null) return@LaunchedEffect
        withFrameNanos { }
        rows.getValue(back).requestFocus()
    }
    fun edit(field: WebDavField) {
        edited = field
        editing = field
    }
    val row: @Composable (WebDavField, String, (String) -> Unit) -> Unit = { field, value, onValueChange ->
        WebDavRow(field, value, onValueChange, editing == field, { edit(field) }, { editing = null }, rows.getValue(field))
    }
    val ready = address.isNotBlank() && user.isNotBlank() && password.isNotEmpty()
    val problem = shown.problem?.let { StepMessage.Problem(setupProblemText(it, account)) }
    val signInProblem = shown.problem?.namesSignIn() == true
    SetupPage(
        icon = R.drawable.ic_server,
        kicker = setupKicker(SyncService.WEBDAV, shown.again),
        title = WEBDAV_TITLE,
        lede = WEBDAV_LEDE,
        foot = {
            FootButton(
                R.drawable.ic_check,
                "Connect",
                { sync.onWebDavConnect(address, user, password) },
                Modifier.alpha(if (ready) 1f else UnavailableAlpha),
                ControlStyle.Primary,
            )
            FootButton(R.drawable.ic_back, "Back", sync::onBack)
        },
    ) {
        Column(Modifier.padding(top = 17.dp).width(650.dp).widenStart(RowInset), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            row(WebDavField.Address, address) { address = it }
            if (!signInProblem) StepMessageLine(problem, Modifier.padding(start = RowInset))
            row(WebDavField.User, user) { user = it }
            row(WebDavField.Password, password) { password = it }
            if (signInProblem) StepMessageLine(problem, Modifier.padding(start = RowInset))
        }
        if (shown.checking) WaitLine(WEBDAV_CHECKING, Modifier.padding(top = 13.dp))
        NoteLine(R.drawable.ic_folder, WEBDAV_FOLDER_LINE)
    }
}

@Composable
private fun WebDavRow(
    field: WebDavField,
    value: String,
    onValueChange: (String) -> Unit,
    editing: Boolean,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    requester: FocusRequester,
) {
    val secret = field == WebDavField.Password
    if (editing) {
        SettingEditRow(
            label = field.label,
            value = value,
            onValueChange = onValueChange,
            onDone = onDone,
            capitalization = KeyboardCapitalization.None,
            secret = secret,
            keyboardType = field.keyboardType,
        )
    } else {
        SettingRow(
            label = field.label,
            hint = field.hint,
            value = if (secret) "•".repeat(value.length) else value,
            chevron = false,
            valueMaxWidth = 380.dp,
            onClick = onEdit,
            modifier = Modifier.focusRequester(requester),
        )
    }
}

/** A line under a step's rows (`.devline`): [icon], then [text]. */
@Composable
private fun NoteLine(@DrawableRes icon: Int, text: String) {
    Row(Modifier.padding(top = 13.dp).widthIn(max = 650.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 1.4.em), color = KeColors.Ink3)
    }
}

/**
 * The sync passphrase (`passnew`, `passjoin`, `passwrong`): a new one for a folder no other device
 * uses yet (at least [MIN_PASSPHRASE] characters), else the one the folder's files were locked
 * with. A wrong one rims the field amber with the reason under it. Turn on sync or Continue (or the
 * keyboard's Done) links; the typed passphrase is handed over as characters the link wipes.
 */
@Composable
private fun PassphraseCover(shown: SyncScreen.Passphrase, sync: SyncViewModel, time: SyncTime) {
    var passphrase by remember { mutableStateOf("") }
    val field = rememberInitialFocus()
    val proceed = remember { FocusRequester() }
    val ready = passphrase.isNotEmpty() && (!shown.mayStartFolder || passphrase.length >= MIN_PASSPHRASE)
    val link = {
        if (ready) {
            proceed.requestFocus()
            sync.onPassphrase(passphrase.toCharArray())
        }
    }
    val account = shown.account
    val failure = shown.failure
    val message = when {
        shown.linking -> StepMessage.Waiting(linkingLine(shown.joining))
        shown.wrong -> StepMessage.Problem(wrongPassphraseText(shown.devices))
        failure != null -> StepMessage.Problem(syncFailureLine(failure, account, TvSyncWords))
        shown.mayStartFolder -> StepMessage.Info(PASSPHRASE_HINT)
        else -> null
    }
    SyncGuided(
        icon = account.service.icon,
        kicker = passphraseKicker(account),
        title = if (shown.joining) JOIN_PASSPHRASE_TITLE else NEW_PASSPHRASE_TITLE,
        body = if (shown.joining) joinPassphraseBody(shown.devices, TvSyncWords, time, account) else newPassphraseBody(TvSyncWords, account),
        actions = {
            GuidedButton(
                R.drawable.ic_check,
                if (shown.joining) CONTINUE_BUTTON else TURN_ON_BUTTON,
                ControlStyle.Primary,
                link,
                Modifier.focusRequester(proceed).alpha(if (ready) 1f else UnavailableAlpha),
            )
            GuidedButton(R.drawable.ic_back, "Cancel", ControlStyle.Secondary, sync::onCancel)
        },
    ) {
        GuidedField(
            value = passphrase,
            onValueChange = { passphrase = it },
            onDone = link,
            onDown = { proceed.requestFocus() },
            modifier = Modifier.padding(top = 17.dp).focusRequester(field),
            width = FieldWidth,
            capitalization = KeyboardCapitalization.None,
            secret = true,
            warn = shown.wrong,
        )
        StepMessageLine(message)
    }
}

/**
 * Reading the profiles to match after a join: a turning ring while Ayn reads (focus waits on the
 * cover so the page under it can't take the remote's keys); if they couldn't be read, why, then
 * Try again or Cancel (which unlinks).
 */
@Composable
private fun ReadingMatchCover(shown: SyncScreen.ReadingMatch, account: SyncAccount?, sync: SyncViewModel) {
    val failure = shown.failure
    val kicker = matchKicker(account, TvSyncWords)
    if (failure == null) {
        val hold = rememberInitialFocus()
        SyncGuided(
            icon = account?.service?.icon ?: R.drawable.ic_cloud,
            kicker = kicker,
            title = MATCH_TITLE,
            body = null,
            actions = {},
            modifier = Modifier.focusRequester(hold).focusable(),
        ) {
            WaitLine(READING_MATCH, Modifier.padding(top = 17.dp))
        }
        return
    }
    val first = rememberInitialFocus()
    SyncGuided(
        icon = R.drawable.ic_alert,
        kicker = kicker,
        title = MATCH_UNREAD_TITLE,
        body = syncFailureLine(failure, account, TvSyncWords),
        actions = {
            GuidedButton(R.drawable.ic_retry, TRY_AGAIN, ControlStyle.Primary, sync::onReadMatchAgain, Modifier.focusRequester(first))
            GuidedButton(R.drawable.ic_back, "Cancel", ControlStyle.Secondary, sync::onCancel)
        },
    )
}

/**
 * Match your profiles (`pairing`, M8-D18): this Shield's profiles, each With one on the other
 * devices or kept apart (the owner's and a shared one fixed, out of focus's reach), then those only
 * there, each added here or not. OK on a row opens its choices in a sheet over the page; closing
 * it returns focus to the row. Start syncing keeps the choices; Cancel (or BACK) unlinks.
 */
@Composable
private fun MatchCover(shown: SyncScreen.Match, sync: SyncViewModel) {
    val match = shown.match
    val preview = match.preview
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun rowRequester(syncId: String): FocusRequester = rowFocus.getOrPut(syncId) { FocusRequester() }
    val start = remember { FocusRequester() }
    val firstRow = preview.local.firstOrNull { it.syncId !in match.fixed }?.syncId ?: match.remoteOnly.firstOrNull()?.syncId
    LaunchedEffect(Unit) {
        withFrameNanos { }
        (firstRow?.let(::rowRequester) ?: start).requestFocus()
    }
    var opener by remember { mutableStateOf<FocusRequester?>(null) }
    fun open(syncId: String) {
        opener = rowRequester(syncId)
        sync.onMatchRow(syncId)
    }
    FocusOnClose(open = shown.sheet != null) { opener?.requestFocus() }
    Overlays<MatchSheet, Unit>(
        sheet = shown.sheet,
        cover = null,
        onRailShade = {},
        sheetContent = { sheet ->
            when (sheet) {
                is MatchSheet.Here -> MatchHereSheet(shown, sheet.syncId, sync)
                is MatchSheet.There -> MatchThereSheet(shown, sheet.syncId, sync)
            }
        },
        coverContent = {},
    ) {
        SetupPage(
            icon = shown.account?.service?.icon ?: R.drawable.ic_cloud,
            kicker = matchKicker(shown.account, TvSyncWords),
            title = MATCH_TITLE,
            lede = matchLede(preview),
            note = matchNote(match),
            foot = {
                FootButton(R.drawable.ic_cloud, START_SYNCING, sync::onStartSyncing, Modifier.focusRequester(start), ControlStyle.Primary)
                FootButton(R.drawable.ic_back, "Cancel", sync::onCancel)
            },
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .padding(top = 13.dp)
                    .width(650.dp)
                    .widenStart(RowInset)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                preview.local.forEach { profile ->
                    key(profile.syncId) {
                        val fixed = profile.syncId in match.fixed
                        MatchRow(
                            profile = profile,
                            value = hereValue(match, profile),
                            hint = hereHint(match, profile),
                            fixed = fixed,
                            onClick = { open(profile.syncId) },
                            modifier = Modifier.focusRequester(rowRequester(profile.syncId)),
                        )
                    }
                }
                if (match.remoteOnly.isNotEmpty()) PaneSubHeading(onlyThereHeading(preview))
                match.remoteOnly.forEach { profile ->
                    key(profile.syncId) {
                        MatchRow(
                            profile = profile,
                            value = thereValue(match, profile, TvSyncWords),
                            hint = thereHint(match, profile, TvSyncWords),
                            fixed = false,
                            onClick = { open(profile.syncId) },
                            modifier = Modifier.focusRequester(rowRequester(profile.syncId)),
                        )
                    }
                }
            }
            NoteLine(R.drawable.ic_lock, matchLocksLine(preview))
            Spacer(Modifier.height(MatchFootRoom))
        }
    }
}

// The foot's buttons sit over the page's bottom 68 dp; the rows scroll above them.
private val MatchFootRoom = 68.dp

/** A Match row: the profile's plate, name and tags, why it matches, and its choice; a fixed pair
 *  (`.srow` without `.f`) is text focus passes over, with no chevron. */
@Composable
private fun MatchRow(profile: MatchProfile, value: String, hint: String?, fixed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingRow(
        label = profile.name,
        hint = hint,
        value = value,
        chevron = !fixed,
        focusable = !fixed,
        valueMaxWidth = 300.dp,
        onClick = onClick,
        modifier = modifier,
        lead = { ProfilePlate(profile.name, ProfileTint.of(profile.tint), RowPlate) },
        labelTrail = { tones -> ProfileTags(profile.owner, profile.kids, tones) },
    )
}
