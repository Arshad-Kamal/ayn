package com.arshad.ayn.ui.tv.kids

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentScreen
import com.arshad.ayn.feature.kids.ParentSheet
import com.arshad.ayn.feature.kids.ParentUiState
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.kids.ReviewUploads
import com.arshad.ayn.feature.settings.TimeSheet
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.common.PageHead
import com.arshad.ayn.ui.tv.common.roomLight
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.profiles.LockEntryScreen
import com.arshad.ayn.ui.tv.profiles.UnlockActions
import com.arshad.ayn.ui.tv.profiles.color
import com.arshad.ayn.ui.tv.settings.ChannelMonogramLead
import com.arshad.ayn.ui.tv.settings.RemoveRow
import com.arshad.ayn.ui.tv.settings.RowIcon
import com.arshad.ayn.ui.tv.settings.RowInset
import com.arshad.ayn.ui.tv.settings.RowSwitch
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.settings.SettingsSheet
import com.arshad.ayn.ui.tv.settings.SheetRowMinHeight
import com.arshad.ayn.ui.tv.settings.SheetTitle
import com.arshad.ayn.ui.tv.settings.TimeSheetContent
import com.arshad.ayn.feature.kids.dayLimitText
import com.arshad.ayn.feature.kids.long
import com.arshad.ayn.ui.tv.settings.monthDay
import com.arshad.ayn.feature.kids.parentLimitsText
import com.arshad.ayn.feature.kids.clockText
import com.arshad.ayn.feature.kids.bedtimeSub
import com.arshad.ayn.feature.kids.bedtimeText
import com.arshad.ayn.feature.kids.channelsHint
import com.arshad.ayn.feature.kids.parentAside
import com.arshad.ayn.feature.kids.reviewHint
import com.arshad.ayn.feature.kids.usedTodayText
import com.arshad.ayn.ui.tv.settings.rememberSheetFocus
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import java.time.DayOfWeek

internal const val REVIEW_KEY = "parent-review"
private const val CHANNELS_KEY = "parent-channels"
private const val VIDEOS_KEY = "parent-videos"
private const val LIMITS_KEY = "parent-limits"
private const val BEDTIME_KEY = "parent-bedtime"
private const val LEAVE_KEY = "parent-leave"
private const val BEDTIME_ON_KEY = "on"

// `.parentlist` (px ÷ 2): 590 dp wide, 17 dp under the page head; the note 13 dp under the rows.
private val ListWidth = 590.dp
private val ListTop = 17.dp

/**
 * A kids profile's Parent page (`kp-kids` parentgate, parent, review; KP-D10, D11), a rail
 * destination of the kid's shell. Each time it shows it asks for the owner's lock first (a cover:
 * the rail hides), and drops the pass when it stops showing (BACK, the shell closing, Ayn left).
 * The page lists the review, the approved channels and videos, the daily limits, bedtime and
 * Leave <kid>'s profile; their sheets slide over it (the rail dims) and focus goes back to the
 * row that opened one. The review ([review], made once the page opens) replaces the page; BACK
 * returns to it, and BACK on the page or the gate goes to [onClose] (the kid's Home). [time] dates
 * the review's rows.
 */
