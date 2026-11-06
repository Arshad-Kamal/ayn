package com.arshad.ayn.ui.mobile.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.kids.clockText
import com.arshad.ayn.feature.kids.dayLimitText
import com.arshad.ayn.feature.kids.limitsHeadingText
import com.arshad.ayn.feature.kids.long
import com.arshad.ayn.feature.kids.short
import com.arshad.ayn.feature.kids.tileLimitText
import com.arshad.ayn.feature.settings.FindChannel
import com.arshad.ayn.feature.settings.KidDraft
import com.arshad.ayn.feature.settings.KidsAddScreen
import com.arshad.ayn.feature.settings.KidsAddUiState
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.OwnerAction
import com.arshad.ayn.feature.settings.plural
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.menu.FullCover
import com.arshad.ayn.ui.mobile.menu.RowSwitch
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.profiles.EntryActions
import com.arshad.ayn.ui.mobile.profiles.EntryCopy
import com.arshad.ayn.ui.mobile.profiles.LockEntryPage
import com.arshad.ayn.ui.mobile.profiles.NameActions
import com.arshad.ayn.ui.mobile.profiles.NameProfilePage
import com.arshad.ayn.ui.mobile.profiles.OwnerLockActions
import com.arshad.ayn.ui.mobile.profiles.OwnerLockFirstPages
import com.arshad.ayn.ui.mobile.profiles.color
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import java.time.DayOfWeek

private const val STEPS = 3

/**
 * Adding a kids profile (`m6-kids` 21–25), pages over the shell from Profiles' kind sheet: the
 * owner's lock (or a first one), the name and colour, the approved channels with Find a channel,
 * the daily limits and bedtime; each step's sheets slide over it. Touch Back leaves a lock's page
 * even with taps entered; the pads' ⌫ erases.
 */
@Composable
internal fun KidsAddCover(state: KidsAddUiState, kidsAdd: KidsAddViewModel) {
    val owner = state.owner ?: return
    val screen = state.screen
    if (screen == KidsAddScreen.Closed) return
    val entry = remember(kidsAdd) {
        EntryActions(
            onPress = kidsAdd::onPress,
            onSubmitSequence = kidsAdd::onSubmitSequence,
            onDigit = kidsAdd::onDigit,
            onErase = kidsAdd::onErase,
            onBack = kidsAdd::onEntryLeave,
            onRejectionShown = kidsAdd::onRejectionShown,
        )
    }
    FullCover {
        when (screen) {
            KidsAddScreen.Closed -> Unit
            is KidsAddScreen.OwnerGate -> LockEntryPage(owner, screen.lockKind, screen.entry, ownerGateCopy(owner, screen.lockKind), entry)
            is KidsAddScreen.OwnerLockNeeded -> OwnerLockFirstPages(
                step = screen.step,
                owner = owner,
                kicker = "New kids profile",
                then = "Lock your profile, then add the kids profile.",
                tint = state.draft.tint.color,
                actions = OwnerLockActions(kidsAdd::onOwnerLockStart, kidsAdd::onOwnerLockKind, kidsAdd::onBack, kidsAdd::onDismiss, entry),
            )
            is KidsAddScreen.Form -> KidFormPage(state.draft, screen, kidsAdd)
            is KidsAddScreen.Channels -> KidChannelsPage(state, screen.find, kidsAdd)
            is KidsAddScreen.Time -> KidTimePage(state.draft, screen, kidsAdd)
        }
    }
}

private fun ownerGateCopy(owner: Profile, kind: LockKind): EntryCopy = EntryCopy(
    label = "Adding a kids profile",
    line = enterLockLine(owner.name, kind),
    hint = ownerGateHint(OwnerAction.Add, kind),
    waitTail = GO_BACK_WAIT_TAIL,
    labelIcon = R.drawable.ic_plus,
)

/** Step 1 (state 22): the name, opening with the keyboard up, and the colour; a refused name says why. */
@Composable
private fun KidFormPage(draft: KidDraft, form: KidsAddScreen.Form, kidsAdd: KidsAddViewModel) {
    val actions = remember(kidsAdd) { NameActions(kidsAdd::onNameChange, kidsAdd::onTintChange, kidsAdd::onFormContinue, kidsAdd::onBack) }
    val problem = form.problem
    NameProfilePage(
        label = "New kids profile",
        name = draft.name,
        tint = draft.tint,
        line = problem?.let { nameProblemText(it, draft.name) }
            ?: "Up to $MAX_PROFILE_NAME characters. A kids profile has no lock of its own: leaving it takes yours.",
        refused = problem != null,
        confirm = "Continue",
        confirmEnabled = true,
        actions = actions,
        barEnd = { StepCount(1) },
    )
}

private const val CHANNEL_COLUMNS = 4

/**
 * Step 2 (state 23): Find a channel and the approved channels, then the owner's subscriptions,
 * each ringed and ticked in the kid's colour while approved; a tap approves or takes the approval
 * back. Find a channel's sheet ([find], state 25) slides over the page.
 */
