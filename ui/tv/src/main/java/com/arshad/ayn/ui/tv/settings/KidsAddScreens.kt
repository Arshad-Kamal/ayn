package com.arshad.ayn.ui.tv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.kids.clockText
import com.arshad.ayn.feature.kids.dayLimitText
import com.arshad.ayn.feature.kids.limitsHeadingText
import com.arshad.ayn.feature.kids.long
import com.arshad.ayn.feature.kids.short
import com.arshad.ayn.feature.settings.FindChannel
import com.arshad.ayn.feature.settings.FindResults
import com.arshad.ayn.feature.settings.KidDraft
import com.arshad.ayn.feature.settings.KidsAddScreen
import com.arshad.ayn.feature.settings.KidsAddUiState
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.TimeSheet
import com.arshad.ayn.feature.settings.bedtimeChoices
import com.arshad.ayn.feature.settings.dayLimitChoices
import com.arshad.ayn.feature.settings.plural
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRow
import com.arshad.ayn.ui.tv.common.ChannelAvatarCard
import com.arshad.ayn.ui.tv.common.ChannelCardWidth
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.common.roomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.focus.FocusRequesters
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.rememberFadeTint
import com.arshad.ayn.ui.tv.profiles.EntryCopy
import com.arshad.ayn.ui.tv.profiles.EntryKicker
import com.arshad.ayn.ui.tv.profiles.GuidedBody
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.LockEntryScreen
import com.arshad.ayn.ui.tv.profiles.MediumPlate
import com.arshad.ayn.ui.tv.profiles.GuidedField
import com.arshad.ayn.ui.tv.profiles.OwnerLockActions
import com.arshad.ayn.ui.tv.profiles.OwnerLockFirstScreen
import com.arshad.ayn.ui.tv.profiles.ProfileGuided
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.TintRow
import com.arshad.ayn.ui.tv.profiles.UnlockActions
import com.arshad.ayn.ui.tv.profiles.color
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan
import java.time.DayOfWeek

private const val STEPS = 3

/**
 * Adding a kids profile, covering the shell over Settings › Profiles (`kp-kids` nolock, kidform,
 * kidchannels, kidtime): the owner's lock (or a first one), the name and colour, the approved
 * channels with Find a channel, the daily limits and bedtime. Each step's sheets slide over it.
 */
@Composable
internal fun KidsAddCover(state: KidsAddUiState, kidsAdd: KidsAddViewModel) {
    val owner = state.owner ?: return
    val entry = remember(kidsAdd) {
        UnlockActions(
            onPress = kidsAdd::onPress,
            onSubmitSequence = kidsAdd::onSubmitSequence,
            onDigit = kidsAdd::onDigit,
            onErase = kidsAdd::onErase,
            onBack = kidsAdd::onBack,
            onRejectionShown = kidsAdd::onRejectionShown,
        )
    }
    when (val shown = state.screen) {
        KidsAddScreen.Closed -> Unit
        is KidsAddScreen.OwnerGate -> LockEntryScreen(owner, shown.lockKind, shown.entry, ownerGateCopy(owner, shown.lockKind), entry)
        is KidsAddScreen.OwnerLockNeeded -> OwnerLockFirstScreen(
            step = shown.step,
            owner = owner,
            kicker = "New kids profile",
            then = "Lock your profile, then add the kids profile.",
            actions = OwnerLockActions(kidsAdd::onOwnerLockStart, kidsAdd::onOwnerLockKind, kidsAdd::onBack, entry),
        )
        is KidsAddScreen.Form -> KidFormScreen(state.draft, shown, kidsAdd)
        is KidsAddScreen.Channels -> KidChannelsScreen(state, shown.find, kidsAdd)
        is KidsAddScreen.Time -> KidTimeScreen(state.draft, shown, kidsAdd)
    }
}