@Composable
internal fun ParentPage(
    parent: ParentViewModel,
    review: @Composable () -> ParentReviewViewModel,
    time: ShellTime,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onClose: () -> Unit,
    onRailShade: (RailShade) -> Unit,
) {
    val state by parent.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(parent) {
        parent.onShown()
        onStopOrDispose { parent.onHidden() }
    }
    val back = { if (!parent.onBack()) onClose() }
    BackHandler(onBack = back)
    val rows = remember { RowFocus() }
    var opener by remember { mutableStateOf(REVIEW_KEY) }
    // The day or bedtime bound whose time sheet opened last: its list reopens on that row.
    var timeOpener by remember { mutableStateOf<String?>(null) }
    val screen = state.screen
    val sheet = (screen as? ParentScreen.Page)?.sheet
    FocusOnClose(open = sheet != null, target = rows.requester(opener))
    val gate = screen as? ParentScreen.OwnerGate
    val unlocked = screen is ParentScreen.Page || screen == ParentScreen.Review
    val actions = remember(parent) {
        UnlockActions(parent::onPress, parent::onSubmitSequence, parent::onDigit, parent::onErase, back, parent::onRejectionShown)
    }
    val ownerTint = state.owner?.tint?.color ?: KeColors.Ink

    Overlays(
        sheet = sheet,
        cover = gate,
        onRailShade = onRailShade,
        sheetContent = { ParentSheetContent(it, state, parent, timeOpener) { key -> timeOpener = key } },
        coverContent = { shown ->
            val owner = state.owner
            val kid = state.kid
            if (owner != null && kid != null) {
                LockEntryScreen(owner, shown.lockKind, shown.entry, parentGateCopy(kid.name, owner.name, shown.lockKind), actions)
            }
        },
        sheetKey = { it::class to ((it as? ParentSheet.Time)?.sheet) },
        coverKey = { ParentScreen.OwnerGate::class },
    ) {
        if (!unlocked) return@Overlays
        val reviewModel = review()
        val reviewState by reviewModel.uiState.collectAsStateWithLifecycle()
        ScreenFocus(
            onNothingToFocus = onNothingToFocus,
            initialFocusKey = REVIEW_KEY,
            entryKey = when {
                screen != ParentScreen.Review -> "page"
                reviewState.uploads == ReviewUploads.Loading -> null
                else -> "review-${reviewState.uploads::class.simpleName}"
            },
            focusHeldElsewhere = focusHeldElsewhere,
        ) {
            Box(Modifier.fillMaxSize().roomLight({ ownerTint }, alpha = { 0.12f }, x = { 0.4f })) {
                if (screen == ParentScreen.Review) {
                    ReviewContent(state.kid, reviewState, reviewModel, parent, time)
                } else {
                    PageContent(state, reviewState.today?.usedSeconds, (reviewState.uploads as? ReviewUploads.Ready)?.videos?.size, rows, parent) {
                        opener = it
                        timeOpener = null
                    }
                }
            }
        }
    }
}

/** The page's rows by key, for focus to return to the one whose sheet closed. */
private class RowFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun on(key: String): Modifier = Modifier.focusRequester(requester(key)).focusMemory(key)
}

/** The Parent page's rows (`kp-kids` parent); [onOpener] names the row a sheet opens from. */
@Composable
private fun PageContent(
    state: ParentUiState,
    usedTodaySeconds: Long?,
    newUploads: Int?,
    rows: RowFocus,
    parent: ParentViewModel,
    onOpener: (String) -> Unit,
) {
    val kid = state.kid ?: return
    val owner = state.owner
    val rules = state.rules
    Column(Modifier.fillMaxSize()) {
        PageHead("Parent", owner?.let { parentAside(kid.name, it.name, it.lock != null) })
        Column(
            Modifier.padding(start = KeLayout.ContentStart - RowInset, top = ListTop).width(ListWidth),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ParentRow(R.drawable.ic_list, "Review", reviewHint(kid.name, newUploads), usedTodaySeconds?.let(::usedTodayText), rows.on(REVIEW_KEY)) {
                parent.onOpenReview()
            }
            ParentRow(
                R.drawable.ic_nav_subscriptions,
                "Approved channels",
                channelsHint(state.channels.map { it.name }),
                state.channels.size.toString(),
                rows.on(CHANNELS_KEY),
            ) {
                onOpener(CHANNELS_KEY)
                parent.onOpenChannels()
            }
            ParentRow(R.drawable.ic_play, "Approved videos", "Single videos from other channels", state.videos.size.toString(), rows.on(VIDEOS_KEY)) {
                onOpener(VIDEOS_KEY)
                parent.onOpenVideos()
            }
            ParentRow(R.drawable.ic_later, "Daily limits", null, rules?.let { parentLimitsText(it.restrictions.dailyLimitMinutes) }, rows.on(LIMITS_KEY)) {
                onOpener(LIMITS_KEY)
                parent.onOpenDailyLimits()
            }
            ParentRow(R.drawable.ic_moon, "Bedtime", null, rules?.let { bedtimeText(it.restrictions.bedtime) }, rows.on(BEDTIME_KEY)) {
                onOpener(BEDTIME_KEY)
                parent.onOpenBedtime()
            }
            SettingRow(
                label = "Leave ${kid.name}’s profile",
                hint = "Opens Who’s watching? on this Shield",
                onClick = parent::onLeave,
                modifier = rows.on(LEAVE_KEY),
                lead = { RowIcon(R.drawable.ic_back, it) },
            )
        }
        Text(
            text = "BACK closes the Parent page; ${kid.name}’s profile stays on, and only your lock leaves it.",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 1.4.em),
            color = KeColors.Ink3,
            modifier = Modifier.padding(start = KeLayout.ContentStart, top = 13.dp).widthIn(max = 550.dp),
        )
    }
}

