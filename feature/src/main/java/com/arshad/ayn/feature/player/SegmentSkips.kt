package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.SegmentAction

/** Joined segments the player acts on as one (M7-D10): [startMs] to [endMs], [categories] in start order. */
data class SkipSpan(val startMs: Long, val endMs: Long, val categories: List<SegmentCategory>) {
    operator fun contains(positionMs: Long): Boolean = positionMs in startMs..<endMs
}

/** The skip prompt: Skip goes to [span]'s end; it shows until [untilMs] of playback, counted from
 *  [shownAtMs] (both move on by the playback that passed while it was hidden). */
data class SkipPrompt(val span: SkipSpan, val shownAtMs: Long, val untilMs: Long)

/** The note after a skip of [span], automatic or from the prompt; [serial] tells one note from the next. */
data class SkipNote(val span: SkipSpan, val serial: Int)

/**
 * One viewing's SponsorBlock skips (M7-D3, M7-D10), fed the playback position. Segments a gap of
 * at most [JOIN_GAP_MS] apart join. A chain of [SegmentAction.Skip] segments is skipped in one
 * jump; a prompt starts at a chain's first [SegmentAction.Button] segment and runs to the chain's
 * end. The prompt and the note share one slot and the note holds it while up: a prompt due
 * meanwhile shows once it goes. The prompt lasts [promptMs] of playback, its time running only
 * while it is on screen ([promptInView], PAF-D2); a note held back from the screen (the slot out of
 * view) for [noteKeptMs] of playback goes, so Undo never jumps far back (Q37); one on screen goes by
 * its own time. While playback is in a prompt span not undone, the player's controls may skip it
 * ([skippable], PAF-D12). Pure: the caller seeks to what [onPosition], [skipPrompted], [skipSpan]
 * and [undo] return.
 */
