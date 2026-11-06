package com.arshad.ayn.core.store

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Device settings seam (M7-D9, B-D9): the TV's own choices, the same for every profile. Device-local
 * like [LastUsedProfile] (M5-D16): no profile owns them; a backup carries them, applied only by a
 * restore onto a device with no owner yet (KP-D17).
 */
interface DeviceSettingsStore {
    /** The current settings, then every change. */
    fun settings(): Flow<DeviceSettings>

    /** Applies [transform] to the current settings and stores the result. */
    suspend fun update(transform: (DeviceSettings) -> DeviceSettings)
}

/** Every default is the approved mockup's (`b3-settings`). */
data class DeviceSettings(
    /** The most lines a video plays at, counted on the picture's short side so a portrait Short
     *  caps as a landscape video does (M7-D15); one of [MAX_VIDEO_HEIGHTS]. */
    val maxVideoHeight: Int = DEFAULT_MAX_VIDEO_HEIGHT,
    val frameRateMatching: Boolean = true,
    /** Any value in [INTERFACE_SCALE_PERCENTS]; the settings screen offers a subset. */
    val interfaceScalePercent: Int = DEFAULT_INTERFACE_SCALE_PERCENT,
    val clockInPlayer: Boolean = true,
    val heroOnHome: Boolean = true,
    /** A phone keeps playing when Ayn leaves the screen or the phone locks (M6-D5); the TV always
     *  pauses (m4 D1) and never reads it. */
    val backgroundAudio: Boolean = true,
) {
    init {
        require(maxVideoHeight in MAX_VIDEO_HEIGHTS) { "height $maxVideoHeight is not offered" }
        require(interfaceScalePercent in INTERFACE_SCALE_PERCENTS) { "scale $interfaceScalePercent% is out of range" }
    }
}

val MAX_VIDEO_HEIGHTS: List<Int> = listOf(2160, 1440, 1080, 720, 480)
const val DEFAULT_MAX_VIDEO_HEIGHT = 2160
val INTERFACE_SCALE_PERCENTS: IntRange = 50..200
const val DEFAULT_INTERFACE_SCALE_PERCENT = 100

/** The interface sizes Settings offers (Q59); the store keeps any value in [INTERFACE_SCALE_PERCENTS]. */
val INTERFACE_SCALE_CHOICES: List<Int> = listOf(80, 90, 100, 110, 120)

/** [DeviceSettingsStore] in the app's private `device` preferences, read and written on [io]
 *  (the first read loads the file). */
class PreferencesDeviceSettings(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DeviceSettingsStore {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE) }

    /** Serializes read-transform-write, so two updates can't drop each other's change. */
    private val writeLock = Any()

    override fun settings(): Flow<DeviceSettings> = callbackFlow {
        // The listener only signals; each read follows its signal off the channel, so the last read
        // always follows the last change. Registered before the first signal, so no change is lost;
        // the local holds it strongly for the collection's life (SharedPreferences keeps a weak one).
        // Any key's change signals: a neighbour's re-read is dropped as unchanged.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        send(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .conflate()
        .map { read() }
        .distinctUntilChanged()
        .flowOn(io)

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) {
        withContext(io) {
            synchronized(writeLock) {
                val next = transform(read())
                preferences.edit()
                    .putInt(MAX_VIDEO_HEIGHT, next.maxVideoHeight)
                    .putBoolean(FRAME_RATE_MATCHING, next.frameRateMatching)
                    .putInt(INTERFACE_SCALE, next.interfaceScalePercent)
                    .putBoolean(CLOCK_IN_PLAYER, next.clockInPlayer)
                    .putBoolean(HERO_ON_HOME, next.heroOnHome)
                    .putBoolean(BACKGROUND_AUDIO, next.backgroundAudio)
                    .apply()
            }
        }
    }

    /** A value this build doesn't offer reads as its default. */
    private fun read(): DeviceSettings {
        val defaults = DeviceSettings()
        return DeviceSettings(
            maxVideoHeight = preferences.getInt(MAX_VIDEO_HEIGHT, defaults.maxVideoHeight)
                .takeIf { it in MAX_VIDEO_HEIGHTS } ?: defaults.maxVideoHeight,
            frameRateMatching = preferences.getBoolean(FRAME_RATE_MATCHING, defaults.frameRateMatching),
            interfaceScalePercent = preferences.getInt(INTERFACE_SCALE, defaults.interfaceScalePercent)
                .takeIf { it in INTERFACE_SCALE_PERCENTS } ?: defaults.interfaceScalePercent,
            clockInPlayer = preferences.getBoolean(CLOCK_IN_PLAYER, defaults.clockInPlayer),
            heroOnHome = preferences.getBoolean(HERO_ON_HOME, defaults.heroOnHome),
            backgroundAudio = preferences.getBoolean(BACKGROUND_AUDIO, defaults.backgroundAudio),
        )
    }

    private companion object {
        const val MAX_VIDEO_HEIGHT = "maxVideoHeight"
        const val FRAME_RATE_MATCHING = "frameRateMatching"
        const val INTERFACE_SCALE = "interfaceScalePercent"
        const val CLOCK_IN_PLAYER = "clockInPlayer"
        const val HERO_ON_HOME = "heroOnHome"
        const val BACKGROUND_AUDIO = "backgroundAudio"
    }
}
