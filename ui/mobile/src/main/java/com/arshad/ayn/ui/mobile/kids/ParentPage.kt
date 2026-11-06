package com.arshad.ayn.ui.mobile.kids

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentScreen
import com.arshad.ayn.feature.kids.ParentUiState
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.kids.ReviewUploads
import com.arshad.ayn.feature.kids.bedtimeText
import com.arshad.ayn.feature.kids.channelsHint
import com.arshad.ayn.feature.kids.parentAside
import com.arshad.ayn.feature.kids.parentLimitsText
import com.arshad.ayn.feature.kids.reviewHint
import com.arshad.ayn.feature.kids.usedTodayText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.profiles.EntryActions
import com.arshad.ayn.ui.mobile.profiles.EntryCopy
import com.arshad.ayn.ui.mobile.profiles.LockEntryPage
import com.arshad.ayn.ui.mobile.settings.PageFoot
import com.arshad.ayn.ui.mobile.settings.PageLede
import com.arshad.ayn.ui.mobile.settings.RowIcon
import com.arshad.ayn.ui.mobile.settings.SettingRow
import com.arshad.ayn.ui.mobile.settings.SettingsFrame
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion

/** The Parent page's ViewModels, built in its entry's store: the [page] for a kid (leaving through
 *  the gate with the owner's pass) and its [review]. */
internal class ParentModels(
    val page: (kidId: Long, leave: (OwnerPass) -> Unit) -> ParentViewModel,
    val review: () -> ParentReviewViewModel,
)

// Wide enough for "1 h · weekends 1 h 30 min" and "7:30 PM – 7:00 AM" on one line (state 14), and
// on a narrow phone at most this much of its width, so the row's label keeps room.
private val ParentValueWidth = 200.dp
private const val VALUE_SHARE = 0.42f

/**
 * A kids profile's Parent page (`m6-kids` 13–20; KP-D10, D11), opened from the kid's menu. Each
 * time it shows it asks for the owner's lock first, and the pass goes when it stops showing (Back,
 * Ayn left), as the TV's. Behind the lock: the review, the approved channels and videos, the daily
 * limits, bedtime, and Leave <kid>'s profile without a second ask; the lists and times are sheets
 * over the page. The review ([review], made once the page opens) is its own page; Back returns to
 * the Parent page. Back on the page, or touch Back on the lock with presses entered, goes to
 * [onClose] (the kid's Home). [time] dates the review's rows.
 */
@Composable
internal fun ParentPage(
    parent: ParentViewModel,
    review: @Composable () -> ParentReviewViewModel,
    time: ShellTime,
    onClose: () -> Unit,
) {
    val state by parent.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(parent) {
        parent.onShown()
        onStopOrDispose { parent.onHidden() }
    }
    // Here, not in the review: during its fade out the page under it takes Back.
    BackHandler(enabled = state.screen == ParentScreen.Review) { parent.onBack() }
    AnimatedContent(
        targetState = state.screen,
        contentKey = { it::class },
        transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.base()) },
        label = "parent",
        modifier = Modifier.fillMaxSize().background(AynColors.Ground),
    ) { screen ->
        val kid = state.kid
        val owner = state.owner
        when (screen) {
            // Reading the owner's lock, or gone: an empty page Back leaves.
            ParentScreen.Closed -> Box(Modifier.fillMaxSize())
            is ParentScreen.OwnerGate -> if (kid != null && owner != null) ParentGate(screen, kid, owner, parent, onClose)
            is ParentScreen.Page, ParentScreen.Review -> if (kid != null) {
                val reviewModel = review()
                val reviewState by reviewModel.uiState.collectAsStateWithLifecycle()
                if (screen is ParentScreen.Page) {
                    ParentRows(kid, state, reviewState.today?.usedSeconds, (reviewState.uploads as? ReviewUploads.Ready)?.videos?.size, parent, onClose)
                    ParentSheets(screen.sheet, state, parent, time)
                } else {
                    ReviewPage(kid, reviewState, reviewModel, parent, time)
                }
            }
        }
    }
}

