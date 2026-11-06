package com.arshad.ayn.playback

import com.arshad.ayn.feature.profiles.ProfilePlayback

/**
 * The profile gate's way to the running playback service (M5-D3/D16): the service attaches its
 * control plane while it lives, and [end] ends that session; with no service running there is
 * nothing to end. App-scoped, main thread only.
 */
class PlaybackHandle : ProfilePlayback {
    private var controlPlane: PlaybackControlPlane? = null

    fun attach(controlPlane: PlaybackControlPlane) {
        this.controlPlane = controlPlane
    }

    fun detach(controlPlane: PlaybackControlPlane) {
        if (this.controlPlane === controlPlane) this.controlPlane = null
    }

    override fun end() {
        controlPlane?.endSession()
    }

    override fun holdsItem(): Boolean = controlPlane?.holdsItem() == true

    /** The held video's place saved now when it moved (M8-D8); nothing with no service running. */
    fun saveMovedPlace() {
        controlPlane?.saveMovedPlace()
    }
}
