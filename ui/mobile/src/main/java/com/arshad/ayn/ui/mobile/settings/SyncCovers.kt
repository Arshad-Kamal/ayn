package com.arshad.ayn.ui.mobile.settings

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.feature.settings.CHECKING_FOLDER
import com.arshad.ayn.feature.settings.CONTINUE_BUTTON
import com.arshad.ayn.feature.settings.DONT_ADD
import com.arshad.ayn.feature.settings.DriveCode
import com.arshad.ayn.feature.settings.GOOGLE_CHECKING
import com.arshad.ayn.feature.settings.GOOGLE_STARTING
import com.arshad.ayn.feature.settings.JOIN_PASSPHRASE_TITLE
import com.arshad.ayn.feature.settings.KEEP_APART
import com.arshad.ayn.feature.settings.KEEP_APART_HINT
import com.arshad.ayn.feature.settings.MATCH_TITLE
import com.arshad.ayn.feature.settings.MATCH_UNREAD_TITLE
import com.arshad.ayn.feature.settings.MIN_PASSPHRASE
import com.arshad.ayn.feature.settings.MatchSheet
import com.arshad.ayn.feature.settings.NEW_PASSPHRASE_TITLE
import com.arshad.ayn.feature.settings.NEXTCLOUD_ADDRESS_LABEL
import com.arshad.ayn.feature.settings.NEXTCLOUD_ADDRESS_TITLE
import com.arshad.ayn.feature.settings.NEXTCLOUD_STARTING
import com.arshad.ayn.feature.settings.PASSPHRASE_HINT
import com.arshad.ayn.feature.settings.PASSPHRASE_LABEL
import com.arshad.ayn.feature.settings.READING_MATCH
import com.arshad.ayn.feature.settings.START_SYNCING
import com.arshad.ayn.feature.settings.SyncScreen
import com.arshad.ayn.feature.settings.SyncTime
import com.arshad.ayn.feature.settings.SyncUiState
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.feature.settings.TRY_AGAIN
import com.arshad.ayn.feature.settings.TURN_ON_BUTTON
import com.arshad.ayn.feature.settings.WEBDAV_ADDRESS
import com.arshad.ayn.feature.settings.WEBDAV_CHECKING
import com.arshad.ayn.feature.settings.WEBDAV_FOLDER_LINE
import com.arshad.ayn.feature.settings.WEBDAV_LEDE
import com.arshad.ayn.feature.settings.WEBDAV_PASSWORD
import com.arshad.ayn.feature.settings.WEBDAV_TITLE
import com.arshad.ayn.feature.settings.WEBDAV_USER
import com.arshad.ayn.feature.settings.addHere
import com.arshad.ayn.feature.settings.dontAddHint
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
import com.arshad.ayn.feature.settings.withOption
import com.arshad.ayn.feature.settings.wrongPassphraseText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.common.aynFieldColors
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.menu.FullCover
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.profiles.Body
import com.arshad.ayn.ui.mobile.profiles.EntryActions
import com.arshad.ayn.ui.mobile.profiles.EntryCopy
import com.arshad.ayn.ui.mobile.profiles.FootButtons
import com.arshad.ayn.ui.mobile.profiles.Headline
import com.arshad.ayn.ui.mobile.profiles.LockEntryPage
import com.arshad.ayn.ui.mobile.profiles.PlateTile
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlinx.coroutines.launch

/**
 * The pages over the shell from Settings › Sync (`m8-sync-phone` 4, 5, 11, 12, the TV's Match): the
 * owner's lock, Google's sign-in, Nextcloud's address and its sign-in in the browser, another WebDAV server's fields,
 * the passphrase, and Match your profiles (with reading them first). Touch Back on the lock leaves
 * even with taps entered. [me] is the open profile, the owner whose lock the gate asks for.
 */
