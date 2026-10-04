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
import androidx.lifecycle.compose.LifecycleStartEffect
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
    val codec by viewModel.codec.collectAsStateWithLifecycle()
    val turboInfo by viewModel.turbo.collectAsStateWithLifecycle()
    val earbudBoost by viewModel.earbudBoost.collectAsStateWithLifecycle()
    val turboBoost by viewModel.turboBoost.collectAsStateWithLifecycle()
    val optimizer by viewModel.optimizer.collectAsStateWithLifecycle()
    val estimate by viewModel.estimate.collectAsStateWithLifecycle()
    val radioTest by viewModel.radioTest.collectAsStateWithLifecycle()
    val earbudInfo by viewModel.earbuds.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val interrupted by viewModel.interruptedCall.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val callBack by viewModel.callBack.collectAsStateWithLifecycle()
    val inboxStatus by viewModel.inboxStatus.collectAsStateWithLifecycle()
    // Each time the app comes to the front outside a call, nudge a sleeping server awake.
    LifecycleStartEffect(session == null) {
        if (session == null) viewModel.wakeServer()
        onStopOrDispose { }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = settings ?: return@Surface Box(Modifier.fillMaxSize())
        val activeSession = session
        when {
            activeSession != null -> CallScreen(
                session = activeSession,
                route = route,
                keepScreenOn = current.keepScreenOn,
                pocketGuard = current.pocketGuard,
                inPictureInPicture = inPictureInPicture,
                earbudBoost = earbudBoost,
                turboNote = turboBoost?.text,
                onVoiceVolumeSaved = { v -> viewModel.updateSettings { it.copy(voiceVolume = v) } },
                onFlipSaved = { on -> viewModel.updateSettings { it.copy(flip = on) } },
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
                codec = codec,
                onCodecPermissionGranted = viewModel::startCodecWatcher,
                wifiBand = remember(route) { viewModel.wifiBand() },
                inCall = false,
                onMeasure = viewModel::measureDelay,
                optimizer = optimizer,
                onFindFastest = viewModel::findFastestSetup,
                onCopyReport = { viewModel.report(route) },
                onClearRuns = viewModel::clearDelayRuns,
                onUpdateSettings = viewModel::updateSettings,
                earbuds = {
                    val earbuds by viewModel.earbuds.collectAsStateWithLifecycle()
                    EarbudCard(
                        info = earbuds,
                        autoGameMode = current.autoGameMode,
                        onDetect = viewModel::detectEarbuds,
                        onSwitch = viewModel::setEarbudGameMode,
                        onAutoGameMode = { v -> viewModel.updateSettings { it.copy(autoGameMode = v) } },
                    )
                },
                radioTest = radioTest,
                onRadioTest = viewModel::runRadioTest,
                turbo = {
                    val turboStatus by viewModel.turboStatus.collectAsStateWithLifecycle()
                    TurboCard(
                        status = turboStatus,
                        info = turboInfo,
                        onRefresh = viewModel::refreshTurbo,
                        onRequestPermission = viewModel::requestTurboPermission,
                        onLoadDiagnostics = viewModel::loadTurboDiagnostics,
                        onEnableLowLatency = viewModel::turboEnableLowLatency,
                        onShortestBuffer = viewModel::turboShortestBuffer,
                        onSweepCodecs = viewModel::turboSweepCodecs,
                    )
                },
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
                estimate = estimate,
                onEstimate = { viewModel.estimateDelay(route) },
                earbuds = earbudInfo,
                onDetectEarbuds = viewModel::detectEarbuds,
                codec = codec,
                onJoin = viewModel::startCall,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenTuner = { screen = Screen.TUNER },
                interrupted = interrupted,
                onDismissInterrupted = viewModel::dismissInterruptedCall,
                contacts = contacts,
                onCallContact = viewModel::callContact,
                onRemoveContact = viewModel::removeContact,
                callBack = callBack,
                onConsumeCallBack = viewModel::consumeCallBack,
                inboxStatus = inboxStatus,
            )
        }
    }
}