/** The owner's lock before the page (state 13): touch Back leaves even with presses entered. */
@Composable
private fun ParentGate(gate: ParentScreen.OwnerGate, kid: Profile, owner: Profile, parent: ParentViewModel, onClose: () -> Unit) {
    val actions = remember(parent, onClose) {
        EntryActions(
            onPress = parent::onPress,
            onSubmitSequence = parent::onSubmitSequence,
            onDigit = parent::onDigit,
            onErase = parent::onErase,
            onBack = { if (parent.canLeaveGate()) onClose() },
            onRejectionShown = parent::onRejectionShown,
        )
    }
    LockEntryPage(owner, gate.lockKind, gate.entry, parentGateCopy(kid.name, owner.name, gate.lockKind), actions)
}

/** What the Parent page's lock says (state 13). */
internal fun parentGateCopy(kid: String, owner: String, kind: LockKind): EntryCopy {
    val erases = if (kind == LockKind.Pin) "⌫ erases a digit" else "⌫ erases a press"
    val enter = if (kind == LockKind.Pin) "Enter $owner’s PIN." else "Enter $owner’s sequence, then tap OK."
    return EntryCopy(
        label = "Parent",
        line = "The Parent page needs $owner’s lock. $enter",
        hint = "$erases · Back returns to $kid’s Home",
        waitTail = ", or go back to $kid’s Home.",
        labelIcon = R.drawable.ic_lock,
    )
}

/** The Parent page's rows (state 14); [usedTodaySeconds] and [newUploads] come from the review. */
@Composable
private fun ParentRows(
    kid: Profile,
    state: ParentUiState,
    usedTodaySeconds: Long?,
    newUploads: Int?,
    parent: ParentViewModel,
    onClose: () -> Unit,
) {
    val rules = state.rules
    SettingsFrame("Parent", onBack = { if (!parent.onBack()) onClose() }) {
        state.owner?.let { owner -> item(key = "lede") { PageLede(parentAside(kid.name, owner.name, owner.lock != null), kid) } }
        item(key = "review") {
            ParentRow(R.drawable.ic_list, "Review", reviewHint(kid.name, newUploads), usedTodaySeconds?.let(::usedTodayText), parent::onOpenReview)
        }
        item(key = "channels") {
            ParentRow(R.drawable.ic_nav_subscriptions, "Approved channels", channelsHint(state.channels.map { it.name }), state.channels.size.toString(), parent::onOpenChannels)
        }
        item(key = "videos") {
            ParentRow(R.drawable.ic_play, "Approved videos", "Single videos from other channels", state.videos.size.toString(), parent::onOpenVideos)
        }
        item(key = "limits") {
            ParentRow(R.drawable.ic_later, "Daily limits", null, rules?.let { parentLimitsText(it.restrictions.dailyLimitMinutes) }, parent::onOpenDailyLimits)
        }
        item(key = "bedtime") {
            ParentRow(R.drawable.ic_moon, "Bedtime", null, rules?.let { bedtimeText(it.restrictions.bedtime) }, parent::onOpenBedtime)
        }
        item(key = "leave") {
            SettingRow(
                label = "Leave ${kid.name}’s profile",
                onClick = parent::onLeave,
                hint = "Opens Who’s watching? on this phone",
                chevron = false,
                lead = { RowIcon(R.drawable.ic_leave) },
            )
        }
        item(key = "foot") { PageFoot("Back closes the Parent page; ${kid.name}’s profile stays on, and only your lock leaves it.") }
    }
}

@Composable
private fun ParentRow(@DrawableRes icon: Int, label: String, hint: String?, value: String?, onClick: () -> Unit) {
    val valueWidth = minOf(ParentValueWidth, LocalConfiguration.current.screenWidthDp.dp * VALUE_SHARE)
    SettingRow(label = label, onClick = onClick, hint = hint, value = value, lead = { RowIcon(icon) }, valueWidth = valueWidth)
}