@Composable
internal fun SyncCover(state: SyncUiState, me: Profile, sync: SyncViewModel, time: SyncTime) {
    val shown = state.screen
    if (shown == SyncScreen.Page || shown == SyncScreen.Services || shown is SyncScreen.TurnOff) return
    val account = state.status.account
    // Read here: the cover's dialog provides its own, which a test can't replace.
    val browser = LocalUriHandler.current
    val entry = remember(sync) {
        EntryActions(
            onPress = sync::onPress,
            onSubmitSequence = sync::onSubmitSequence,
            onDigit = sync::onDigit,
            onErase = sync::onErase,
            onBack = sync::onEntryLeave,
            onRejectionShown = sync::onRejectionShown,
        )
    }
    FullCover {
        when (shown) {
            is SyncScreen.OwnerGate -> LockEntryPage(me, shown.lockKind, shown.entry, gateCopy(shown, me), entry)
            is SyncScreen.NextcloudAddress -> NextcloudAddressPage(shown, account, sync)
            is SyncScreen.NextcloudWaiting -> NextcloudWaitingPage(shown, sync, browser)
            is SyncScreen.GoogleSignIn -> GoogleSignInPage(shown, account, sync, browser)
            is SyncScreen.WebDav -> WebDavPage(shown, account, sync)
            is SyncScreen.Passphrase -> PassphrasePage(shown, sync, time)
            is SyncScreen.ReadingMatch -> ReadingMatchPage(shown, account, sync)
            is SyncScreen.Match -> MatchPage(shown, sync)
            SyncScreen.Page, SyncScreen.Services, is SyncScreen.TurnOff -> Unit
        }
    }
}

private fun gateCopy(gate: SyncScreen.OwnerGate, owner: Profile): EntryCopy = EntryCopy(
    label = syncGateKicker(gate.action),
    line = enterLockLine(owner.name, gate.lockKind),
    hint = syncGateHint(gate.lockKind),
    waitTail = GO_BACK_WAIT_TAIL,
    labelIcon = R.drawable.ic_cloud,
)

/** A step's form (`.nf`): [label] after [icon] by the back arrow, [title] and [body], then [content]
 *  (fields, a line under them, the button), scrolling over the keyboard. */
@Composable
private fun FormPage(
    label: String,
    @DrawableRes icon: Int,
    title: String,
    body: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        BackBar(onBack, label = label, labelIcon = icon)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp)
                .risingIn(),
        ) {
            Headline(title)
            body?.let { Body(it) }
            content()
        }
    }
}

/** A step's text field (`.field`): [secret] masks it (and keeps it out of semantics, CMP-SEC-01). */
@Composable
private fun SyncField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = warn,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = aynFieldColors(),
        modifier = modifier.fillMaxWidth().padding(top = 16.dp),
    )
}

/** What a step's fields say under them, fading as it changes (D8). */
private sealed interface StepMessage {
    val text: String

    data class Info(override val text: String) : StepMessage

    data class Problem(override val text: String) : StepMessage

    data class Waiting(override val text: String) : StepMessage
}

@Composable
private fun StepLine(message: StepMessage?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = message,
        // A step stops after an async check while focus stays on its button: the reason is read out.
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.press()) },
        label = "stepLine",
    ) { shown ->
        if (shown == null) return@AnimatedContent
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (shown) {
                is StepMessage.Waiting -> CircularProgressIndicator(Modifier.padding(top = 2.dp).size(15.dp), color = AynColors.Ink2, strokeWidth = 2.dp)
                is StepMessage.Problem -> LineIcon(R.drawable.ic_alert, AynColors.Warn)
                is StepMessage.Info -> Unit
            }
            Text(
                shown.text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = when (shown) {
                    is StepMessage.Problem -> AynColors.Ink
                    is StepMessage.Waiting -> AynColors.Ink2
                    is StepMessage.Info -> AynColors.Ink3
                },
            )
        }
    }
}

@Composable
private fun LineIcon(@DrawableRes icon: Int, tint: Color) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(15.dp))
}

/** A quiet line under a step's fields (`.sup` with an icon): [icon], then [text]. */
@Composable
private fun NoteLine(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineIcon(icon, AynColors.Ink3)
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp), color = AynColors.Ink3)
    }
}

