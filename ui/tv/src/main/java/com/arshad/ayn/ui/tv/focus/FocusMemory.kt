package com.arshad.ayn.ui.tv.focus

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import java.io.Serializable

/**
 * Where an item stands in a list, for [FocusMemory]: [group] names the list (one per row on a
 * screen), [index] the item's place in it. Serializable, so the screen's saved state keeps it.
 */
internal data class FocusSlot(val group: String, val index: Int) : Serializable

/**
 * One screen's record of which item held focus (and its [FocusSlot], when it has one), kept in
 * the destination's saved state so it outlives the destination leaving composition.
 * `focusRestorer` can't do this across navigation: its record is unregistered when its node
 * detaches, which happens before the NavHost saves the entry (Compose UI 1.12.1).
 */
internal class FocusMemory(
    private val lastFocused: MutableState<String?>,
    private val returning: State<String?> = mutableStateOf(null),
    private val lastSlot: MutableState<FocusSlot?> = mutableStateOf(null),
    private val reenter: () -> Unit = {},
) {
    private val items = mutableMapOf<String, FocusMemoryNode>()

    // Where the remembered item stood when focus left for an overlay; the next focus clears it.
    private var vacatedAt: Offset? = null
    private var regionHasFocus = false

    /**
     * The item the screen's next entry focuses ahead of the remembered one ([ScreenFocus]'s
     * `returnKey`). Only a composed item takes focus, so a lazy container holding it scrolls it
     * in as the screen comes back.
     */
    val returnKey: String? get() = returning.value

    /**
     * The remembered item's key, read without observing it, so focus moving doesn't recompose
     * the reader: for a row coming back to bring that item into view where it moved meanwhile.
     */
    val rememberedKey: String? get() = Snapshot.withoutReadObservation { lastFocused.value }

    /**
     * Focuses the [returnKey] item, else the remembered one, else (when it went while an overlay
     * held focus, [regionFocusChanged]) the item now nearest its place, else (it had a slot) the
     * item now in its slot, else [initialKey]'s, if on screen; false leaves the focus change to
     * its default (the first focusable).
     */
    fun restore(initialKey: String?): Boolean =
        focus(returning.value) || focus(lastFocused.value) || focusNearestVacated() || focusInSlot() || focus(initialKey)

    /**
     * The region's focus state, from its `onFocusChanged`. When focus leaves it for an overlay
     * ([overlay], [LocalOverlayHoldsFocus]), the remembered item's place is noted: should the page
     * lose that item meanwhile (a block from its card menu takes it out, KP-D19), focus coming
     * back goes to whatever then stands nearest that place, which in a row or grid is the next
     * card, moved up into it.
     */
    fun regionFocusChanged(hasFocus: Boolean, overlay: Boolean) {
        if (regionHasFocus && !hasFocus) {
            vacatedAt = if (overlay) lastFocused.value?.let(items::get)?.centerInRoot() else null
        }
        regionHasFocus = hasFocus
    }

    private fun focusNearestVacated(): Boolean {
        val place = vacatedAt ?: return false
        vacatedAt = null
        return items.values
            .mapNotNull { node -> node.centerInRoot()?.let { node to it } }
            .sortedBy { (_, center) -> distanceFromVacated(place, center) }
            .any { (node, _) -> node.requestFocus() }
    }

    // The remembered item left its list while the screen was away (a finished video leaves
    // Continue watching): the item now at its index, the next one moved up, else the nearest
    // before it (it was the last).
    private fun focusInSlot(): Boolean {
        val slot = lastSlot.value ?: return false
        return items.values
            .mapNotNull { node -> node.slot?.takeIf { it.group == slot.group && it.index <= slot.index }?.let { node to it.index } }
            .sortedByDescending { (_, index) -> index }
            .any { (node, _) -> node.requestFocus() }
    }

    /** Focuses the item keyed [key] if it is on screen. */
    fun focus(key: String?): Boolean = key?.let(items::get)?.requestFocus() == true

    /** Enters the screen again as its arrival does ([ScreenFocus]: [restore], else the first
     *  focusable, else its nothing-to-focus fallback), for focus left on nothing in it. */
    fun enter() {
        reenter()
    }

    internal fun attach(node: FocusMemoryNode) {
        items[node.key] = node
    }

    internal fun detach(node: FocusMemoryNode) {
        if (items[node.key] === node) items.remove(node.key)
    }

    internal fun record(key: String, slot: FocusSlot?) {
        lastFocused.value = key
        lastSlot.value = slot
        vacatedAt = null
    }
}

/** Squared distance with the vertical part counted double, so a card's neighbour in its own row
 *  wins over one in the next row at the same distance. */
private fun distanceFromVacated(place: Offset, center: Offset): Float {
    val dx = center.x - place.x
    val dy = (center.y - place.y) * 2
    return dx * dx + dy * dy
}

/** Null outside a [ScreenFocus]: [focusMemory] then does nothing. */
internal val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** True while an overlay over the page (a card menu's sheet, covering screen or notice) holds
 *  focus, for [FocusMemory.regionFocusChanged]. */
internal val LocalOverlayHoldsFocus = compositionLocalOf { false }

/**
 * Marks a focusable item that should get focus back when its screen is returned to.
 * [key] must be stable and unique on the screen (a video id, "hero-play"); place this before
 * the item's focusable, like `onFocusChanged` (CMP-FOCUS-02). An item in a list whose items can
 * leave it while the screen is away gives its [slot], so focus coming back finds its neighbour.
 */
internal fun Modifier.focusMemory(key: String, slot: FocusSlot? = null): Modifier = this then FocusMemoryElement(key, slot)

private data class FocusMemoryElement(val key: String, val slot: FocusSlot?) : ModifierNodeElement<FocusMemoryNode>() {
    override fun create(): FocusMemoryNode = FocusMemoryNode(key, slot)

    // An update re-sends the node's focus event (auto-invalidation), so a focused item whose
    // list shifted under it records its new slot.
    override fun update(node: FocusMemoryNode) {
        node.rekey(key)
        node.slot = slot
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusMemory"
        properties["key"] = key
        properties["slot"] = slot
    }
}

internal class FocusMemoryNode(key: String, var slot: FocusSlot?) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    FocusEventModifierNode,
    FocusRequesterModifierNode {

    var key: String = key
        private set
    private var memory: FocusMemory? = null

    override fun onAttach() {
        onObservedReadsChanged()
    }

    // The memory is constant per screen (a static local); the read sits in observeReads
    // because lint rejects CompositionLocal reads in onAttach.
    override fun onObservedReadsChanged() {
        observeReads {
            val current = currentValueOf(LocalFocusMemory)
            if (current !== memory) {
                memory?.detach(this)
                memory = current
                current?.attach(this)
            }
        }
    }

    override fun onDetach() {
        memory?.detach(this)
        memory = null
    }

    fun rekey(newKey: String) {
        if (newKey == key) return
        memory?.detach(this)
        key = newKey
        memory?.attach(this)
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) memory?.record(key, slot)
    }

    /** Where the item's centre is now, while it is laid out. */
    fun centerInRoot(): Offset? = if (isAttached) requireLayoutCoordinates().takeIf { it.isAttached }?.boundsInRoot()?.center else null
}