private fun ownerGateCopy(owner: Profile, kind: LockKind): EntryCopy = EntryCopy(
    kicker = EntryKicker(R.drawable.ic_plus, "Adding a kids profile"),
    line = "Only the owner adds profiles. ${enterLock(owner.name, kind)}",
    hint = cancelHint(kind),
    waitTail = SettingsWaitTail,
)

/**
 * Step 1 (`kidform`): the name with the system keyboard (the field starts focused while empty, its
 * Done key moves on to Continue) and the colour with the arrows; a refused name says why.
 */
@Composable
private fun KidFormScreen(draft: KidDraft, form: KidsAddScreen.Form, kidsAdd: KidsAddViewModel) {
    val field = remember { FocusRequester() }
    val proceed = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        (if (draft.name.isEmpty()) field else proceed).requestFocus()
    }
    // A refused name puts focus back in the field to fix it.
    LaunchedEffect(form.problem) {
        if (form.problem != null) field.requestFocus()
    }
    BackHandler(onBack = kidsAdd::onBack)
    val tint = draft.tint.color
    Box(Modifier.fillMaxSize().roomLight({ tint }, alpha = { 0.24f }, x = { 0.3f })) {
        ProfileGuided(
            copy = {
                KickerRow("New kids profile · step 1 of $STEPS") { KickerIcon(R.drawable.ic_plus) }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(17.dp),
                ) {
                    ProfilePlate(draft.name, draft.tint, MediumPlate)
                    GuidedField(
                        value = draft.name,
                        onValueChange = kidsAdd::onNameChange,
                        onDone = { proceed.requestFocus() },
                        onDown = { focusManager.moveFocus(FocusDirection.Down) },
                        modifier = Modifier.focusRequester(field),
                    )
                }
                form.problem?.let { FieldProblem(nameProblemText(it, draft.name), Modifier.padding(top = 10.dp, start = 83.dp)) }
                TintRow(draft.tint, kidsAdd::onTintChange, Modifier.padding(top = if (form.problem != null) 11.dp else 17.dp, start = 83.dp))
                GuidedBody("Up to $MAX_PROFILE_NAME characters. A kids profile has no lock of its own: leaving it takes yours.")
            },
            actions = {
                GuidedButton(R.drawable.ic_check, "Continue", ControlStyle.Primary, kidsAdd::onFormContinue, Modifier.focusRequester(proceed))
                GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Secondary, kidsAdd::onBack)
            },
        )
    }
}

// `.chan` rows (px ÷ 2): 20 dp between channels, as Home's channel row.
private val ChannelGap = 20.dp

/**
 * Step 2 (`kidchannels`): the approved channels after Find a channel, then the owner's
 * subscriptions, each checked while approved; OK approves or takes the approval back. Find a
 * channel's sheet ([find]) slides over the page; focus goes back to its tile when it closes.
 */