/** Nextcloud's address (`m8-sync-phone` 11), the keyboard up: Continue (or its Done key) asks it for a sign-in. */
@Composable
private fun NextcloudAddressPage(shown: SyncScreen.NextcloudAddress, account: SyncAccount?, sync: SyncViewModel) {
    // The caret after a filled-in address (Sign in again, Change address).
    var address by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(filled(shown.address)) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    val next = { sync.onNextcloudContinue(address.text) }
    val message = if (shown.checking) StepMessage.Waiting(NEXTCLOUD_STARTING) else shown.problem?.let { StepMessage.Problem(setupProblemText(it, account)) }
    FormPage(setupKicker(SyncService.NEXTCLOUD, shown.again), R.drawable.ic_cloud, NEXTCLOUD_ADDRESS_TITLE, PHONE_NEXTCLOUD_ADDRESS_LEDE, sync::onBack) {
        SyncField(
            address,
            { address = it },
            NEXTCLOUD_ADDRESS_LABEL,
            Modifier.focusRequester(field),
            warn = shown.problem != null,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onDone = next,
        )
        StepLine(message)
        WideButton(CONTINUE_BUTTON, next, enabled = !shown.checking, modifier = Modifier.padding(top = 20.dp))
    }
}

/**
 * Nextcloud's sign-in (Login Flow v2): its page opens in [browser] once as this page shows (and
 * again from Open the sign-in page); the page waits for the grant, then checks the Ayn folder, and
 * moves on by itself. Change address goes back to the field; Cancel to Settings › Sync.
 */
@Composable
private fun NextcloudWaitingPage(shown: SyncScreen.NextcloudWaiting, sync: SyncViewModel, browser: UriHandler) {
    var noBrowser by remember { mutableStateOf(false) }
    val open = { noBrowser = !browser.tryOpen(shown.loginUrl) }
    // Saved, so the page coming back after the activity is recreated doesn't open the browser again.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            open()
        }
    }
    val line = when {
        shown.checking -> StepMessage.Waiting(CHECKING_FOLDER)
        noBrowser -> StepMessage.Problem(NO_BROWSER)
        else -> StepMessage.Waiting(PHONE_NEXTCLOUD_WAITING)
    }
    BackHandler(onBack = sync::onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(sync::onBack, label = setupKicker(SyncService.NEXTCLOUD, shown.again), labelIcon = R.drawable.ic_cloud)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 4.dp).risingIn()) {
            Headline(NEXTCLOUD_SIGN_IN_TITLE)
            Body(nextcloudSignInBody(shown.server))
            StepLine(line, Modifier.padding(top = 8.dp))
        }
        FootButtons {
            WideButton(OPEN_SIGN_IN, open, icon = R.drawable.ic_open, enabled = !shown.checking)
            WideButton(CHANGE_ADDRESS, sync::onChangeAddress, kind = ButtonKind.Tonal, enabled = !shown.checking)
            WideButton("Cancel", sync::onCancel, kind = ButtonKind.Quiet)
        }
    }
}

/**
 * Google's sign-in (`m8-sync-phone` 4, device flow): the code to enter on Google's page; Copy code
 * and open copies it and opens the page in [browser] (no page opens by itself: the code comes
 * first). The page waits until the user allows Ayn, then checks the Drive and moves on by itself.
 * First Ayn asks Google for the code; a problem says why, with Try again. Cancel goes back to
 * Settings › Sync.
 */
@Composable
private fun GoogleSignInPage(shown: SyncScreen.GoogleSignIn, account: SyncAccount?, sync: SyncViewModel, browser: UriHandler) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val problem = shown.problem
    val code = shown.code?.takeIf { problem == null }
    // A new code (Try again) clears a browser that wouldn't open.
    var noBrowser by remember(code) { mutableStateOf(false) }
    fun copy(shownCode: DriveCode) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(COPY_CODE, shownCode.userCode))) }
    }
    val line = when {
        problem != null -> StepMessage.Problem(googleProblemText(problem, account))
        code == null -> StepMessage.Waiting(GOOGLE_STARTING)
        shown.checking -> StepMessage.Waiting(GOOGLE_CHECKING)
        noBrowser -> StepMessage.Problem(NO_BROWSER)
        else -> StepMessage.Waiting(googleWaiting(code.minutes))
    }
    BackHandler(onBack = sync::onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(sync::onBack, label = setupKicker(SyncService.GOOGLE_DRIVE, shown.again), labelIcon = R.drawable.ic_gdrive)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 4.dp).risingIn()) {
            Headline(GOOGLE_SIGN_IN_TITLE)
            Body(GOOGLE_SIGN_IN_BODY)
            FadingIn(code) { shownCode -> UserCodeBox(shownCode.userCode, onCopy = { copy(shownCode) }) }
            StepLine(line, Modifier.padding(top = 8.dp))
        }
        FootButtons {
            FadingIn(code) { shownCode ->
                val open = {
                    copy(shownCode)
                    noBrowser = !browser.tryOpen(shownCode.url)
                }
                WideButton(copyAndOpen(shownCode.page), open, icon = R.drawable.ic_open, enabled = !shown.checking)
            }
            if (problem != null) WideButton(TRY_AGAIN, sync::onGoogleTryAgain, icon = R.drawable.ic_retry)
            WideButton("Cancel", sync::onCancel, kind = ButtonKind.Tonal)
        }
    }
}

