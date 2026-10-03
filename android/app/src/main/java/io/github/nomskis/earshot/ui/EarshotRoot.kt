package io.github.nomskis.earshot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Picks the screen: an active call always wins, otherwise home or settings. */
@Composable
fun EarshotRoot(viewModel: MainViewModel, inPictureInPicture: Boolean, onLeaveCallScreen: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val pendingRoom by viewModel.pendingRoom.collectAsStateWithLifecycle()
    val serverCheck by viewModel.serverCheck.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = settings ?: return@Surface Box(Modifier.fillMaxSize())
        val activeSession = session
        when {
            activeSession != null -> CallScreen(
                session = activeSession,
                route = route,
                keepScreenOn = current.keepScreenOn,
                inPictureInPicture = inPictureInPicture,
                onVoiceVolumeSaved = { v -> viewModel.updateSettings { it.copy(voiceVolume = v) } },
                onLeaveScreen = onLeaveCallScreen,
            )
            showSettings -> SettingsScreen(
                settings = current,
                serverCheck = serverCheck,
                onUpdate = viewModel::updateSettings,
                onCheckServer = viewModel::checkServer,
                onBack = {
                    viewModel.resetServerCheck()
                    showSettings = false
                },
            )
            else -> HomeScreen(
                settings = current,
                route = route,
                error = error,
                pendingRoom = pendingRoom,
                onConsumePendingRoom = viewModel::consumePendingRoom,
                onDismissError = viewModel::clearError,
                onUpdateSettings = viewModel::updateSettings,
                onJoin = viewModel::startCall,
                onOpenSettings = { showSettings = true },
            )
        }
    }
}
