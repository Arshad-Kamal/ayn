package com.arshad.ayn.ui.tv.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.feature.library.BlockNotice
import com.arshad.ayn.feature.library.BlockProfiles
import com.arshad.ayn.feature.library.BlockTarget
import com.arshad.ayn.feature.library.MenuStage
import com.arshad.ayn.feature.library.VideoMenu
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.NoticeLine
import com.arshad.ayn.ui.tv.profiles.EntryCopy
import com.arshad.ayn.ui.tv.profiles.EntryKicker
import com.arshad.ayn.ui.tv.profiles.LockEntryScreen
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.UnlockActions
import com.arshad.ayn.ui.tv.settings.NestInset
import com.arshad.ayn.ui.tv.settings.RowCheck
import com.arshad.ayn.ui.tv.settings.RowIcon
import com.arshad.ayn.ui.tv.settings.RowPlate
import com.arshad.ayn.ui.tv.settings.RowRadio
import com.arshad.ayn.ui.tv.settings.RowTag
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.settings.SettingsSheet
import com.arshad.ayn.ui.tv.settings.SettingsWaitTail
import com.arshad.ayn.ui.tv.settings.SheetRowMinHeight
import com.arshad.ayn.ui.tv.settings.SheetTitle
import com.arshad.ayn.ui.tv.settings.cancelHint
import com.arshad.ayn.ui.tv.settings.enterLock
import com.arshad.ayn.ui.tv.settings.rememberSheetFocus
import com.arshad.ayn.ui.tv.settings.setByOwner
import com.arshad.ayn.feature.settings.spokenList

private const val ALL_KEY = "block-all"
private const val CHOOSE_KEY = "block-choose"
private const val CHOSEN_KEY = "block-chosen"

// `kp-filters` blocksheet (px ÷ 2): Block in N profiles sits 9 dp below the last profile.
private val ChosenTop = 9.dp

/**
 * "Block in which profiles?" (`kp-filters` blocksheet, blockchoose, blockmira; KP-D19): All
 * profiles (OK blocks at once) or Choose profiles, which ticks the open profile and offers Block
 * in N profiles. Each profile says what the block becomes there: the open profile's own filter, a
 * restriction anywhere else. For anyone but the owner, All profiles says it asks for the owner's
 * lock. It opens on All profiles, or back from the owner's lock on what was chosen.
 */
@Composable
internal fun BlockSheet(menu: VideoMenu, stage: MenuStage.Block, from: VideoMenu?, viewModel: VideoMenuViewModel) {
    val blockers = menu.blockers ?: return
    val chosen = stage.chosen
    val focus = rememberSheetFocus(if (from?.stage is MenuStage.BlockGate && chosen != null) CHOSEN_KEY else ALL_KEY)
    SettingsSheet(
        kicker = blockKicker(stage.target),
        title = { SheetTitle("Block in which profiles?") },
        sub = blockSub(stage.target),
        note = blockNote(blockers, choosing = chosen != null),
        scroll = true,
    ) {
        SettingRow(
            label = "All profiles",
            hint = allProfilesHint(blockers),
            minHeight = SheetRowMinHeight,
            onClick = viewModel::onBlockAll,
            modifier = focus.on(ALL_KEY),
            lead = { RowRadio(on = chosen == null, tones = it) },
        )
        SettingRow(
            label = "Choose profiles",
            hint = if (chosen == null) "Only the ones you tick" else null,
            minHeight = SheetRowMinHeight,
            onClick = viewModel::onBlockChoose,
            modifier = focus.on(CHOOSE_KEY),
            lead = { RowRadio(on = chosen != null, tones = it) },
        )
        if (chosen != null) {
            blockers.all.forEach { profile ->
                key(profile.id) {
                    val own = profile.id == blockers.self.id
                    SettingRow(
                        label = profile.name,
                        hint = if (own) "Your own filter" else "A restriction",
                        minHeight = SheetRowMinHeight,
                        onClick = { viewModel.onBlockToggle(profile.id) },
                        modifier = Modifier.padding(start = NestInset),
                        lead = { tones ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RowCheck(profile.id in chosen, tones)
                                ProfilePlate(profile.name, profile.tint, RowPlate, Modifier.padding(start = 11.dp))
                            }
                        },
                        labelTrail = { tones -> if (own) RowTag("You", tones) },
                    )
                }
            }
            SettingRow(
                label = blockInLabel(chosen.size),
                minHeight = SheetRowMinHeight,
                focusable = chosen.isNotEmpty(),
                onClick = viewModel::onBlockChosen,
                modifier = focus.on(CHOSEN_KEY).padding(top = ChosenTop),
                lead = { RowIcon(R.drawable.ic_block, it) },
            )
        }
    }
}