/** [content] for [code] while there is one, fading in and out as it comes and goes (D8: alpha
 *  only, the layout taking the new size at once). */
@Composable
private fun FadingIn(code: DriveCode?, content: @Composable (DriveCode) -> Unit) {
    AnimatedContent(
        targetState = code,
        transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.press()) using null },
        label = "googleCode",
    ) { shownCode -> shownCode?.let { content(it) } }
}

// `.ucode2`: the code in a raised well with a faint rim, its copy button at the end.
private val CodeShape = RoundedCornerShape(16.dp)
private val CodeRim = Color.White.copy(alpha = 0.14f)

@Composable
private fun UserCodeBox(code: String, onCopy: () -> Unit) {
    Row(
        Modifier
            .padding(top = 22.dp)
            .fillMaxWidth()
            .background(AynColors.Surface2, CodeShape)
            .border(1.dp, CodeRim, CodeShape)
            .padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            code,
            style = MaterialTheme.typography.titleLarge.atWeight(720).copy(fontSize = 32.sp, letterSpacing = 0.06.em, fontFeatureSettings = "tnum"),
            color = AynColors.Ink,
        )
        IconButton(onClick = onCopy) {
            Icon(painterResource(R.drawable.ic_copy), contentDescription = COPY_CODE, tint = AynColors.Ink2)
        }
    }
}

/** [text] with the caret after it. */
private fun filled(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))

/** Opens [uri] in the browser; false when no app takes it (AndroidUriHandler wraps the
 *  ActivityNotFoundException in an IllegalArgumentException). */
internal fun UriHandler.tryOpen(uri: String): Boolean = try {
    openUri(uri)
    true
} catch (_: IllegalArgumentException) {
    false
}

/**
 * Another WebDAV server (`m8-sync-phone` 12): its address, user name and app password; Connect
 * tries them. A refusal shows under the field it names. The password stays in this page, never in
 * the ViewModel's state.
 */
@Composable
private fun WebDavPage(shown: SyncScreen.WebDav, account: SyncAccount?, sync: SyncViewModel) {
    var address by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(filled(shown.address)) }
    var user by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(filled(shown.user)) }
    var password by remember { mutableStateOf(TextFieldValue()) }
    val ready = address.text.isNotBlank() && user.text.isNotBlank() && password.text.isNotEmpty()
    val connect = { sync.onWebDavConnect(address.text, user.text, password.text) }
    val problem = shown.problem?.let { StepMessage.Problem(setupProblemText(it, account)) }
    val signInProblem = shown.problem?.namesSignIn() == true
    FormPage(setupKicker(SyncService.WEBDAV, shown.again), R.drawable.ic_server, WEBDAV_TITLE, WEBDAV_LEDE, sync::onBack) {
        SyncField(address, { address = it }, WEBDAV_ADDRESS, warn = problem != null && !signInProblem, keyboardType = KeyboardType.Uri)
        StepLine(problem.takeIf { !signInProblem })
        SyncField(user, { user = it }, WEBDAV_USER, warn = signInProblem)
        SyncField(password, { password = it }, WEBDAV_PASSWORD, warn = signInProblem, secret = true, imeAction = ImeAction.Done, onDone = { if (ready) connect() })
        StepLine(if (shown.checking) StepMessage.Waiting(WEBDAV_CHECKING) else problem.takeIf { signInProblem })
        NoteLine(R.drawable.ic_folder, WEBDAV_FOLDER_LINE)
        WideButton("Connect", connect, enabled = ready && !shown.checking, modifier = Modifier.padding(top = 20.dp))
    }
}

