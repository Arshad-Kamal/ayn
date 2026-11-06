package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.PlaybackFailure
import com.arshad.ayn.core.access.VideoRefusal
import com.arshad.ayn.core.filter.VideoFiltered
import com.arshad.ayn.core.kids.PlayTimeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D8 mapping tests: each PlaybackFailure type → the expected player
 * UI state at the budget boundaries, plus a failed resolve's stop reason per failure kind.
 */
class PlayerPolicyTest {

    private val recoverable = PlaybackFailure.Recoverable("SocketTimeoutException")
    private val attestation = PlaybackFailure.Attestation("attestation required")
    private val protocolFatal = PlaybackFailure.ProtocolFatal("sabr error")

    @Test
    fun `recoverable stays in playback within the budget`() {
        assertEquals(
            PlayerErrorUiState.Retrying(1, PlayerPolicy.RECOVERABLE_RETRY_BUDGET),
            playbackErrorUiState(recoverable, attemptsSpent = 0),
        )
        // m4-09's exact copy scenario: the second of six.
        assertEquals(
            PlayerErrorUiState.Retrying(2, 6),
            playbackErrorUiState(recoverable, attemptsSpent = 1),
        )
        assertEquals(
            PlayerErrorUiState.Retrying(6, 6),
            playbackErrorUiState(recoverable, attemptsSpent = PlayerPolicy.RECOVERABLE_RETRY_BUDGET - 1),
        )
    }

    @Test
    fun `recoverable budget exhaustion is terminal`() {
        assertEquals(
            PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_EXHAUSTED),
            playbackErrorUiState(recoverable, attemptsSpent = PlayerPolicy.RECOVERABLE_RETRY_BUDGET),
        )
    }

    @Test
    fun `attestation refreshes within its own smaller budget`() {
        assertEquals(
            PlayerErrorUiState.Refreshing(1, PlayerPolicy.ATTESTATION_RETRY_BUDGET),
            playbackErrorUiState(attestation, attemptsSpent = 0),
        )
        assertEquals(
            PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_ATTESTATION),
            playbackErrorUiState(attestation, attemptsSpent = PlayerPolicy.ATTESTATION_RETRY_BUDGET),
        )
    }

    @Test
    fun `protocol fatal is terminal immediately regardless of attempts`() {
        assertEquals(
            PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_PROTOCOL),
            playbackErrorUiState(protocolFatal, attemptsSpent = 0),
        )
        assertEquals(
            PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_PROTOCOL),
            playbackErrorUiState(protocolFatal, attemptsSpent = 99),
        )
    }

    @Test
    fun `a resolve that never reached YouTube stops as offline`() {
        assertEquals(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, resolveStopReason(access(AccessFailure.OFFLINE)))
    }

    @Test
    fun `a refused video stops as unavailable`() {
        assertEquals(PlayerPolicy.TERMINAL_UNAVAILABLE, resolveStopReason(access(AccessFailure.UNAVAILABLE)))
    }

    @Test
    fun `a made-for-kids refusal stops as made-for-kids with no detail`() {
        val refused = access(AccessFailure.UNAVAILABLE, VideoRefusal.MadeForKids)

        assertEquals(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, resolveStopReason(refused))
        assertNull(resolveStopDetail(refused))
    }

    @Test
    fun `a not-started video stops as not-started carrying YouTube's sentence`() {
        val refused = access(AccessFailure.UNAVAILABLE, VideoRefusal.NotStarted("This live event will begin in 5 days."))

        assertEquals(PlayerPolicy.TERMINAL_NOT_STARTED, resolveStopReason(refused))
        assertEquals("This live event will begin in 5 days.", resolveStopDetail(refused))
    }

    @Test
    fun `a stop without a named refusal carries no detail`() {
        assertNull(resolveStopDetail(access(AccessFailure.UNAVAILABLE)))
        assertNull(resolveStopDetail(VideoFiltered()))
    }

    @Test
    fun `a video the profile's filters hide stops as filtered`() {
        assertEquals(PlayerPolicy.TERMINAL_FILTERED, resolveStopReason(VideoFiltered()))
    }

    @Test
    fun `a kids profile's stopped time stops as time-up`() {
        assertEquals(PlayerPolicy.TERMINAL_TIME_UP, resolveStopReason(PlayTimeUp()))
    }

    @Test
    fun `an unreadable answer or a failure outside the seam stops as unreadable`() {
        assertEquals(PlayerPolicy.TERMINAL_RESOLVE_UNREADABLE, resolveStopReason(access(AccessFailure.UNREADABLE)))
        assertEquals(PlayerPolicy.TERMINAL_RESOLVE_UNREADABLE, resolveStopReason(access(AccessFailure.RATE_LIMITED)))
        assertEquals(PlayerPolicy.TERMINAL_RESOLVE_UNREADABLE, resolveStopReason(IllegalStateException("manifest")))
        assertEquals(PlayerPolicy.TERMINAL_RESOLVE_UNREADABLE, resolveStopReason(NoSuchMethodError("api band")))
    }

    private fun access(failure: AccessFailure, refusal: VideoRefusal? = null) =
        AccessException("resolve", RuntimeException(), failure, refusal)
}
