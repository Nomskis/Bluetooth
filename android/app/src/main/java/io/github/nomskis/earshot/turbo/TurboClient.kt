package io.github.nomskis.earshot.turbo

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.nomskis.earshot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Optional "Turbo": reaches Android's privileged Bluetooth and audio controls
 * through Shizuku, which users can start on their own phone over Wireless
 * debugging (Android 11+), no computer or root needed. Everything here is
 * best effort and degrades to "not available".
 */
class TurboClient(context: Context) {
    private val appContext = context.applicationContext

    sealed interface Status {
        data object NotInstalled : Status
        data object NotRunning : Status
        data object NeedsPermission : Status
        data object Connecting : Status
        data object Ready : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.NotInstalled)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var service: ITurboService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(ITurboService.Stub::asInterface)
            _status.value = if (service != null) Status.Ready else Status.Failed("Shizuku couldn't start the helper.")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            refresh()
        }
    }

    private val args = Shizuku.UserServiceArgs(ComponentName(appContext.packageName, TurboService::class.java.name))
        .daemon(false)
        .processNameSuffix("turbo")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) connect() else refresh()
        }
    }

    fun refresh() {
        _status.value = when {
            !installed() -> Status.NotInstalled
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> Status.NotRunning
            Shizuku.isPreV11() -> Status.Failed("Your Shizuku is too old; update it.")
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NeedsPermission
            service != null -> Status.Ready
            else -> Status.Connecting.also { connect() }
        }
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure { refresh() }
    }

    fun connect() {
        if (service != null) return
        _status.value = Status.Connecting
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            Log.w(TAG, "bindUserService failed", it)
            _status.value = Status.Failed(it.message ?: "Couldn't connect to Shizuku.")
        }
    }

    suspend fun diagnostics(): BluetoothOutputDiagnostics? = call { s ->
        TurboDiagnostics.parse(s.dump("bluetooth_manager"), s.dump("media.audio_flinger"))
    }

    suspend fun enableVariableLatency(): Boolean = call { it.setVariableLatency(true) } ?: false

    suspend fun codecStatus(): CodecStatus? = call { TurboDiagnostics.parseCodecStatus(it.codecStatus()) }

    suspend fun setCodec(type: Int, specific1: Long = 0): Boolean = call { it.setCodec(type, specific1) } ?: false

    /** Sets the shortest phone-side Bluetooth buffer the stack allows. Returns the ms set, or null. */
    suspend fun shortestBuffer(codecType: Int): Int? = call { s ->
        if (s.dynamicBufferSupport() == 0) return@call null
        val min = s.minBufferMillis(codecType)
        if (min > 0 && s.setBufferMillis(codecType, min)) min else null
    }

    /** Puts the phone-side Bluetooth buffer back to the stack's default for a codec. */
    suspend fun defaultBuffer(codecType: Int): Int? = call { s ->
        if (s.dynamicBufferSupport() == 0) return@call null
        val ms = s.defaultBufferMillis(codecType)
        if (ms > 0 && s.setBufferMillis(codecType, ms)) ms else null
    }

    /** Waits briefly for the helper to be ready; false if Shizuku isn't set up or running. */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        refresh()
        return withTimeoutOrNull(timeoutMs) {
            status.first { it is Status.Ready || it is Status.Failed || it is Status.NotInstalled || it is Status.NotRunning || it is Status.NeedsPermission }
        } is Status.Ready
    }

    private suspend fun <T> call(block: (ITurboService) -> T): T? = withContext(Dispatchers.IO) {
        val s = service ?: return@withContext null
        runCatching { block(s) }.onFailure { Log.w(TAG, "Turbo call failed", it) }.getOrNull()
    }

    private fun installed(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "EarshotTurbo"
        private const val REQUEST_CODE = 7
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_SITE = "https://shizuku.rikka.app/guide/setup/"
    }
}