/**
 * The sync passphrase (`m8-sync-phone` 5), the keyboard up: a new one for a folder no other device
 * uses yet (at least [MIN_PASSPHRASE] characters), else the one the folder's files were locked with.
 * A wrong one marks the field with the reason under it. The button (or the keyboard's Done) links;
 * the typed passphrase is handed over as characters the link wipes.
 */
@Composable
private fun PassphrasePage(shown: SyncScreen.Passphrase, sync: SyncViewModel, time: SyncTime) {
    var passphrase by remember { mutableStateOf(TextFieldValue()) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    val ready = passphrase.text.isNotEmpty() && (!shown.mayStartFolder || passphrase.text.length >= MIN_PASSPHRASE)
    val link = { if (ready && !shown.linking) sync.onPassphrase(passphrase.text.toCharArray()) }
    val account = shown.account
    val failure = shown.failure
    val message = when {
        shown.linking -> StepMessage.Waiting(linkingLine(shown.joining))
        shown.wrong -> StepMessage.Problem(wrongPassphraseText(shown.devices))
        failure != null -> StepMessage.Problem(syncFailureLine(failure, account, PhoneSyncWords))
        shown.mayStartFolder -> StepMessage.Info(PASSPHRASE_HINT)
        else -> null
    }
    FormPage(
        label = passphraseKicker(account),
        icon = account.service.icon,
        title = if (shown.joining) JOIN_PASSPHRASE_TITLE else NEW_PASSPHRASE_TITLE,
        body = if (shown.joining) joinPassphraseBody(shown.devices, PhoneSyncWords, time, account) else newPassphraseBody(PhoneSyncWords, account),
        onBack = sync::onBack,
    ) {
        SyncField(
            passphrase,
            { passphrase = it },
            PASSPHRASE_LABEL,
            Modifier.focusRequester(field),
            warn = shown.wrong || failure != null,
            secret = true,
            imeAction = ImeAction.Done,
            onDone = link,
        )
        StepLine(message)
        WideButton(if (shown.joining) CONTINUE_BUTTON else TURN_ON_BUTTON, link, enabled = ready && !shown.linking, modifier = Modifier.padding(top = 20.dp))
    }
}

/** Reading the profiles to match after a join; if they couldn't be read, why, then Try again or
 *  Cancel (which unlinks, as Back does). */
@Composable
private fun ReadingMatchPage(shown: SyncScreen.ReadingMatch, account: SyncAccount?, sync: SyncViewModel) {
    BackHandler(onBack = sync::onBack)
    val failure = shown.failure
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(sync::onBack, label = matchKicker(account, PhoneSyncWords), labelIcon = account?.service?.icon ?: R.drawable.ic_cloud)
        // Keyed, so a failed read rises in as its own page.
        key(failure != null) {
            Column(Modifier.weight(1f).padding(horizontal = 24.dp).risingIn()) {
                if (failure == null) {
                    Headline(MATCH_TITLE)
                    StepLine(StepMessage.Waiting(READING_MATCH), Modifier.padding(top = 8.dp))
                } else {
                    Headline(MATCH_UNREAD_TITLE)
                    Body(syncFailureLine(failure, account, PhoneSyncWords))
                }
            }
        }
        if (failure != null) {
            FootButtons {
                WideButton(TRY_AGAIN, sync::onReadMatchAgain, icon = R.drawable.ic_retry)
                WideButton("Cancel", sync::onCancel, kind = ButtonKind.Tonal)
            }
        }
    }
}

/**
 * Match your profiles (M8-D18, as the TV draws it): this phone's profiles, each With one on the
 * other devices or kept apart (the owner's and a shared one fixed, nothing to tap), then those only
 * there, each added here or not. A tap opens a row's choices in a sheet. Start syncing keeps the
 * choices; Cancel (or Back) unlinks. While Start syncing runs the page takes nothing else.
 */
