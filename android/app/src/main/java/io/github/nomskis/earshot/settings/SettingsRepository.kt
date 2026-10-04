package io.github.nomskis.earshot.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.nomskis.earshot.BuildConfig
import io.github.nomskis.earshot.call.Ids
import io.github.nomskis.earshot.call.LinkMemories
import io.github.nomskis.earshot.call.LinkMemory
import io.github.nomskis.earshot.calls.CallLog
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.calls.Contacts
import io.github.nomskis.earshot.calls.InboxKeys
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
        val flip = booleanPreferencesKey("flip")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val receiveCalls = booleanPreferencesKey("receive_calls")
        val pocketGuard = booleanPreferencesKey("pocket_guard")
        val gameAudioLabel = booleanPreferencesKey("game_audio_label")
        val lowLatencyPlayback = booleanPreferencesKey("low_latency_playback")
        val headStartCue = booleanPreferencesKey("head_start_cue")
        val smartDuck = booleanPreferencesKey("smart_duck")
        val autoGameMode = booleanPreferencesKey("auto_game_mode")
        val bluetoothFriendlyVideo = booleanPreferencesKey("bluetooth_friendly_video")
        val mobileDataOn24GHz = booleanPreferencesKey("mobile_data_on_24ghz")
        val lipSync = booleanPreferencesKey("lip_sync")
        // A new key: the old one was written with the old default (on) by any settings change,
        // so a stored "on" didn't mean anyone chose it.
        val mobileDataBackup = booleanPreferencesKey("mobile_data_backup_opt_in")
        val turboDuringCalls = booleanPreferencesKey("turbo_during_calls")
        val relayRoute = booleanPreferencesKey("relay_route")
        val lessData = booleanPreferencesKey("less_data")
        val backgroundGuideDone = booleanPreferencesKey("background_guide_done")
        val callSetupDone = booleanPreferencesKey("call_setup_done")
        val gameModeHintDone = booleanPreferencesKey("game_mode_hint_done")
        val voiceVolume = floatPreferencesKey("voice_volume")
        val lastRoom = stringPreferencesKey("last_room")
        val quickReplies = stringPreferencesKey("quick_replies")
        val peerId = stringPreferencesKey("peer_id")
        val delayRuns = stringPreferencesKey("delay_runs")
        val inboxKey = stringPreferencesKey("inbox_key")
        val contacts = stringPreferencesKey("contacts")
        val blocked = stringPreferencesKey("blocked")
        val linkMemories = stringPreferencesKey("link_memories")
        val callLog = stringPreferencesKey("call_log")
        val activeCallRoom = stringPreferencesKey("active_call_room")
        val activeCallVideo = booleanPreferencesKey("active_call_video")
        val activeCallAliveAt = longPreferencesKey("active_call_alive_at")
    }

    /** People you've called with, most recent first; see [Contacts]. */
    val contacts: Flow<List<Contact>> = context.dataStore.data.map { Contacts.decode(it[Keys.contacts]) }

    suspend fun saveContact(contact: Contact) {
        context.dataStore.edit { prefs ->
            // Someone you blocked doesn't come back by calling or writing.
            if (Contacts.decode(prefs[Keys.blocked]).any { it.address == contact.address }) return@edit
            prefs[Keys.contacts] = Contacts.encode(Contacts.upsert(Contacts.decode(prefs[Keys.contacts]), contact))
        }
    }

    /** People whose calls don't ring and whose messages are dropped, newest first. */
    val blocked: Flow<List<Contact>> = context.dataStore.data.map { Contacts.decode(it[Keys.blocked]) }

    suspend fun isBlocked(address: String): Boolean = blocked.first().any { it.address == address }

    /** Their calls no longer ring and their messages are dropped; they leave your contacts. */
    suspend fun block(contact: Contact) {
        context.dataStore.edit { prefs ->
            prefs[Keys.blocked] = Contacts.encode(Contacts.block(Contacts.decode(prefs[Keys.blocked]), contact))
            prefs[Keys.contacts] = Contacts.encode(Contacts.remove(Contacts.decode(prefs[Keys.contacts]), contact.address))
        }
    }

    /** Back to normal, and back in your contacts. */
    suspend fun unblock(address: String) {
        context.dataStore.edit { prefs ->
            val blocked = Contacts.decode(prefs[Keys.blocked])
            val contact = blocked.firstOrNull { it.address == address } ?: return@edit
            prefs[Keys.blocked] = Contacts.encode(Contacts.remove(blocked, address))
            prefs[Keys.contacts] = Contacts.encode(Contacts.upsert(Contacts.decode(prefs[Keys.contacts]), contact))
        }
    }

    suspend fun renameContact(address: String, name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.contacts] = Contacts.encode(Contacts.rename(Contacts.decode(prefs[Keys.contacts]), address, name)) }
    }

    /** Every call, newest first; see [CallLog]. */
    val callLog: Flow<List<CallRecord>> = context.dataStore.data.map { CallLog.decode(it[Keys.callLog]) }

    suspend fun addCallRecord(record: CallRecord) {
        context.dataStore.edit { prefs -> prefs[Keys.callLog] = CallLog.encode(CallLog.add(CallLog.decode(prefs[Keys.callLog]), record)) }
    }

    suspend fun clearCallLog() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.callLog) }
    }

    suspend fun removeContact(address: String) {
        context.dataStore.edit { prefs -> prefs[Keys.contacts] = Contacts.encode(Contacts.remove(Contacts.decode(prefs[Keys.contacts]), address)) }
    }

    /** What past calls learned about each contact's route; see [LinkMemory]. */
    suspend fun linkMemories(): List<LinkMemory> = LinkMemories.decode(context.dataStore.data.first()[Keys.linkMemories])

    suspend fun saveLinkMemory(memory: LinkMemory) {
        context.dataStore.edit { prefs ->
            prefs[Keys.linkMemories] = LinkMemories.encode(LinkMemories.upsert(LinkMemories.decode(prefs[Keys.linkMemories]), memory, System.currentTimeMillis()))
        }
    }

    /** This install's secret inbox key (made on first use); see [InboxKeys]. */
    suspend fun inboxKey(): String {
        context.dataStore.data.first()[Keys.inboxKey]?.let { return it }
        var key = ""
        context.dataStore.edit { prefs ->
            key = prefs[Keys.inboxKey] ?: InboxKeys.newKey().also { prefs[Keys.inboxKey] = it }
        }
        return key
    }

    /** The call that was running when the app last died without hanging up, if any. */
    val interruptedCall: Flow<InterruptedCall?> = context.dataStore.data.map { prefs ->
        val room = prefs[Keys.activeCallRoom] ?: return@map null
        InterruptedCall(room, prefs[Keys.activeCallVideo] ?: true, prefs[Keys.activeCallAliveAt] ?: 0L)
    }

    /** Called when a call starts and then every so often while it runs. */
    suspend fun markCallAlive(room: String, withVideo: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[Keys.activeCallRoom] = room
            prefs[Keys.activeCallVideo] = withVideo
            prefs[Keys.activeCallAliveAt] = nowMillis
        }
    }

    /** The call ended properly (or the offer to rejoin was dismissed). */
    suspend fun clearActiveCall() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.activeCallRoom)
            prefs.remove(Keys.activeCallVideo)
            prefs.remove(Keys.activeCallAliveAt)
        }
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
            prefs[Keys.flip] = next.flip
            prefs[Keys.keepScreenOn] = next.keepScreenOn
            prefs[Keys.receiveCalls] = next.receiveCalls
            prefs[Keys.pocketGuard] = next.pocketGuard
            prefs[Keys.gameAudioLabel] = next.gameAudioLabel
            prefs[Keys.lowLatencyPlayback] = next.lowLatencyPlayback
            prefs[Keys.headStartCue] = next.headStartCue
            prefs[Keys.smartDuck] = next.smartDuck
            prefs[Keys.autoGameMode] = next.autoGameMode
            prefs[Keys.bluetoothFriendlyVideo] = next.bluetoothFriendlyVideo
            prefs[Keys.mobileDataOn24GHz] = next.mobileDataOn24GHz
            prefs[Keys.lipSync] = next.lipSync
            prefs[Keys.mobileDataBackup] = next.mobileDataBackup
            prefs[Keys.turboDuringCalls] = next.turboDuringCalls
            prefs[Keys.relayRoute] = next.relayRoute
            prefs[Keys.lessData] = next.lessData
            prefs[Keys.backgroundGuideDone] = next.backgroundGuideDone
            prefs[Keys.callSetupDone] = next.callSetupDone
            prefs[Keys.gameModeHintDone] = next.gameModeHintDone
            prefs[Keys.voiceVolume] = next.voiceVolume
            prefs[Keys.lastRoom] = next.lastRoom
            prefs[Keys.quickReplies] = QuickReplies.encode(next.quickReplies)
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
            serverUrl = this[Keys.serverUrl]?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL,
            displayName = this[Keys.displayName] ?: defaults.displayName,
            audioMode = enumOrDefault(this[Keys.audioMode], defaults.audioMode),
            micSource = enumOrDefault(this[Keys.micSource], defaults.micSource),
            echoCancellation = enumOrDefault(this[Keys.echoCancellation], defaults.echoCancellation),
            noiseSuppression = this[Keys.noiseSuppression] ?: defaults.noiseSuppression,
            autoGainControl = this[Keys.autoGainControl] ?: defaults.autoGainControl,
            videoQuality = enumOrDefault(this[Keys.videoQuality], defaults.videoQuality),
            startWithBackCamera = this[Keys.startWithBackCamera] ?: defaults.startWithBackCamera,
            flip = this[Keys.flip] ?: defaults.flip,
            keepScreenOn = this[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            receiveCalls = this[Keys.receiveCalls] ?: defaults.receiveCalls,
            pocketGuard = this[Keys.pocketGuard] ?: defaults.pocketGuard,
            gameAudioLabel = this[Keys.gameAudioLabel] ?: defaults.gameAudioLabel,
            lowLatencyPlayback = this[Keys.lowLatencyPlayback] ?: defaults.lowLatencyPlayback,
            headStartCue = this[Keys.headStartCue] ?: defaults.headStartCue,
            smartDuck = this[Keys.smartDuck] ?: defaults.smartDuck,
            autoGameMode = this[Keys.autoGameMode] ?: defaults.autoGameMode,
            bluetoothFriendlyVideo = this[Keys.bluetoothFriendlyVideo] ?: defaults.bluetoothFriendlyVideo,
            mobileDataOn24GHz = this[Keys.mobileDataOn24GHz] ?: defaults.mobileDataOn24GHz,
            lipSync = this[Keys.lipSync] ?: defaults.lipSync,
            mobileDataBackup = this[Keys.mobileDataBackup] ?: defaults.mobileDataBackup,
            turboDuringCalls = this[Keys.turboDuringCalls] ?: defaults.turboDuringCalls,
            relayRoute = this[Keys.relayRoute] ?: defaults.relayRoute,
            lessData = this[Keys.lessData] ?: defaults.lessData,
            backgroundGuideDone = this[Keys.backgroundGuideDone] ?: defaults.backgroundGuideDone,
            callSetupDone = this[Keys.callSetupDone] ?: defaults.callSetupDone,
            gameModeHintDone = this[Keys.gameModeHintDone] ?: defaults.gameModeHintDone,
            voiceVolume = this[Keys.voiceVolume] ?: defaults.voiceVolume,
            lastRoom = this[Keys.lastRoom] ?: defaults.lastRoom,
            quickReplies = QuickReplies.decode(this[Keys.quickReplies]),
        )
    }
}

private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
