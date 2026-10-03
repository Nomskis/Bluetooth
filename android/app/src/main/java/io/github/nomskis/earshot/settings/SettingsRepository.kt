package io.github.nomskis.earshot.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.call.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val displayName = stringPreferencesKey("display_name")
        val audioMode = stringPreferencesKey("audio_mode")
        val micSource = stringPreferencesKey("mic_source")
        val echoCancellation = stringPreferencesKey("echo_cancellation")
        val noiseSuppression = booleanPreferencesKey("noise_suppression")
        val autoGainControl = booleanPreferencesKey("auto_gain_control")
        val videoQuality = stringPreferencesKey("video_quality")
        val startWithBackCamera = booleanPreferencesKey("start_with_back_camera")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val gameAudioLabel = booleanPreferencesKey("game_audio_label")
        val lowLatencyPlayback = booleanPreferencesKey("low_latency_playback")
        val headStartCue = booleanPreferencesKey("head_start_cue")
        val smartDuck = booleanPreferencesKey("smart_duck")
        val autoGameMode = booleanPreferencesKey("auto_game_mode")
        val bluetoothFriendlyVideo = booleanPreferencesKey("bluetooth_friendly_video")
        val mobileDataOn24GHz = booleanPreferencesKey("mobile_data_on_24ghz")
        val voiceVolume = floatPreferencesKey("voice_volume")
        val lastRoom = stringPreferencesKey("last_room")
        val peerId = stringPreferencesKey("peer_id")
        val delayRuns = stringPreferencesKey("delay_runs")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    /** Every stored delay measurement, oldest first. */
    val delayRuns: Flow<List<DelayRun>> = context.dataStore.data.map { DelayRuns.decode(it[Keys.delayRuns]) }

    suspend fun addDelayRun(run: DelayRun) {
        context.dataStore.edit { prefs ->
            prefs[Keys.delayRuns] = DelayRuns.encode(DelayRuns.decode(prefs[Keys.delayRuns]) + run)
        }
    }

    suspend fun clearDelayRuns(device: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.delayRuns] = DelayRuns.encode(DelayRuns.decode(prefs[Keys.delayRuns]).filter { it.device != device })
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.serverUrl] = next.serverUrl.trim()
            prefs[Keys.displayName] = next.displayName.trim()
            prefs[Keys.audioMode] = next.audioMode.name
            prefs[Keys.micSource] = next.micSource.name
            prefs[Keys.echoCancellation] = next.echoCancellation.name
            prefs[Keys.noiseSuppression] = next.noiseSuppression
            prefs[Keys.autoGainControl] = next.autoGainControl
            prefs[Keys.videoQuality] = next.videoQuality.name
            prefs[Keys.startWithBackCamera] = next.startWithBackCamera
            prefs[Keys.keepScreenOn] = next.keepScreenOn
            prefs[Keys.gameAudioLabel] = next.gameAudioLabel
            prefs[Keys.lowLatencyPlayback] = next.lowLatencyPlayback
            prefs[Keys.headStartCue] = next.headStartCue
            prefs[Keys.smartDuck] = next.smartDuck
            prefs[Keys.autoGameMode] = next.autoGameMode
            prefs[Keys.bluetoothFriendlyVideo] = next.bluetoothFriendlyVideo
            prefs[Keys.mobileDataOn24GHz] = next.mobileDataOn24GHz
            prefs[Keys.voiceVolume] = next.voiceVolume
            prefs[Keys.lastRoom] = next.lastRoom
        }
    }

    /**
     * A stable id for this installation. Reusing it lets the server resume our
     * place in a room after the app or the network restarts.
     */
    suspend fun peerId(): String {
        context.dataStore.data.first()[Keys.peerId]?.let { return it }
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[Keys.peerId] ?: Ids.random(12).also { prefs[Keys.peerId] = it }
        }
        return id
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            serverUrl = this[Keys.serverUrl] ?: BuildConfig.DEFAULT_SERVER_URL,
            displayName = this[Keys.displayName] ?: defaults.displayName,
            audioMode = enumOrDefault(this[Keys.audioMode], defaults.audioMode),
            micSource = enumOrDefault(this[Keys.micSource], defaults.micSource),
            echoCancellation = enumOrDefault(this[Keys.echoCancellation], defaults.echoCancellation),
            noiseSuppression = this[Keys.noiseSuppression] ?: defaults.noiseSuppression,
            autoGainControl = this[Keys.autoGainControl] ?: defaults.autoGainControl,
            videoQuality = enumOrDefault(this[Keys.videoQuality], defaults.videoQuality),
            startWithBackCamera = this[Keys.startWithBackCamera] ?: defaults.startWithBackCamera,
            keepScreenOn = this[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            gameAudioLabel = this[Keys.gameAudioLabel] ?: defaults.gameAudioLabel,
            lowLatencyPlayback = this[Keys.lowLatencyPlayback] ?: defaults.lowLatencyPlayback,
            headStartCue = this[Keys.headStartCue] ?: defaults.headStartCue,
            smartDuck = this[Keys.smartDuck] ?: defaults.smartDuck,
            autoGameMode = this[Keys.autoGameMode] ?: defaults.autoGameMode,
            bluetoothFriendlyVideo = this[Keys.bluetoothFriendlyVideo] ?: defaults.bluetoothFriendlyVideo,
            mobileDataOn24GHz = this[Keys.mobileDataOn24GHz] ?: defaults.mobileDataOn24GHz,
            voiceVolume = this[Keys.voiceVolume] ?: defaults.voiceVolume,
            lastRoom = this[Keys.lastRoom] ?: defaults.lastRoom,
        )
    }
}

private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