@Composable
private fun MatchPage(shown: SyncScreen.Match, sync: SyncViewModel) {
    BackHandler(onBack = sync::onBack)
    val match = shown.match
    val preview = match.preview
    val idle = !shown.starting
    SetupPage(
        title = MATCH_TITLE,
        lede = matchLede(preview),
        bar = { BackBar(sync::onBack, label = matchKicker(shown.account, PhoneSyncWords), labelIcon = shown.account?.service?.icon ?: R.drawable.ic_cloud) },
        note = matchNote(match),
        foot = {
            WideButton(START_SYNCING, sync::onStartSyncing, icon = R.drawable.ic_cloud, busy = shown.starting)
            WideButton("Cancel", sync::onCancel, kind = ButtonKind.Tonal, enabled = idle)
        },
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            preview.local.forEach { profile ->
                key(profile.syncId) {
                    MatchRow(profile, hereValue(match, profile), hereHint(match, profile), fixed = profile.syncId in match.fixed, idle) { sync.onMatchRow(profile.syncId) }
                }
            }
            if (match.remoteOnly.isNotEmpty()) SubHeading(onlyThereHeading(preview))
            match.remoteOnly.forEach { profile ->
                key(profile.syncId) {
                    MatchRow(profile, thereValue(match, profile, PhoneSyncWords), thereHint(match, profile, PhoneSyncWords), fixed = false, idle) {
                        sync.onMatchRow(profile.syncId)
                    }
                }
            }
        }
        NoteLine(R.drawable.ic_lock, matchLocksLine(preview), Modifier.padding(horizontal = SetupSide))
    }
    StageSheet(shown.sheet, onDismiss = sync::onDismiss, onBack = sync::onBack, stageKey = { it }) { sheet ->
        when (sheet) {
            is MatchSheet.Here -> MatchHereSheet(shown, sheet.syncId, sync)
            is MatchSheet.There -> MatchThereSheet(shown, sheet.syncId, sync)
        }
    }
}

/** A Match row: the profile's tile, name and tags, why it matches, and its choice; a fixed pair is
 *  text with no chevron. */
@Composable
private fun MatchRow(profile: MatchProfile, value: String, hint: String?, fixed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    SettingRow(
        profile.name,
        onClick = onClick.takeIf { !fixed },
        hint = hint,
        value = value,
        chevron = !fixed,
        enabled = enabled,
        lead = { ProfileTile(profile.name, ProfileTint.of(profile.tint), PlateTile) },
        labelTrail = { ProfileTags(profile.owner, profile.kids) },
    )
}

/** A profile here's choices: With one of its candidates on the other devices, or Keep apart. */
@Composable
private fun MatchHereSheet(shown: SyncScreen.Match, syncId: String, sync: SyncViewModel) {
    val match = shown.match
    val profile = match.preview.local.firstOrNull { it.syncId == syncId } ?: return
    val with = match.with[syncId]
    MatchSheetColumn(profile, "${profile.historyCount} in history") {
        match.candidates(syncId).forEach { remote ->
            ChoiceRow(withOption(remote), on = with == remote.syncId, onClick = { sync.onMatchWith(remote.syncId) })
        }
        ChoiceRow(KEEP_APART, on = with == null, onClick = sync::onKeepApart, hint = KEEP_APART_HINT)
    }
}

/** A profile only on the other devices: Add to this phone (while there is room) or Don't add. */
@Composable
private fun MatchThereSheet(shown: SyncScreen.Match, syncId: String, sync: SyncViewModel) {
    val match = shown.match
    val profile = match.remote(syncId) ?: return
    val skipped = syncId in match.skipped
    val room = !skipped || match.countHere < MAX_PROFILES
    MatchSheetColumn(profile, onlyThereHeading(match.preview)) {
        Dimmed(lit = room) {
            ChoiceRow(addHere(PhoneSyncWords), on = !skipped, onClick = sync::onAddHere, hint = thereHint(match, profile, PhoneSyncWords), enabled = room)
        }
        ChoiceRow(DONT_ADD, on = skipped, onClick = sync::onDontAdd, hint = dontAddHint(profile))
    }
}

/** A Match row's sheet: Match your profiles, whom it is about ([line] under the name), then its choices. */
@Composable
private fun MatchSheetColumn(profile: MatchProfile, line: String, choices: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
        SettingsSheetKicker(MATCH_TITLE)
        SheetHead(profile.name, ProfileTint.of(profile.tint), line)
        Column(Modifier.selectableGroup()) { choices() }
        SheetFoot()
    }
}