class SegmentSkips(
    segments: List<Segment>,
    actions: Map<SegmentCategory, SegmentAction>,
    private val skipNote: Boolean,
    startsInView: Boolean = true,
    private val promptMs: Long = PROMPT_MS,
    private val noteKeptMs: Long = NOTE_KEPT_MS,
) {
    private val autoSpans: List<SkipSpan>
    private val promptSpans: List<SkipSpan>

    init {
        fun Segment.action(): SegmentAction? = actions[category]
        val skipped = segments.filter { it.action() == SegmentAction.Skip }
        val acting = segments.filter { it.action() == SegmentAction.Skip || it.action() == SegmentAction.Button }
        autoSpans = joined(skipped).map(::spanOf)
        promptSpans = joined(acting).mapNotNull { chain ->
            val first = chain.firstOrNull { it.action() == SegmentAction.Button } ?: return@mapNotNull null
            val end = chain.maxOf { it.endMs }
            SkipSpan(first.startMs, end, chain.filter { it.endMs > first.startMs }.map { it.category }.distinct())
        }
    }

    var prompt: SkipPrompt? = null
        private set
    var note: SkipNote? = null
        private set

    /** Whether the slot's place (the prompt's or the note's) is on screen (the [promptInView] call). */
    private var placeInView = startsInView

    /** Playback that passed while the note was held back from the screen. */
    private var noteHeldMs = 0L

    /** Playback that passed while the prompt was out of view, up to [lastPositionMs]. */
    private var heldMs = 0L
    private var lastPositionMs = 0L

    /** Spans undone this viewing, auto or prompt (never equal across kinds: a prompt span carries a Button category). */
    private val undone = mutableSetOf<SkipSpan>()
    private val disarmed = mutableSetOf<SkipSpan>()
    private var noteSerial = 0

    /** The prompt span the position was last in, and whether its prompt is still to come. */
    private var currentPromptSpan: SkipSpan? = null
    private var promptDue = false

    /** Playback is at [positionMs]: where to jump, or `null` to play on. */
    fun onPosition(positionMs: Long): Long? {
        if (!placeInView && prompt != null) heldMs += positionMs - lastPositionMs
        if (!placeInView && note != null) noteHeldMs += (positionMs - lastPositionMs).coerceAtLeast(0)
        lastPositionMs = positionMs
        prompt?.let { if (placeInView && positionMs >= it.untilMs) prompt = null }
        if (noteHeldMs >= noteKeptMs) note = null
        disarmed.retainAll { positionMs in it }
        val auto = autoSpans.firstOrNull { positionMs in it && it !in undone && it !in disarmed }
        if (auto != null) {
            disarmed += auto
            // The note takes the slot; a live prompt it hides comes back after it if still in its span.
            if (skipNote && prompt != null) {
                prompt = null
                promptDue = true
            }
            return skipped(auto)
        }
        val span = promptSpans.firstOrNull { positionMs in it }
        if (span != currentPromptSpan) {
            currentPromptSpan = span
            promptDue = span != null
            prompt = null
        }
        if (span != null && promptDue && note == null && span !in undone) {
            promptDue = false
            heldMs = 0
            prompt = SkipPrompt(span, positionMs, minOf(positionMs + promptMs, span.endMs))
        }
        return null
    }

    /**
     * The slot's place (where the prompt or the note shows) came into view, or went, with playback
     * at [positionMs]. While it is out of view the playback that passes is held back: the prompt
     * doesn't run out, and when it comes back its time moves on by that much, still ending by its
     * span's end; a note held back that long goes ([noteKeptMs]).
     */
    fun promptInView(inView: Boolean, positionMs: Long) {
        if (inView == placeInView) return
        if (!placeInView && note != null) noteHeldMs += (positionMs - lastPositionMs).coerceAtLeast(0)
        if (inView) prompt = prompt?.heldFor(heldMs + positionMs - lastPositionMs)
        heldMs = 0
        lastPositionMs = positionMs
        placeInView = inView
    }

    /**
     * The prompt span playback is in, while it may be skipped from the player's controls (PAF-D12):
     * not undone this viewing. Skipping it ([skipSpan]) works as the prompt's Skip does.
     */
    val skippable: SkipSpan?
        get() = currentPromptSpan?.takeIf { it !in undone }

    /** The controls' skip: hides the prompt, notes the skip, and answers where [skippable] ends. */
    fun skipSpan(): Long? {
        val span = skippable ?: return null
        prompt = null
        promptDue = false
        // Playback leaves the span with the jump: the controls stop offering it at once.
        currentPromptSpan = null
        return skipped(span)
    }

    /** OK on the prompt: hides it, shows the skip note as an automatic skip does (PAF-D11), and answers where its span ends. */
    fun skipPrompted(): Long? {
        val shown = prompt ?: return null
        prompt = null
        return skipped(shown.span)
    }

    /** A jump past [span]: its note when the profile shows one; answers where playback goes on. */
    private fun skipped(span: SkipSpan): Long {
        // Playback goes on from the span's end: the jump itself is no playback held back.
        lastPositionMs = span.endMs
        if (skipNote) {
            noteHeldMs = 0
            note = SkipNote(span, ++noteSerial)
        }
        return span.endMs
    }

    /** BACK on the prompt: hidden until the position leaves its span and comes back. */
    fun dismissPrompt() {
        prompt = null
    }

    /** Undo on the note: answers the skipped span's start; it isn't skipped or prompted again this viewing. */
    fun undo(): Long? {
        val skipped = note ?: return null
        note = null
        undone += skipped.span
        disarmed -= skipped.span
        return skipped.span.startMs
    }

    /** The note went (its time ran out, or another key dropped it). */
    fun dismissNote() {
        note = null
    }

    companion object {
        /** Segments this far apart, or closer, are one span. */
        const val JOIN_GAP_MS = 1_000L

        /** How long a prompt shows by default, in playback time (a pause holds it). */
        const val PROMPT_MS = 8_000L

        /** How much playback a note may be held back from the screen, by default, before it goes. */
        const val NOTE_KEPT_MS = 8_000L
    }
}

private fun SkipPrompt.heldFor(ms: Long): SkipPrompt =
    if (ms == 0L) this else copy(shownAtMs = shownAtMs + ms, untilMs = minOf(untilMs + ms, span.endMs))

/** [segments] in start order, chained where each starts within [SegmentSkips.JOIN_GAP_MS] of the chain's end. */
private fun joined(segments: List<Segment>): List<List<Segment>> {
    val chains = mutableListOf<MutableList<Segment>>()
    var chainEnd = 0L
    for (segment in segments.sortedBy { it.startMs }) {
        val chain = chains.lastOrNull()
        if (chain != null && segment.startMs <= chainEnd + SegmentSkips.JOIN_GAP_MS) {
            chain += segment
            chainEnd = maxOf(chainEnd, segment.endMs)
        } else {
            chains += mutableListOf(segment)
            chainEnd = segment.endMs
        }
    }
    return chains
}

private fun spanOf(chain: List<Segment>): SkipSpan =
    SkipSpan(chain.first().startMs, chain.maxOf { it.endMs }, chain.map { it.category }.distinct())
