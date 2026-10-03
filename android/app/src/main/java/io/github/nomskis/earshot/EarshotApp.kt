package io.github.nomskis.earshot

import android.app.Application
import android.content.Context
import io.github.nomskis.earshot.audio.AudioRouteMonitor
import io.github.nomskis.earshot.audio.CodecWatcher
import io.github.nomskis.earshot.call.CallManager
import io.github.nomskis.earshot.earbuds.EarbudBoost
import io.github.nomskis.earshot.earbuds.EarbudControl
import io.github.nomskis.earshot.settings.SettingsRepository
import io.github.nomskis.earshot.turbo.TurboBoost
import io.github.nomskis.earshot.turbo.TurboClient
import kotlinx.coroutines.flow.MutableStateFlow
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
}

class EarshotApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.codecWatcher.start()
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as EarshotApp).graph