@Composable
private fun KidChannelsPage(state: KidsAddUiState, find: FindChannel?, kidsAdd: KidsAddViewModel) {
    val draft = state.draft
    val name = draft.name.trim()
    val tint = draft.tint.color
    KidsStepPage(
        step = 2,
        onBack = kidsAdd::onBack,
        note = "Approved channels become $name’s subscriptions; only you can change them.",
        action = { WideButton("Continue", kidsAdd::onChannelsContinue) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(CHANNEL_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = PageSide, end = PageSide, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                StepHeading("Which channels can $name watch?", "$name sees these channels’ videos, new ones too, and nothing else. Tap a channel to add or remove it.")
            }
            item(key = "approvedHeading", span = { GridItemSpan(maxLineSpan) }) {
                GroupHeading("Approved for $name", if (draft.channels.isEmpty()) "None yet" else plural(draft.channels.size, "channel"))
            }
            item(key = "find") { FindTile(kidsAdd::onFindChannel) }
            items(draft.channels, key = { "approved:${it.url}" }) { channel ->
                ChannelTile(channel.name, channel.avatarUrl, approved = true, tint, { kidsAdd.onToggleChannel(channel) }, Modifier.animateItem(fadeInSpec = AynMotion.base(), placementSpec = AynMotion.base(), fadeOutSpec = AynMotion.base()))
            }
            if (state.ownChannels.isNotEmpty()) {
                item(key = "ownHeading", span = { GridItemSpan(maxLineSpan) }) { GroupHeading("From your subscriptions", null) }
                items(state.ownChannels, key = { "own:${it.channelUrl}" }) { subscription ->
                    ChannelTile(subscription.name, subscription.avatarUrl, draft.approves(subscription.channelUrl), tint, { kidsAdd.onToggleSubscription(subscription) })
                }
            }
        }
    }
    StageSheet(find, onDismiss = kidsAdd::onDismiss, onBack = kidsAdd::onBack, stageKey = { FindChannel::class }) { shown ->
        FindChannelSheet(shown, draft, kidsAdd)
    }
}

private val AvatarBox = 68.dp
private val AvatarInset = 5.dp

/** A channel to approve (`.chan`): its picture, ringed and ticked in [tint] while [approved] (both
 *  fade and grow in), its name under it. Read out as a checkbox. */
@Composable
private fun ChannelTile(name: String, avatarUrl: String?, approved: Boolean, tint: Color, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val on by animateFloatAsState(if (approved) 1f else 0f, AynMotion.base(), label = "approved")
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .toggleable(value = approved, interactionSource = interaction, indication = null, role = Role.Checkbox, onValueChange = { onToggle() }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(AvatarBox).drawBehind {
                if (on > 0f) {
                    val stroke = 2.5.dp.toPx()
                    drawCircle(tint.copy(alpha = on), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                }
            },
        ) {
            ChannelAvatar(name, avatarUrl, Modifier.padding(AvatarInset))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .graphicsLayer {
                        alpha = on
                        scaleX = 0.6f + 0.4f * on
                        scaleY = 0.6f + 0.4f * on
                    }
                    .background(tint, CircleShape)
                    .border(2.dp, AynColors.Ground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(12.dp))
            }
        }
        TileName(name)
    }
}

/** Find a channel (`.chan.find`): a search mark in an outlined ring, then "Search YouTube". */
@Composable
private fun FindTile(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(AvatarBox).padding(AvatarInset).border(1.5.dp, AynColors.Ink3, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(24.dp))
        }
        TileName("Find a channel")
        Text("Search YouTube", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = AynColors.Ink3)
    }
}

