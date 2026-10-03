package io.github.nomskis.earshot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nomskis.earshot.appGraph
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.call.CallSession
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.signaling.ServerUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data object Ok : ServerCheck
    data class Failed(val reason: String) : ServerCheck
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.appGraph

    val settings: StateFlow<AppSettings?> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val session: StateFlow<CallSession?> = graph.callManager.session
    val lastError: StateFlow<String?> = graph.callManager.lastError

    val route: StateFlow<AudioRoute> =
        graph.routeMonitor.route.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.routeMonitor.snapshot())

    /** A room handed to us by an earshot://join/<room> link. */
    private val _pendingRoom = MutableStateFlow<String?>(null)
    val pendingRoom: StateFlow<String?> = _pendingRoom.asStateFlow()

    private val _serverCheck = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val serverCheck: StateFlow<ServerCheck> = _serverCheck.asStateFlow()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    fun startCall(room: String, withVideo: Boolean) = graph.callManager.startCall(room, withVideo)

    fun endCall() = graph.callManager.endCall()

    fun clearError() = graph.callManager.clearError()

    fun offerRoom(room: String?) {
        _pendingRoom.value = room
    }

    fun consumePendingRoom() {
        _pendingRoom.value = null
    }

    /** Hits /healthz so the user knows the address is right before a call. */
    fun checkServer(input: String) {
        val base = ServerUrls.normalizeBase(input)
        if (base == null) {
            _serverCheck.value = ServerCheck.Failed("That doesn't look like a web address.")
            return
        }
        _serverCheck.value = ServerCheck.Checking
        viewModelScope.launch {
            _serverCheck.value = withContext(Dispatchers.IO) {
                runCatching {
                    graph.http.newCall(Request.Builder().url("$base/healthz").build()).execute().use { response ->
                        if (response.isSuccessful && response.body.string().contains("ok")) {
                            ServerCheck.Ok
                        } else {
                            ServerCheck.Failed("The server answered with HTTP ${response.code}.")
                        }
                    }
                }.getOrElse { ServerCheck.Failed(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    fun resetServerCheck() {
        _serverCheck.value = ServerCheck.Idle
    }
}
