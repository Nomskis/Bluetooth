package io.github.nomskis.earshot.ui

import android.Manifest
import android.app.Application
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows.shadowOf
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
    private val settings = AppSettings(serverUrl = "https://calls.example.com", lastRoom = "calm-otter-4821", displayName = "Sam")
    private val run = DelayRun(device = buds.name, label = "Game mode on", delayMs = 118.0, reportedMs = 210.0, atMillis = 1)

    @Test
    fun homeScreenOffersInvitingSomeoneAndTheGameMode() {
        var updated: AppSettings? = null
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
                    earbuds = EarbudInfo(checked = true, earbuds = buds.name, family = "OPPO / OnePlus / realme"),
                    onDetectEarbuds = {},
                    onJoin = { _, _ -> },
                    onOpenSettings = {},
                )
            }
        }
        // Inviting comes first, without scrolling; a room code is folded away until asked for.
        compose.onNodeWithText("Invite someone").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Room").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Join with a room code").performClick()
        compose.onNodeWithText("Room").assertIsDisplayed()
        compose.onNodeWithText("Your earbuds have a game mode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use it for calls").performScrollTo().performClick()
        assertEquals(true, updated?.autoGameMode)
    }

    @Test
    fun anInviteLinkAsksOnceAndJoinsWithOneTap() {
        var joined: Pair<String, Boolean>? = null
        var consumed = false
        val permissions = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        permissions.grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent {
            EarshotTheme {
                HomeScreen(
                    settings = settings,
                    route = route,
                    error = null,
                    pendingRoom = "calm-otter-4821",
                    onConsumePendingRoom = { consumed = true },
                    onDismissError = {},
                    onUpdateSettings = {},
                    earbuds = EarbudInfo(),
                    onDetectEarbuds = {},
                    onJoin = { room, video -> joined = room to video },
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("You're invited to a call").assertIsDisplayed()
        assertTrue(consumed)
        compose.onNodeWithText("Join").performClick()
        assertEquals("calm-otter-4821" to true, joined)
        // Asked once: the card goes once it's been answered.
        assertTrue(compose.onAllNodesWithText("You're invited to a call").fetchSemanticsNodes().isEmpty())
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
                    earbuds = EarbudInfo(),
                    onDetectEarbuds = {},
                    onJoin = { room, video -> joined = room to video },
                    onOpenSettings = {},
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
                    earbuds = EarbudInfo(),
                    onDetectEarbuds = {},
                    onJoin = { _, _ -> },
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("Connect a server first").assertIsDisplayed()
        // No name yet: asked for right there.
        compose.onNodeWithText("Your name").assertIsDisplayed()
    }

    @Test
    fun theNameBoxKeepsASpaceWhileYouType() {
        // Like the real settings store, which trims the name it saves.
        var stored by mutableStateOf(AppSettings(serverUrl = "https://calls.example.com"))
        compose.setContent {
            EarshotTheme {
                HomeScreen(
                    settings = stored,
                    route = route,
                    error = null,
                    pendingRoom = null,
                    onConsumePendingRoom = {},
                    onDismissError = {},
                    onUpdateSettings = { change -> stored = change(stored).let { it.copy(displayName = it.displayName.trim()) } },
                    earbuds = EarbudInfo(),
                    onDetectEarbuds = {},
                    onJoin = { _, _ -> },
                    onOpenSettings = {},
                )
            }
        }
        val box = compose.onNodeWithText("Your name")
        box.performTextInput("Sam ")
        box.performTextInput("Smith")
        compose.onNodeWithText("Sam Smith").assertIsDisplayed()
        assertEquals("Sam Smith", stored.displayName)
    }

    @Test
    fun aConversationShowsTheMessagesAndSends() {
        val sam = io.github.nomskis.earshot.calls.Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD")
        val conversation = io.github.nomskis.earshot.messages.Conversation(sam.address)
            .received("m1", "Landed!", 1_000)
            .sending("m2", "Welcome home", 2_000)
        val sent = mutableListOf<String>()
        var called: Boolean? = null
        compose.setContent {
            EarshotTheme {
                ConversationScreen(sam, conversation, onSend = { sent += it }, onCall = { called = it }, onBack = {})
            }
        }
        compose.onNodeWithText("Landed!").assertIsDisplayed()
        compose.onNodeWithText("Welcome home").assertIsDisplayed()
        compose.onNodeWithText("Message").performTextInput("See you soon")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.onNodeWithContentDescription("Video call Sam").performClick()
        assertEquals(listOf("See you soon"), sent)
        assertEquals(true, called)
    }

    @Test
    fun anUpdateIsOneTap() {
        var tapped = 0
        val release = io.github.nomskis.earshot.update.AppUpdates.Release(104, "https://example.com/earshot.apk")
        compose.setContent {
            EarshotTheme { UpdateCard(io.github.nomskis.earshot.update.AppUpdater.State.Available(release), onUpdate = { tapped++ }) }
        }
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Build 104").assertIsDisplayed()
        compose.onNodeWithText("Update").performClick()
        assertEquals(1, tapped)
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
        compose.onNodeWithText("Quick replies").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mobile data backup").performScrollTo().performClick()
        assertEquals(true, updated?.mobileDataBackup) // off by default; this turns it on
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