/** The owner's lock before a block reaches other profiles (blockmira's ask; the owner gate's
 *  layout, as Settings' Restrictions shows it). */
@Composable
internal fun BlockGateCover(menu: VideoMenu, gate: MenuStage.BlockGate, viewModel: VideoMenuViewModel) {
    val owner = menu.blockers?.owner ?: return
    val actions = remember(viewModel) {
        UnlockActions(
            onPress = viewModel::onGatePress,
            onSubmitSequence = viewModel::onGateSubmitSequence,
            onDigit = viewModel::onGateDigit,
            onErase = viewModel::onGateErase,
            onBack = viewModel::onBack,
            onRejectionShown = viewModel::onGateRejectionShown,
        )
    }
    val copy = EntryCopy(
        kicker = EntryKicker(R.drawable.ic_block, blockKicker(gate.target)),
        line = enterLock(owner.name, gate.lockKind),
        hint = cancelHint(gate.lockKind),
        waitTail = SettingsWaitTail,
    )
    LockEntryScreen(owner, gate.lockKind, gate.entry, copy, actions)
}

/** What a Block row and its sheet call the target: "Block fern", "Block this video". */
internal fun blockKicker(target: BlockTarget): String = when (target) {
    is BlockTarget.Channel -> "Block ${target.name ?: "this channel"}"
    is BlockTarget.Video -> "Block this video"
}

internal fun blockSub(target: BlockTarget): String = when (target) {
    is BlockTarget.Channel -> "Nothing from ${target.name ?: "this channel"} shows in the profiles you choose, and its videos won’t play there."
    is BlockTarget.Video -> "It won’t show in the profiles you choose, or play there."
}

/** Every profile's name; for anyone but the owner, followed by the owner's lock it asks for. */
internal fun allProfilesHint(blockers: BlockProfiles): String {
    val names = spokenList(blockers.all.map { it.name })
    val owner = blockers.owner ?: return names
    val lock = owner.lock?.takeIf { !blockers.self.isOwner } ?: return names
    return "$names. Asks for ${owner.name}’s ${lockWord(lock)} first"
}

/** The sheet's foot: where the owner's block shows in the other profiles, or how anyone else
 *  blocks without the owner's lock; nothing when the owner has no lock to ask for. */
internal fun blockNote(blockers: BlockProfiles, choosing: Boolean): String? {
    val owner = blockers.owner ?: return null
    return when {
        blockers.self.isOwner && choosing -> "A restriction is one the profile can’t turn off; only you lift it."
        blockers.self.isOwner -> "In the other profiles it shows under ${setByOwner(owner.name)}. Lift it in Settings › Profiles › Restrictions."
        owner.lock != null -> "Blocking only in your profile needs no lock: choose profiles and leave ${blockers.self.name} ticked."
        else -> null
    }
}

internal fun blockInLabel(count: Int): String = if (count == 1) "Block in 1 profile" else "Block in $count profiles"

/** The blocked notice (`kp-filters` blocked): "fern blocked · in Arshad and Mira", or a warning
 *  that nothing could be blocked. */
internal fun blockNoticeLine(notice: BlockNotice): NoticeLine {
    val name = (notice.target as? BlockTarget.Channel)?.name
    if (notice.blockedIn.isEmpty()) {
        val what = when (notice.target) {
            is BlockTarget.Channel -> name ?: "this channel"
            is BlockTarget.Video -> "this video"
        }
        return NoticeLine("Couldn’t block $what", "Try again", undo = false)
    }
    val what = when (notice.target) {
        is BlockTarget.Channel -> name ?: "Channel"
        is BlockTarget.Video -> "Video"
    }
    val where = if (notice.everywhere) "in all profiles" else "in ${spokenList(notice.blockedIn)}"
    return NoticeLine("$what blocked", where)
}

private fun lockWord(kind: LockKind): String = if (kind == LockKind.Pin) "PIN" else "sequence"