@Composable
private fun TileName(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall.atWeight(640).copy(fontSize = 12.5.sp, lineHeight = 15.sp),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private const val DAY_COLUMNS = 4

/**
 * Step 3 (state 24): a tile per weekday (a tap opens its limit: No limit, then 15-minute steps) and
 * bedtime's start, end and switch; Add <name> writes it all, showing its progress. A failed Add
 * says so just above it.
 */
@Composable
private fun KidTimePage(draft: KidDraft, time: KidsAddScreen.Time, kidsAdd: KidsAddViewModel) {
    val name = draft.name.trim()
    KidsStepPage(
        step = 3,
        onBack = kidsAdd::onBack,
        note = null,
        alert = if (time.failed) {
            { SettingsNotice("$name wasn’t added.", "Nothing was saved. Tap Add $name to try again.") }
        } else {
            null
        },
        action = { WideButton("Add $name", kidsAdd::onAdd, busy = time.saving) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = PageSide)) {
                StepHeading(
                    "When can $name watch?",
                    "Time counts while $name’s profile is in use. Tap a day to change it in 15-minute steps, or choose No limit.",
                )
                GroupHeading("Daily limit", limitsHeadingText(draft.dailyLimitMinutes))
                DayOfWeek.entries.chunked(DAY_COLUMNS).forEach { week ->
                    TileRow {
                        week.forEach { day ->
                            val limit = draft.dailyLimitMinutes[day]
                            TimeTile(
                                day.short(),
                                tileLimitText(limit),
                                { kidsAdd.onDayLimit(day) },
                                Modifier.weight(1f),
                                spoken = day.long(),
                                spokenValue = dayLimitText(limit),
                            )
                        }
                        repeat(DAY_COLUMNS - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                GroupHeading("Bedtime", "30-minute steps")
                TileRow {
                    val off = !draft.bedtimeOn
                    TimeTile("Starts", clockText(draft.bedtime.startMinute), kidsAdd::onBedtimeStart, Modifier.weight(1f), dimmed = off, spoken = "Bedtime starts")
                    TimeTile("Ends", clockText(draft.bedtime.endMinute), kidsAdd::onBedtimeEnd, Modifier.weight(1f), dimmed = off, spoken = "Bedtime ends")
                    BedtimeTile(draft.bedtimeOn, kidsAdd::onBedtimeToggle, Modifier.weight(1f))
                }
                Text(
                    "$name sees a note when 5 minutes are left, and again at 1 minute. At 0 the video stops and its place is saved. More time needs your lock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AynColors.Ink3,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
    StageSheet(time.sheet, onDismiss = kidsAdd::onDismiss, onBack = kidsAdd::onBack, stageKey = { it }) { sheet ->
        TimeSheetContent(sheet, name, draft.dailyLimitMinutes, draft.bedtime, kidsAdd::onDayLimitPicked, kidsAdd::onBedtimePicked)
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    // Tiles fill the row's height, so a value that wraps (large text) keeps the row even.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

private val TileShape = RoundedCornerShape(14.dp)

/** A time tile (`.day`): its [label] small over its [value] (read out as [spoken] and
 *  [spokenValue]); [dimmed] while what it sets is off (the change fades). It sinks while pressed. */
@Composable
private fun TimeTile(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier,
    dimmed: Boolean = false,
    spoken: String = label,
    spokenValue: String = value,
) {
    val interaction = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(if (dimmed) DIMMED_ALPHA else 1f, AynMotion.base(), label = "tileDim")
    TileFrame(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick), interaction, alpha, label, spoken) {
        Text(
            value,
            style = TileValue,
            maxLines = 2,
            modifier = if (spokenValue != value) Modifier.semantics { contentDescription = spokenValue } else Modifier,
        )
    }
}

/** Bedtime's switch as a tile; the whole tile toggles it. */
@Composable
private fun BedtimeTile(on: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    TileFrame(modifier.toggleable(value = on, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = { onToggle() }), interaction, 1f, "Bedtime", "Bedtime") {
        Box(Modifier.heightIn(max = 30.dp), contentAlignment = Alignment.CenterStart) { RowSwitch(on) }
    }
}

private val TileValue
    @Composable get() = MaterialTheme.typography.titleLarge.atWeight(700).copy(fontSize = 16.sp, fontFeatureSettings = "tnum")

@Composable
private fun TileFrame(
    modifier: Modifier,
    interaction: MutableInteractionSource,
    alpha: Float,
    label: String,
    spoken: String,
    value: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .pressScale(interaction)
            .heightIn(min = 60.dp)
            .clip(TileShape)
            .background(AynColors.Surface2)
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = AynColors.Ink3,
            modifier = if (spoken != label) Modifier.semantics { contentDescription = spoken } else Modifier,
        )
        value()
    }
}

// `.setup` (px = dp): copy 24 dp from the sides.
private val PageSide = 24.dp

/**
 * A kids add step's page (`.setup`, states 23–24): the back arrow, "New kids profile" and "Step n
 * of 3" over the page's [content] (it scrolls), then [alert] (read out as it shows), [note] and
 * [action] at the foot. The page rises in (D8).
 */
@Composable
private fun KidsStepPage(
    step: Int,
    onBack: () -> Unit,
    note: String?,
    action: @Composable () -> Unit,
    alert: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(onBack, label = "New kids profile", end = { StepCount(step) })
        Column(Modifier.weight(1f).risingIn(), content = content)
        if (alert != null) Box(Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) { alert() }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = AynColors.Ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
                )
            }
            action()
        }
    }
}

/** "Step 2 of 3", the step's number in ink. */
@Composable
private fun StepCount(step: Int) {
    Text(
        buildAnnotatedString {
            append("Step ")
            withStyle(weightSpan(700, AynColors.Ink)) { append("$step") }
            append(" of $STEPS")
        },
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = AynColors.Ink2,
    )
}

@Composable
private fun StepHeading(title: String, lede: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 34.sp), modifier = Modifier.semantics { heading() })
        Text(lede, style = MaterialTheme.typography.bodyMedium, color = AynColors.Ink2, modifier = Modifier.padding(top = 12.dp))
    }
}

/** A step's group heading (`.tsec h2`) with its summary beside it, read out as a heading. */
@Composable
private fun GroupHeading(title: String, aside: String?) {
    Text(
        buildAnnotatedString {
            withStyle(weightSpan(680, AynColors.Ink)) { append(title) }
            if (aside != null) withStyle(weightSpan(500, AynColors.Ink3).copy(fontSize = 13.sp)) { append("   $aside") }
        },
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 22.dp, bottom = 4.dp).semantics { heading() },
    )
}
