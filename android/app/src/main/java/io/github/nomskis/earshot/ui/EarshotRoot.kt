package io.github.nomskis.earshot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.nomskis.earshot.ui.theme.Tones

private enum class Screen { HOME, SETTINGS, TUNER }

/** Picks the screen: an active call always wins, otherwise home, settings or the delay tuner. */
@Composable
fun EarshotRoot(viewModel: MainViewModel, inPictureInPicture: Boolean) {
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
    val radioTest by viewModel.radioTest.collectAsStateWithLifecycle()
    val earbudInfo by viewModel.earbuds.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val interrupted by viewModel.interruptedCall.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val callBack by viewModel.callBack.collectAsStateWithLifecycle()
    val inboxStatus by viewModel.inboxStatus.collectAsStateWithLifecycle()
    val callLog by viewModel.callLog.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val callEnded by viewModel.callEnded.collectAsStateWithLifecycle()
    // Each time the app comes to the front outside a call, nudge a sleeping server awake.
    LifecycleStartEffect(session == null) {
        if (session == null) {
            viewModel.wakeServer()
            // A newer build, and carrying on with one after "Install unknown apps" was allowed.
            viewModel.checkForUpdate()
            viewModel.retryUpdateInstall()
        }
        onStopOrDispose { }
    }
    val update by viewModel.update.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val openConversation by viewModel.openConversation.collectAsStateWithLifecycle()
    val callMinimized by viewModel.callMinimized.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = Tones.page) {
        val current = settings ?: return@Surface Box(Modifier.fillMaxSize())
        val activeSession = session
        // During a call the rest of the app stays usable, with a bar at the top back to the call.
        if (activeSession != null && (!callMinimized || inPictureInPicture)) {
            CallScreen(
                session = activeSession,
                route = route,
                keepScreenOn = current.keepScreenOn,
                pocketGuard = current.pocketGuard,
                inPictureInPicture = inPictureInPicture,
                earbudBoost = earbudBoost,
                turboNote = turboBoost?.text,
                onVoiceVolumeSaved = { v -> viewModel.updateSettings { it.copy(voiceVolume = v) } },
                onFlipSaved = { on -> viewModel.updateSettings { it.copy(flip = on) } },
                onLeaveScreen = viewModel::minimizeCall,
                quickReplies = current.quickReplies,
            )
            return@Surface
        }
        Column(Modifier.fillMaxSize()) {
            if (activeSession != null) {
                val callState by activeSession.state.collectAsStateWithLifecycle()
                OngoingCallBar(calmed(callState), onClick = viewModel::showCall)
            }
            // The bar has the status bar's space; the screens under it don't need it again.
            Box(Modifier.weight(1f).then(if (activeSession != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                when {
                    openConversation != null && contacts.any { it.address == openConversation } -> {
                        val contact = contacts.first { it.address == openConversation }
                        // Read as they come only while it's really on screen, not with the app in the background.
                        LifecycleStartEffect(contact.address) {
                            viewModel.conversationOnScreen(contact.address)
                            onStopOrDispose { viewModel.conversationOnScreen(null) }
                        }
                        ConversationScreen(
                            contact = contact,
                            conversation = conversations[contact.address],
                            onSend = { text -> viewModel.sendMessage(contact.address, text) },
                            onCall = { video -> viewModel.callFromConversation(contact, video) },
                            onBack = { viewModel.openConversation(null) },
                            onDelete = { message -> viewModel.deleteMessage(contact.address, message) },
                            onClear = { viewModel.clearConversation(contact.address) },
                            calls = callLog.filter { it.address == contact.address },
                            onRename = { name -> viewModel.renameContact(contact.address, name) },
                            onBlock = { viewModel.block(contact) },
                            onRemove = { viewModel.removeContact(contact) },
                        )
                    }
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
                        lastCall = callLog.firstOrNull { it.quality != null },
                        blocked = blocked,
                        onUnblock = { person -> viewModel.unblock(person.address) },
                        update = update.takeIf { viewModel.updatesEnabled },
                        onCheckForUpdate = viewModel::checkForUpdateNow,
                        onInstallUpdate = viewModel::installUpdate,
                    )
                    screen == Screen.TUNER -> TunerScreen(
                        settings = current,
                        route = route,
                        runs = delayRuns,
                        sonar = sonar,
                        codec = codec,
                        onCodecPermissionGranted = viewModel::startCodecWatcher,
                        wifiBand = remember(route) { viewModel.wifiBand() },
                        inCall = activeSession != null,
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
                        earbuds = earbudInfo,
                        onDetectEarbuds = viewModel::detectEarbuds,
                        onJoin = viewModel::startCall,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        interrupted = interrupted,
                        onDismissInterrupted = viewModel::dismissInterruptedCall,
                        contacts = contacts,
                        onCallContact = viewModel::callContact,
                        onRemoveContact = viewModel::removeContact,
                        callBack = callBack,
                        onConsumeCallBack = viewModel::consumeCallBack,
                        inboxStatus = inboxStatus,
                        update = update,
                        onUpdate = viewModel::installUpdate,
                        conversations = conversations,
                        onOpenConversation = { contact -> viewModel.openConversation(contact.address) },
                        recentCalls = callLog,
                        onRenameContact = { contact, name -> viewModel.renameContact(contact.address, name) },
                        onBlockContact = viewModel::block,
                        callEnded = callEnded,
                        onCallEndedShown = viewModel::consumeCallEnded,
                        inCall = activeSession != null,
                    )
                }
            }
        }
    }
}