@Composable
private fun KidChannelsScreen(state: KidsAddUiState, find: FindChannel?, kidsAdd: KidsAddViewModel) {
    val draft = state.draft
    val name = draft.name.trim()
    val findTile = remember { FocusRequester() }
    val approvedFocus = remember { FocusRequesters() }
    val firstSubscription = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        when {
            draft.channels.isNotEmpty() -> approvedFocus[draft.channels.first().url]?.requestFocus()
            state.ownChannels.isNotEmpty() -> firstSubscription.requestFocus()
            else -> findTile.requestFocus()
        }
    }
    FocusOnClose(open = find != null, target = findTile)
    Overlays<FindChannel, Unit>(
        sheet = find,
        cover = null,
        onRailShade = {},
        sheetContent = { FindChannelSheet(it, draft, kidsAdd) },
        coverContent = {},
        sheetKey = { FindChannel::class },
    ) {
        KidsSetupPage(
            draft = draft,
            step = 2,
            title = "Which channels can $name watch?",
            lede = "$name sees these channels’ videos, new ones too, and nothing else. OK adds or removes a channel.",
            note = "Approved channels become $name’s subscriptions; only you can change them.",
            onBack = kidsAdd::onBack,
            foot = {
                FootButton(R.drawable.ic_check, "Continue", kidsAdd::onChannelsContinue, style = ControlStyle.Primary)
                FootButton(R.drawable.ic_back, "Back", kidsAdd::onBack)
            },
        ) {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                CardRow(
                    title = "Approved for $name",
                    itemWidth = ChannelCardWidth,
                    itemSpacing = ChannelGap,
                    aside = if (draft.channels.isEmpty()) "None yet" else plural(draft.channels.size, "channel"),
                ) { rowFocused ->
                    item(key = "find") {
                        ChannelAvatarCard(
                            name = "Find a channel",
                            avatarUrl = null,
                            meta = "Search YouTube",
                            focusKey = "find",
                            onClick = kidsAdd::onFindChannel,
                            modifier = Modifier.focusRequester(findTile),
                            rowFocused = rowFocused,
                            icon = R.drawable.ic_nav_search,
                        )
                    }
                    itemsIndexed(draft.channels, key = { _, it -> "approved:${it.url}" }) { index, channel ->
                        ChannelAvatarCard(
                            name = channel.name,
                            avatarUrl = channel.avatarUrl,
                            meta = subscribersText(channel.subscriberCount),
                            focusKey = "approved:${channel.url}",
                            onClick = {
                                // The card leaves the row: its neighbour (or Find a channel) takes focus first.
                                val next = draft.channels.getOrNull(index + 1) ?: draft.channels.getOrNull(index - 1)
                                (next?.let { approvedFocus[it.url] } ?: findTile).requestFocus()
                                kidsAdd.onToggleChannel(channel)
                            },
                            modifier = Modifier.focusRequester(approvedFocus.of(channel.url)),
                            rowFocused = rowFocused,
                            checked = true,
                        )
                    }
                }
                if (state.ownChannels.isNotEmpty()) {
                    CardRow(title = "From your subscriptions", itemWidth = ChannelCardWidth, itemSpacing = ChannelGap) { rowFocused ->
                        itemsIndexed(state.ownChannels, key = { _, it -> "own:${it.channelUrl}" }) { index, subscription ->
                            ChannelAvatarCard(
                                name = subscription.name,
                                avatarUrl = subscription.avatarUrl,
                                meta = null,
                                focusKey = "own:${subscription.channelUrl}",
                                onClick = { kidsAdd.onToggleSubscription(subscription) },
                                modifier = if (index == 0) Modifier.focusRequester(firstSubscription) else Modifier,
                                rowFocused = rowFocused,
                                checked = draft.approves(subscription.channelUrl),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Find a channel (unmocked; the `kp-filters` keywords sheet's field): the name typed with the
 * system keyboard, whose Done searches YouTube; the channels found are rows, OK approves one and
 * closes the sheet. Focus moves to the first found; UP returns to the field.
 */
@Composable
private fun FindChannelSheet(find: FindChannel, draft: KidDraft, kidsAdd: KidsAddViewModel) {
    val first = remember { FocusRequester() }
    val results = find.results
    LaunchedEffect(results) {
        if (results is FindResults.Found) {
            withFrameNanos { }
            first.requestFocus()
        }
    }
    SettingsSheet(
        kicker = "Approve for ${draft.name.trim()}",
        title = { SheetTitle("Find a channel") },
        sub = "Type the channel’s name, then press Done.",
        scroll = true,
    ) {
        SettingEditRow(
            label = null,
            value = find.query,
            onValueChange = kidsAdd::onFindQueryChange,
            onDone = kidsAdd::onFindSearch,
            readOnly = results == FindResults.Searching,
            minHeight = SheetRowMinHeight,
            capitalization = KeyboardCapitalization.None,
            leadIcon = R.drawable.ic_nav_search,
        )
        when (results) {
            FindResults.Idle -> Unit
            FindResults.Searching -> SheetLine("Searching YouTube…")
            is FindResults.Found -> {
                SheetGroupHeading("Channels for “${results.query}”")
                results.channels.forEachIndexed { index, channel ->
                    FoundChannelRow(channel, draft.approves(channel.url), { kidsAdd.onFindPick(channel) }, if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
            is FindResults.NoMatch -> SheetLine("No channel matches “${results.query}”. Try its name as YouTube shows it.")
            FindResults.Failed -> SheetLine("Ayn couldn’t reach YouTube. Check the connection, then press Done again.")
        }
    }
}

private val FoundAvatar = 26.dp

@Composable
private fun FoundChannelRow(channel: ChannelItem, approved: Boolean, onClick: () -> Unit, modifier: Modifier) {
    SettingRow(
        label = channel.name,
        hint = subscribersText(channel.subscriberCount),
        value = if (approved) "Approved" else null,
        chevron = false,
        minHeight = SheetRowMinHeight,
        onClick = onClick,
        modifier = modifier,
        lead = { Box(Modifier.size(FoundAvatar).clip(CircleShape)) { Picture(channel.avatarUrl) } },
    )
}

/** A line of text among a sheet's rows: what a search is doing, or why it found nothing. */
@Composable
private fun SheetLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp, lineHeight = 1.4.em),
        color = KeColors.Ink2,
        modifier = Modifier.padding(start = RowInset, top = 10.dp),
    )
}

/**
 * Step 3 (`kidtime`): a tile per weekday (OK opens its limit: No limit, then 15-minute steps) and
 * bedtime's start, end and on/off; Add <name> writes it all. A failed Add says so above the foot.
 */
@Composable
private fun KidTimeScreen(draft: KidDraft, time: KidsAddScreen.Time, kidsAdd: KidsAddViewModel) {
    val name = draft.name.trim()
    val tiles = remember { TimeTileFocus() }
    val sheet = time.sheet
    LaunchedEffect(Unit) {
        withFrameNanos { }
        tiles.day(DayOfWeek.MONDAY).requestFocus()
    }
    FocusOnClose(open = sheet != null, target = tiles.opener)
    Overlays<TimeSheet, Unit>(
        sheet = sheet,
        cover = null,
        onRailShade = {},
        sheetContent = {
            TimeSheetContent(it, draft.name.trim(), draft.dailyLimitMinutes, draft.bedtime, kidsAdd::onDayLimitPicked, kidsAdd::onBedtimePicked)
        },
        coverContent = {},
    ) {
        KidsSetupPage(
            draft = draft,
            step = 3,
            title = "When can $name watch?",
            lede = "Time counts while $name’s profile is in use. Press OK on a day to change it in 15-minute steps, or choose No limit.",
            onBack = kidsAdd::onBack,
            foot = {
                FootButton(R.drawable.ic_check, "Add $name", { if (!time.saving) kidsAdd.onAdd() }, style = ControlStyle.Primary)
                FootButton(R.drawable.ic_back, "Back", kidsAdd::onBack)
            },
        ) {
            Column(Modifier.padding(start = KeLayout.ContentStart, end = KeLayout.SafeHorizontal)) {
                TimeSection("Daily limit", limitsHeadingText(draft.dailyLimitMinutes))
                Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    DayOfWeek.entries.forEach { day ->
                        TimeTile(
                            label = day.short(),
                            value = dayLimitText(draft.dailyLimitMinutes[day]),
                            onClick = {
                                tiles.opener = tiles.day(day)
                                kidsAdd.onDayLimit(day)
                            },
                            modifier = Modifier.focusRequester(tiles.day(day)),
                        )
                    }
                }
                TimeSection("Bedtime", "Ayn doesn’t open for $name in between · 30-minute steps")
                Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    TimeTile(
                        label = "Starts",
                        value = clockText(draft.bedtime.startMinute),
                        onClick = {
                            tiles.opener = tiles.start
                            kidsAdd.onBedtimeStart()
                        },
                        modifier = Modifier.focusRequester(tiles.start),
                        width = WideTileWidth,
                        dimmed = !draft.bedtimeOn,
                    )
                    TimeTile(
                        label = "Ends",
                        value = clockText(draft.bedtime.endMinute),
                        onClick = {
                            tiles.opener = tiles.end
                            kidsAdd.onBedtimeEnd()
                        },
                        modifier = Modifier.focusRequester(tiles.end),
                        width = WideTileWidth,
                        dimmed = !draft.bedtimeOn,
                    )
                    TimeTile(label = "Bedtime", value = if (draft.bedtimeOn) "On" else "Off", onClick = kidsAdd::onBedtimeToggle, width = WideTileWidth)
                }
                Text(
                    text = "$name sees a note when 5 minutes are left, and again at 1 minute. At 0 the video stops and its place is " +
                        "saved. More time needs your lock.",
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.4.em),
                    color = KeColors.Ink2,
                    modifier = Modifier.padding(top = 20.dp).widthIn(max = LedeWidth),
                )
                if (time.failed) {
                    Notice(
                        lead = "$name wasn’t added.",
                        text = "Nothing was saved. Press Add $name to try again.",
                        modifier = Modifier.padding(top = 17.dp).widthIn(max = LedeWidth),
                    )
                }
            }
        }
    }
}

/** The time step's tiles, and the one whose sheet is open, for focus to return to. */
private class TimeTileFocus {
    private val days = DayOfWeek.entries.associateWith { FocusRequester() }
    val start = FocusRequester()
    val end = FocusRequester()
    var opener: FocusRequester = days.getValue(DayOfWeek.MONDAY)

    fun day(day: DayOfWeek): FocusRequester = days.getValue(day)
}

/** A day's limit from [limits] or a bound of [bedtime], for [name]: the choices, the current one
 *  marked and focused; a pick goes to [onDayLimit] or [onBedtime]. */
@Composable
internal fun TimeSheetContent(
    sheet: TimeSheet,
    name: String,
    limits: Map<DayOfWeek, Int>,
    bedtime: Bedtime,
    onDayLimit: (Int?) -> Unit,
    onBedtime: (Int) -> Unit,
) {
    when (sheet) {
        is TimeSheet.DayLimit -> {
            val current = limits[sheet.day]
            val choices = dayLimitChoices()
            val initial = choices.indexOf(current).coerceAtLeast(0)
            val focus = rememberSheetFocus(choices[initial].toString())
            SettingsListSheet(
                kicker = "Daily limit",
                title = { SheetTitle(sheet.day.long()) },
                sub = "How long $name can watch on ${sheet.day.long()}s.",
                initialItem = initial,
            ) {
                items(choices, key = { it.toString() }) { minutes ->
                    Choice(dayLimitText(minutes), minutes == current, { onDayLimit(minutes) }, focus.on(minutes.toString()))
                }
            }
        }
        TimeSheet.BedtimeStart, TimeSheet.BedtimeEnd -> {
            val starts = sheet == TimeSheet.BedtimeStart
            val current = if (starts) bedtime.startMinute else bedtime.endMinute
            val other = if (starts) bedtime.endMinute else bedtime.startMinute
            val choices = bedtimeChoices(other)
            val initial = choices.indexOf(current).coerceAtLeast(0)
            val focus = rememberSheetFocus(choices[initial].toString())
            SettingsListSheet(
                kicker = "Bedtime",
                title = { SheetTitle(if (starts) "Starts" else "Ends") },
                sub = "Ayn doesn’t open for $name from ${clockText(bedtime.startMinute)} to ${clockText(bedtime.endMinute)}.",
                initialItem = initial,
            ) {
                items(choices, key = { it }) { minute ->
                    Choice(clockText(minute), minute == current, { onBedtime(minute) }, focus.on(minute.toString()))
                }
            }
        }
    }
}

/** A time step's section heading (`.tsec h2`) with its summary beside it. */
@Composable
private fun TimeSection(title: String, aside: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(weightSpan(640, KeColors.Ink)) { append(title) }
            withStyle(weightSpan(450, KeColors.Ink3).copy(fontSize = 12.5.sp)) { append("   $aside") }
        },
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
        modifier = Modifier.padding(top = 22.dp),
    )
}

// `.day` (px ÷ 2): 98 × 66 dp, 12 dp corners, 8 dp apart; `.day.wide` 150 dp.
private val TileWidth = 98.dp
private val WideTileWidth = 150.dp
private val TileHeight = 66.dp
private val TileGap = 8.dp
private val TileShape = RoundedCornerShape(12.dp)
private val TileStyle = ControlStyle(restFill = KeColors.Well, restContent = KeColors.Ink, focusedScale = 1.06f, ring = false)

/** A time tile (`.day`): its [label] small above its [value]; focus fills it ink. [dimmed] while
 *  what it sets is off (bedtime). */
@Composable
private fun TimeTile(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TileWidth,
    dimmed: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "timeTile")
    val rest = animateFloatAsState(if (dimmed) 0.5f else 1f, KeMotion.fast(), label = "timeTileDim")
    val valueInk = remember(progress, rest) {
        ColorProducer { lerp(KeColors.Ink.copy(alpha = rest.value), KeColors.OnInk, progress.value) }
    }
    ControlSurface(
        onClick = onClick,
        style = TileStyle,
        shape = TileShape,
        modifier = modifier.size(width, TileHeight).onFocusChanged { focused = it.isFocused },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TintedText(
                text = label,
                style = MaterialTheme.typography.labelMedium.atWeight(560).copy(fontSize = 12.sp),
                tint = rememberFadeTint(KeColors.Ink3, KeColors.OnInk.copy(alpha = 0.72f), progress),
            )
            TintedText(
                text = value,
                style = MaterialTheme.typography.titleMedium.atWeight(680).copy(fontSize = 17.sp, fontFeatureSettings = "tnum"),
                maxLines = 1,
                tint = valueInk,
            )
        }
    }
}

