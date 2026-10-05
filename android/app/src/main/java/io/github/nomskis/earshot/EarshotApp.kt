package io.github.nomskis.earshot

import android.app.Application
import android.content.Context
import io.github.nomskis.earshot.audio.AudioRouteMonitor
import io.github.nomskis.earshot.audio.CodecWatcher
import io.github.nomskis.earshot.call.CallManager
import io.github.nomskis.earshot.calls.CallBackRequest
import io.github.nomskis.earshot.calls.CallInbox
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.earbuds.EarbudControl
import io.github.nomskis.earshot.messages.MessageNotifications
import io.github.nomskis.earshot.messages.MessageStore
import io.github.nomskis.earshot.messages.Photos
import io.github.nomskis.earshot.messages.Profiles
import io.github.nomskis.earshot.messages.Messenger
import io.github.nomskis.earshot.settings.SettingsRepository
import io.github.nomskis.earshot.turbo.TurboBoost
import io.github.nomskis.earshot.turbo.TurboClient
import io.github.nomskis.earshot.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies. Small enough that a DI framework would be overkill. */
class AppGraph(context: Context) {
    val settings = SettingsRepository(context)
    val routeMonitor = AudioRouteMonitor(context)
    val codecWatcher = CodecWatcher(context)
    val turbo by lazy { TurboClient(context) }
    val http: OkHttpClient = OkHttpClient.Builder()
        // Detects dead signaling connections (common when switching networks).
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    val earbuds = EarbudControl(context)
    /** The call screen is in front of the user (not backgrounded, not shrunk to picture-in-picture). */
    val callScreenVisible = MutableStateFlow(false)
    val callManager = CallManager(
        context,
        settings,
        routeMonitor,
        http,
        earbudBoost = EarbudBoost(earbuds),
        turboBoost = TurboBoost { turbo },
    ) { codecWatcher.latest.value }
    /** Incoming calls: waits for them and rings the phone. */
    val callInbox = CallInbox(
        context,
        settings,
        http,
        isBusy = { callManager.busy },
        startCall = { ring, withVideo -> callManager.answerCall(ring, withVideo) },
        ringingOut = { callManager.ringingOut() },
        switchTo = { ring, withVideo -> callManager.switchTo(ring, withVideo) },
        preconnect = { ring -> callManager.preconnect(ring) },
        dropPreconnect = { ringId -> callManager.dropPreconnect(ringId) },
        rekeyPreconnect = { old, new -> callManager.rekeyPreconnect(old, new) },
        inCall = { room -> callManager.inCall(room) },
    )
    /** A missed call's "Call back", waiting for the home screen to pick it up. */
    val callBack = MutableStateFlow<CallBackRequest?>(null)
    /** Newer builds, installed over this one. */
    val updater = AppUpdater(context, http)
    /** Pictures in chats. */
    val photos = Photos(context)
    /** Profile pictures: ours and our contacts'. */
    val profiles = Profiles(context)
    /** Chat with contacts, in a call or not, over the inbox connection. */
    val messenger = Messenger(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        store = MessageStore(context),
        send = callInbox::sendInbox,
        myName = { settings.current().displayName },
        contact = { address -> settings.contacts.first().firstOrNull { it.address == address } },
        saveContact = settings::saveContact,
        isBlocked = settings::isBlocked,
        notify = { name, address, conversation -> MessageNotifications.show(context, name, address, conversation, profiles.file(address)) },
        cancelNotification = { address -> MessageNotifications.cancel(context, address) },
        photos = photos,
        profiles = profiles,
        contacts = { settings.contacts.first().map { it.address } },
    ).also { messenger ->
        callInbox.onChat = messenger::onServerMessage
        callManager.onCallChat = { address, message ->
            // Their own ids: kept apart from messages sent through the server.
            messenger.recordCallChat(address, "call-${message.id}", message.text, message.mine, message.atMillis)
        }
        MessageNotifications.createChannel(context)
        messenger.load()
        // Someone new on the list gets our profile picture.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            settings.contacts.map { list -> list.map { it.address }.toSet() }.distinctUntilChanged().collect { messenger.shareProfile() }
        }
    }
}

class EarshotApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.codecWatcher.start()
        graph.callInbox.follow()
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as EarshotApp).graph