@Composable
private fun ParentRow(@DrawableRes icon: Int, label: String, hint: String?, value: String?, modifier: Modifier, onClick: () -> Unit) {
    SettingRow(
        label = label,
        hint = hint,
        value = value,
        chevron = true,
        valueMaxWidth = 260.dp,
        onClick = onClick,
        modifier = modifier,
        lead = { RowIcon(icon, it) },
    )
}

/**
 * The sheet over the Parent page: the approved lists, the limits by day, bedtime, a time to pick.
 * [timeOpener] is the row that opened the last time sheet, for its list to reopen on; [onTimeOpener]
 * names it.
 */
@Composable
private fun ParentSheetContent(
    shown: ParentSheet,
    state: ParentUiState,
    parent: ParentViewModel,
    timeOpener: String?,
    onTimeOpener: (String) -> Unit,
) {
    val kid = state.kid ?: return
    val restrictions = state.rules?.restrictions ?: return
    val kicker = "Parent · ${kid.name}"
    when (shown) {
        ParentSheet.ApprovedChannels -> ApprovedChannelsSheet(kicker, kid, state, parent)
        ParentSheet.ApprovedVideos -> ApprovedVideosSheet(kicker, kid, state, parent)
        ParentSheet.DailyLimits -> DailyLimitsSheet(kicker, kid, restrictions.dailyLimitMinutes, parent, timeOpener ?: DayOfWeek.MONDAY.name, onTimeOpener)
        ParentSheet.Bedtime -> BedtimeSheet(kicker, kid, restrictions.bedtime, state, parent, timeOpener ?: BEDTIME_ON_KEY, onTimeOpener)
        is ParentSheet.Time -> TimeSheetContent(
            sheet = shown.sheet,
            name = kid.name,
            limits = restrictions.dailyLimitMinutes,
            bedtime = restrictions.bedtime ?: state.bedtimeOff,
            onDayLimit = parent::onDayLimitPicked,
            onBedtime = parent::onBedtimePicked,
        )
    }
}

/** The approved channels, most recently approved first; OK takes one off and focus moves on. */
@Composable
private fun ApprovedChannelsSheet(kicker: String, kid: Profile, state: ParentUiState, parent: ParentViewModel) {
    val keys = state.channels.map { it.channelUrl }
    val focus = rememberSheetFocus(keys.firstOrNull().orEmpty())
    SettingsSheet(
        kicker = kicker,
        title = { SheetTitle("Approved channels") },
        sub = "${kid.name} sees these channels’ videos, new ones too. OK removes one.",
        note = "To approve a channel, open your profile and choose Approve for ${kid.name} on any of its videos.",
        scroll = true,
    ) {
        state.channels.forEachIndexed { index, channel ->
            key(keys[index]) {
                RemoveRow(
                    label = channel.name,
                    hint = "Approved ${monthDay(channel.subscribedAtMillis)}",
                    action = "Remove",
                    actionIcon = R.drawable.ic_trash,
                    onClick = {
                        (keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1))?.let(focus::focus)
                        parent.onRemoveChannel(channel.channelUrl)
                    },
                    modifier = focus.on(keys[index]),
                    lead = { ChannelMonogramLead(channel.name, channel.avatarUrl) },
                )
            }
        }
    }
}