// `.setup` (px ÷ 2): 28 dp from the top; the title 36 sp; the lede up to 650 dp; the foot 32 dp up.
private val SetupTop = 28.dp
private val LedeWidth = 650.dp
private val FootBottom = 32.dp

/**
 * A kids add step's page (`.setup`, `kp-kids` kidchannels, kidtime): the kid's plate and "New
 * kids profile", "Step n of 3" at the right, [title], [lede], then [content] (rows of cards reach
 * the screen's edges, so only the copy is inset); [foot]'s buttons along the bottom with [note].
 * The room takes the kid's colour; the page rises in (D8).
 */
@Composable
private fun KidsSetupPage(
    draft: KidDraft,
    step: Int,
    title: String,
    lede: String,
    onBack: () -> Unit,
    foot: @Composable RowScope.() -> Unit,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    val rise = rememberRise()
    val riseFoot = rememberRise(40)
    val tint = draft.tint.color
    val inset = Modifier.padding(start = KeLayout.ContentStart, end = KeLayout.SafeHorizontal)
    Box(Modifier.fillMaxSize().roomLight({ tint }, alpha = { 0.16f }, x = { 0.24f })) {
        Column(Modifier.padding(top = SetupTop).rising(rise)) {
            Row(inset.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KickerRow("New kids profile") { ProfilePlate(draft.name, draft.tint, RowPlate) }
                Spacer(Modifier.weight(1f))
                Text(
                    text = buildAnnotatedString {
                        append("Step ")
                        withStyle(weightSpan(640, KeColors.Ink)) { append("$step") }
                        append(" of $STEPS")
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"),
                    color = KeColors.Ink2,
                )
            }
            Column(inset) {
                Text(title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp), modifier = Modifier.padding(top = 9.dp))
                Text(
                    text = lede,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.4.em),
                    color = KeColors.Ink2,
                    modifier = Modifier.padding(top = 9.dp).widthIn(max = LedeWidth),
                )
            }
            content()
        }
        Row(
            modifier = inset.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = FootBottom).rising(riseFoot),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            foot()
            note?.let {
                Spacer(Modifier.weight(1f))
                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp), color = KeColors.Ink3)
            }
        }
    }
}
