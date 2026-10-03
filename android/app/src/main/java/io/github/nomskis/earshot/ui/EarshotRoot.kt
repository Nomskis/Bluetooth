package io.github.nomskis.earshot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Screen { HOME, SETTINGS, TUNER }

/** Picks the screen: an active call always wins, otherwise home, settings or the delay tuner. */
@Composable
fun EarshotRoot(viewModel: MainViewModel, inPictureInPicture: Boolean, onLeaveCallScreen: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val pendingRoom by viewModel.pendingRoom.collectAsStateWithLifecycle()
    val serverCheck by viewModel.serverCheck.collectAsStateWithLifecycle()
    val delayRuns by viewModel.delayRuns.collectAsStateWithLifecycle()
    val sonar by viewModel.sonar.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

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
            screen == Screen.SETTINGS -> SettingsScreen(
                settings = current,
                serverCheck = serverCheck,
                onUpdate = viewModel::updateSettings,
                onCheckServer = viewModel::checkServer,
                onOpenTuner = { screen = Screen.TUNER },
                onBack = {
                    viewModel.resetServerCheck()
                    screen = Screen.HOME
                },
            )
            screen == Screen.TUNER -> TunerScreen(
                settings = current,
                route = route,
                runs = delayRuns,
                sonar = sonar,
                wifiBand = remember(route) { viewModel.wifiBand() },
                inCall = false,
                onMeasure = viewModel::measureDelay,
                onClearRuns = viewModel::clearDelayRuns,
                onUpdateSettings = viewModel::updateSettings,
                onBack = { screen = Screen.HOME },
            )
            else -> HomeScreen(
                settings = current,
                route = route,
                error = error,
                pendingRoom = pendingRoom,
                onConsumePendingRoom = viewModel::consumePendingRoom,
                onDismissError = viewModel::clearError,
                onUpdateSettings = viewModel::updateSettings,
                delayRuns = delayRuns,
                onJoin = viewModel::startCall,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenTuner = { screen = Screen.TUNER },
            )
        }
    }
}