/** The approved single videos, newest first; OK takes one off and focus moves on. */
@Composable
private fun ApprovedVideosSheet(kicker: String, kid: Profile, state: ParentUiState, parent: ParentViewModel) {
    val keys = state.videos.map { it.rule.videoId }
    val focus = rememberSheetFocus(keys.firstOrNull().orEmpty())
    SettingsSheet(
        kicker = kicker,
        title = { SheetTitle("Approved videos") },
        sub = "${kid.name} can watch these without their channels approved. OK removes one.",
        note = "To approve a video, open your profile and choose Approve for ${kid.name} on it.",
        scroll = true,
    ) {
        state.videos.forEachIndexed { index, listed ->
            key(keys[index]) {
                RemoveRow(
                    label = listed.rule.title,
                    hint = listed.rule.channelName,
                    action = "Remove",
                    actionIcon = R.drawable.ic_trash,
                    onClick = {
                        (keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1))?.let(focus::focus)
                        parent.onRemoveVideo(listed.rule)
                    },
                    modifier = focus.on(keys[index]),
                )
            }
        }
    }
}

/** A row per weekday with its limit; OK opens the day's choices. Opens on [initial]'s row. */
@Composable
private fun DailyLimitsSheet(
    kicker: String,
    kid: Profile,
    limits: Map<DayOfWeek, Int>,
    parent: ParentViewModel,
    initial: String,
    onOpen: (String) -> Unit,
) {
    val focus = rememberSheetFocus(initial)
    SettingsSheet(
        kicker = kicker,
        title = { SheetTitle("Daily limits") },
        sub = "Time counts while ${kid.name}’s profile is in use. OK on a day changes it in 15-minute steps.",
        scroll = true,
    ) {
        DayOfWeek.entries.forEach { day ->
            SettingRow(
                label = day.long(),
                value = dayLimitText(limits[day]),
                minHeight = SheetRowMinHeight,
                onClick = {
                    onOpen(day.name)
                    parent.onDayLimit(day)
                },
                modifier = focus.on(day.name),
            )
        }
    }
}

/** Bedtime on or off, and its start and end; OK on a bound opens its choices. Opens on [initial]'s row. */
@Composable
private fun BedtimeSheet(
    kicker: String,
    kid: Profile,
    bedtime: Bedtime?,
    state: ParentUiState,
    parent: ParentViewModel,
    initial: String,
    onOpen: (String) -> Unit,
) {
    val focus = rememberSheetFocus(initial)
    val shown = bedtime ?: state.bedtimeOff
    SettingsSheet(kicker = kicker, title = { SheetTitle("Bedtime") }, sub = bedtimeSub(kid.name, bedtime)) {
        SettingRow(
            label = "Bedtime",
            minHeight = SheetRowMinHeight,
            onClick = parent::onBedtimeToggle,
            modifier = focus.on(BEDTIME_ON_KEY),
            control = { RowSwitch(bedtime != null, it) },
        )
        SettingRow(
            label = "Starts",
            value = clockText(shown.startMinute),
            minHeight = SheetRowMinHeight,
            onClick = {
                onOpen(TimeSheet.BedtimeStart.toString())
                parent.onBedtimeStart()
            },
            modifier = focus.on(TimeSheet.BedtimeStart.toString()),
        )
        SettingRow(
            label = "Ends",
            value = clockText(shown.endMinute),
            minHeight = SheetRowMinHeight,
            onClick = {
                onOpen(TimeSheet.BedtimeEnd.toString())
                parent.onBedtimeEnd()
            },
            modifier = focus.on(TimeSheet.BedtimeEnd.toString()),
        )
    }
}
