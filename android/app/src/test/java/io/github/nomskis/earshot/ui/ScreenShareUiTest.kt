package io.github.nomskis.earshot.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.signaling.PeerInfo
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sharing your screen from a call: offered when it can work, impossible to forget while it runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreenShareUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val call = CallState(
        phase = CallPhase.CONNECTED,
        room = "calm-otter-4821",
        inviteLink = "https://calls.example.com/r/calm-otter-4821",
        audioMode = AudioMode.HIFI,
        remotePeer = PeerInfo(peerId = "p1", name = "Sam", seq = 1),
        connectedAt = SystemClock.elapsedRealtime() - 65_000,
    )

    private fun more(state: CallState, onShare: () -> Unit) = compose.setContent {
        EarshotTheme {
            MoreMenu(
                state = state,
                unreadChat = 0,
                onChat = {},
                onSpeaker = {},
                onSwitchCamera = {},
                onFlip = {},
                onVolume = {},
                onVolumeDone = {},
                onReplay = {},
                onEarbudMic = {},
                onInfo = {},
                onShareScreen = onShare,
            )
        }
    }

    @Test
    fun shareScreenIsOfferedWhenTheServerAndTheirAppCanDoIt() {
        var taps = 0
        more(call.copy(canShareScreen = true)) { taps++ }
        compose.onNodeWithText("Share screen").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun notOfferedWhenItCantWorkOrTheyreSharing() {
        more(call.copy(canShareScreen = false)) {}
        compose.onNodeWithText("Share screen").assertDoesNotExist()
    }

    @Test
    fun notOfferedWhileTheyShareTheirs() {
        more(call.copy(canShareScreen = true, theirScreen = true)) {}
        compose.onNodeWithText("Share screen").assertDoesNotExist()
    }

    @Test
    fun whileSharingTheMenuStopsIt() {
        var taps = 0
        more(call.copy(canShareScreen = true, sharingScreen = true)) { taps++ }
        compose.onNodeWithText("Stop sharing").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun theBannerSaysSoAndStops() {
        var stops = 0
        compose.setContent { EarshotTheme { SharingBanner(live = true, onStop = { stops++ }) } }
        compose.onNodeWithText("Sharing your screen").assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun theBannerSaysWhenItsStillStarting() {
        compose.setContent { EarshotTheme { SharingBanner(live = false, onStop = {}) } }
        compose.onNodeWithText("Starting to share…").assertIsDisplayed()
    }

    @Test
    fun theCallBarRemindsYouOutsideTheApp() {
        compose.setContent { EarshotTheme { OngoingCallBar(call.copy(sharingScreen = true), onClick = {}) } }
        compose.onNodeWithText("Sam · Sharing your screen").assertIsDisplayed()
    }
}
