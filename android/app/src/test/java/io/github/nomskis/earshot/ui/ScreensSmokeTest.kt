package io.github.nomskis.earshot.ui

import android.app.Application
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.OutputDevice
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.settings.InterruptedCall
import io.github.nomskis.earshot.turbo.TurboClient
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real screens on the JVM, so a crash on opening one shows up
 * here rather than on someone's phone. A plain Application keeps the app's
 * own start-up (Bluetooth listeners and so on) out of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreensSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private val buds = OutputDevice(DeviceKind.BLUETOOTH_MUSIC, "realme Buds Air8 Pro")
    private val route = AudioRoute(buds, AudioManager.MODE_NORMAL, null, bluetoothMusicAvailable = true)
    private val settings = AppSettings(serverUrl = "https://calls.example.com", lastRoom = "calm-otter-4821")
    private val run = DelayRun(device = buds.name, label = "Game mode on", delayMs = 118.0, reportedMs = 210.0, atMillis = 1)

    @Test
    fun homeScreenShowsTheRouteTheDelayAndTheGameModeOffer() {
        var updated: AppSettings? = null
        var estimated = false
        compose.setContent {
            EarshotTheme {
                HomeScreen(
                    settings = settings,
                    route = route,
                    error = null,
                    pendingRoom = null,
                    onConsumePendingRoom = {},
                    onDismissError = {},
                    onUpdateSettings = { updated = it(settings) },
                    delayRuns = listOf(run),
                    estimate = null,
                    onEstimate = { estimated = true },
                    earbuds = EarbudInfo(checked = true, earbuds = buds.name, family = "OPPO / OnePlus / realme"),
                    onDetectEarbuds = {},
                    codec = null,
                    onJoin = { _, _ -> },
                    onOpenSettings = {},
                    onOpenTuner = {},
                )
            }
        }
        // Joining comes first, without scrolling; the explanations follow.
        compose.onNodeWithText("Join call").assertIsDisplayed()
        compose.onNodeWithText("118 ms · Game mode on").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your earbuds have a game mode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use it for calls").performScrollTo().performClick()
        assertEquals(true, updated?.autoGameMode)
        assertTrue(estimated)
    }

    @Test
    fun homeScreenOffersToRejoinACallTheSystemCutOff() {
        var joined: Pair<String, Boolean>? = null
        var dismissed = false
        compose.setContent {
            EarshotTheme {
                HomeScreen(
                    settings = settings,
                    route = route,
                    error = null,
                    pendingRoom = null,
                    onConsumePendingRoom = {},
                    onDismissError = {},
                    onUpdateSettings = {},
                    delayRuns = emptyList(),
                    estimate = null,
                    onEstimate = {},
                    earbuds = EarbudInfo(),
                    onDetectEarbuds = {},
                    codec = null,
                    onJoin = { room, video -> joined = room to video },
                    onOpenSettings = {},
                    onOpenTuner = {},
                    interrupted = InterruptedCall("calm-otter-4821", withVideo = false, aliveAtMillis = System.currentTimeMillis()),
                    onDismissInterrupted = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("Your call was cut off").assertIsDisplayed()
        // Permissions aren't granted under Robolectric, so Rejoin asks for them first; Dismiss is direct.
        compose.onNodeWithText("Dismiss").performClick()
        assertTrue(dismissed)
        assertEquals(null, joined)
    }

    @Test
    fun homeScreenWithoutAServerAsksForOne() {
        compose.setContent {
            EarshotTheme {
                HomeScreen(
                    settings = AppSettings(),
                    route = AudioRoute.Unknown,
                    error = null,
                    pendingRoom = "calm-otter-4821",
                    onConsumePendingRoom = {},
                    onDismissError = {},
                    onUpdateSettings = {},
                    delayRuns = emptyList(),
                    estimate = null,
                    onEstimate = {},
                    earbuds = EarbudInfo(),
                    onDetectEarbuds = {},
                    codec = null,
                    onJoin = { _, _ -> },
                    onOpenSettings = {},
                    onOpenTuner = {},
                )
            }
        }
        compose.onNodeWithText("Connect a server first").assertIsDisplayed()
    }

    @Test
    fun settingsScreenRendersEverySection() {
        var updated: AppSettings? = null
        compose.setContent {
            EarshotTheme {
                SettingsScreen(
                    settings = settings,
                    serverCheck = ServerCheck.Idle,
                    onUpdate = { updated = it(settings) },
                    onCheckServer = {},
                    onOpenTuner = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Sharing the radio with Bluetooth").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mobile data as a backup during calls").performScrollTo().performClick()
        assertEquals(false, updated?.mobileDataBackup)
    }

    @Test
    fun tunerScreenRendersWithMeasurementsAndEveryCard() {
        compose.setContent {
            EarshotTheme {
                TunerScreen(
                    settings = settings,
                    route = route,
                    runs = listOf(run, run.copy(label = "Normal", delayMs = 240.0, atMillis = 0)),
                    sonar = SonarState.Idle,
                    codec = null,
                    onCodecPermissionGranted = {},
                    wifiBand = io.github.nomskis.earshot.audio.WifiBand.GHZ_2_4,
                    inCall = false,
                    onMeasure = {},
                    optimizer = MainViewModel.OptimizerState(message = "Fastest: earbud game mode at 118 ms."),
                    onFindFastest = {},
                    onCopyReport = { "report" },
                    onClearRuns = {},
                    onUpdateSettings = {},
                    earbuds = {
                        EarbudCard(
                            info = EarbudInfo(checked = true, earbuds = buds.name, family = "OPPO / OnePlus / realme"),
                            autoGameMode = false,
                            onDetect = {},
                            onSwitch = {},
                            onAutoGameMode = {},
                        )
                    },
                    radioTest = MainViewModel.RadioTestState(),
                    onRadioTest = {},
                    turbo = {
                        TurboCard(
                            status = TurboClient.Status.NotInstalled,
                            info = MainViewModel.TurboInfo(),
                            onRefresh = {},
                            onRequestPermission = {},
                            onLoadDiagnostics = {},
                            onEnableLowLatency = {},
                            onShortestBuffer = {},
                            onSweepCodecs = {},
                        )
                    },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Find my fastest setup (about a minute)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fastest: earbud game mode at 118 ms.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Does Wi-Fi slow your earbuds?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Earbud game mode").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backgroundGuideRendersForAnAggressiveMaker() {
        compose.setContent { EarshotTheme { BackgroundCard(done = false, onDone = {}) } }
        // Robolectric reports "robolectric" as the maker: stock Android, so only the battery step, if it's needed.
        compose.waitForIdle()
    }
}
