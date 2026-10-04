package io.github.nomskis.earshot.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.signaling.PeerInfo
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import io.github.nomskis.earshot.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Using the app during a call, and the settings that came with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MidCallAndSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val call = CallState(
        phase = CallPhase.CONNECTED,
        room = "call-0123456789abcdef",
        inviteLink = "https://calls.example.com/r/call-0123456789abcdef",
        audioMode = AudioMode.HIFI,
        remotePeer = PeerInfo(peerId = "p1", name = "Sam", seq = 1),
        connectedAt = SystemClock.elapsedRealtime() - 65_000,
        micMuted = true,
    )

    @Test
    fun theBarSaysWhoAndHowLongAndGoesBack() {
        var back = 0
        compose.setContent { EarshotTheme { OngoingCallBar(call, onClick = { back++ }) } }
        compose.onNodeWithText("Sam · 1:05", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Muted").assertIsDisplayed()
        compose.onNodeWithText("Return").performClick()
        assertEquals(1, back)
    }

    @Test
    fun theCallScreenCanBeMinimized() {
        var minimized = 0
        compose.setContent { EarshotTheme { TopBar(call, onMinimize = { minimized++ }) } }
        compose.onNodeWithContentDescription("Minimize").performClick()
        assertEquals(1, minimized)
    }

    private fun settings(
        update: AppUpdater.State? = null,
        replies: List<String> = QuickReplies.DEFAULT,
        onUpdate: ((AppSettings) -> AppSettings) -> Unit = {},
        onCheck: () -> Unit = {},
    ) {
        val current = AppSettings(serverUrl = "https://calls.example.com", quickReplies = replies)
        compose.setContent {
            EarshotTheme {
                SettingsScreen(
                    settings = current,
                    serverCheck = ServerCheck.Idle,
                    onUpdate = onUpdate,
                    onCheckServer = {},
                    onOpenTuner = {},
                    onBack = {},
                    update = update,
                    onCheckForUpdate = onCheck,
                )
            }
        }
    }

    @Test
    fun settingsCheckForUpdatesAndSayWhenUpToDate() {
        var checks = 0
        settings(update = AppUpdater.State.UpToDate(120), onCheck = { checks++ })
        compose.onNodeWithText("Up to date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check for updates").performScrollTo().performClick()
        assertEquals(1, checks)
        compose.onNodeWithText("build", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun quickRepliesCanBeAddedAndRemoved() {
        var saved: List<String>? = null
        settings(replies = listOf("One sec"), onUpdate = { saved = it(AppSettings()).quickReplies })
        compose.onNodeWithContentDescription("Remove One sec").performScrollTo().performClick()
        assertEquals(emptyList<String>(), saved)
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("At the gym")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("One sec", "At the gym"), saved)
        assertTrue(QuickReplies.DEFAULT.isNotEmpty())
    }
}
