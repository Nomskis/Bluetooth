package io.github.nomskis.earshot.ui

import android.app.Application
import android.media.AudioManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.OutputDevice
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.call.Chat
import io.github.nomskis.earshot.call.ChatMessage
import io.github.nomskis.earshot.call.DelayBreakdown
import io.github.nomskis.earshot.call.LipSync
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.signaling.ClientInfo
import io.github.nomskis.earshot.signaling.PeerInfo
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The call screen's overlay and controls, rendered with a live-looking call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallScreenPartsTest {
    @get:Rule
    val compose = createComposeRule()

    private val route = AudioRoute(OutputDevice(DeviceKind.BLUETOOTH_MUSIC, "realme Buds Air8 Pro"), AudioManager.MODE_NORMAL, null, true)

    private val state = CallState(
        phase = CallPhase.CONNECTED,
        room = "calm-otter-4821",
        inviteLink = "https://calls.example.com/r/calm-otter-4821",
        audioMode = AudioMode.HIFI,
        remotePeer = PeerInfo(peerId = "p1", name = "Sam", seq = 1),
        hasRemoteVideo = true,
        signalingOnline = true,
        remoteSpeaking = true,
        canReplay = true,
        radioNote = "2.4 GHz Wi-Fi: lighter video so your earbuds stay smooth",
        lipSync = LipSync.Plan(videoDelayMs = 140, audioDelayMs = 235.0, source = LipSync.Source.MEASURED),
        delay = DelayBreakdown(senderMs = 30, networkMs = 25, jitterBufferMs = 40, playoutMs = 120, playoutMeasured = true, lossPercent = 0.4),
        earbudMicAvailable = true,
    )

    @Test
    fun topBarShowsWhoIsTalkingTheDelayAndWhatEarshotIsDoing() {
        compose.setContent {
            EarshotTheme { TopBar(state, route, listOf("Earbud game mode on"), Modifier) }
        }
        compose.onNodeWithText("Sam").assertIsDisplayed()
        compose.onNodeWithText("Talking").assertIsDisplayed()
        compose.onNodeWithText("≈ 215 ms from their mouth to your ear").assertIsDisplayed().performClick()
        compose.onNodeWithText("Network 25 ms, 0.4% packets lost (repaired where possible)").assertIsDisplayed()
        compose.onNodeWithText("Earbud game mode on").assertIsDisplayed()
        compose.onNodeWithText("Video held back 140 ms to match the earbuds (measured)").assertIsDisplayed()
    }

    @Test
    fun topBarSaysWhenEchoCancellationCameOnBecauseTheCallIsOutLoud() {
        compose.setContent {
            EarshotTheme { TopBar(state.copy(echoGuard = true), route, emptyList(), Modifier) }
        }
        compose.onNodeWithText("Playing out loud now, so echo cancellation is on.").assertIsDisplayed()
    }

    @Test
    fun controlsOfferTheEarbudMicAndHangUp() {
        var earbudMic = 0
        var hungUp = 0
        compose.setContent {
            EarshotTheme {
                Controls(
                    state = state,
                    onMic = {},
                    onCamera = {},
                    onSwitchCamera = {},
                    onVolume = {},
                    onVolumeDone = {},
                    onReplay = {},
                    onEarbudMic = { earbudMic++ },
                    onHangUp = { hungUp++ },
                    modifier = Modifier,
                )
            }
        }
        compose.onNodeWithContentDescription("Use the earbuds' mic (call quality)").performClick()
        compose.onNodeWithContentDescription("Hang up").performClick()
        assertEquals(1, earbudMic)
        assertEquals(1, hungUp)
        // Sam's client doesn't list chat, so there's no chat button.
        compose.onNodeWithContentDescription("Chat").assertDoesNotExist()
    }

    @Test
    fun chatButtonShowsUnreadWhenTheOtherSideHasChat() {
        var opened = 0
        val withChat = state.copy(remotePeer = PeerInfo("p1", "Sam", ClientInfo("web", "0.1.0", listOf(Chat.CAPABILITY)), seq = 1))
        compose.setContent {
            EarshotTheme {
                Controls(
                    state = withChat,
                    onMic = {},
                    onCamera = {},
                    onSwitchCamera = {},
                    onVolume = {},
                    onVolumeDone = {},
                    onReplay = {},
                    onEarbudMic = {},
                    onHangUp = {},
                    modifier = Modifier,
                    onChat = { opened++ },
                    unreadChat = 2,
                )
            }
        }
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Chat, 2 unread").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun chatPanelShowsTheConversationAndSendsQuickReplies() {
        val sent = mutableListOf<String>()
        val messages = listOf(
            ChatMessage("a", "Can't hear you", mine = false, atMillis = 1, status = ChatMessage.Status.RECEIVED),
            ChatMessage("b", "Moving away from the speakers", mine = true, atMillis = 2, status = ChatMessage.Status.DELIVERED),
        )
        compose.setContent { EarshotTheme { ChatPanel(messages, onSend = { sent += it }, onClose = {}) } }
        compose.onNodeWithText("Moving away from the speakers").assertIsDisplayed()
        compose.onNodeWithText("Delivered").assertIsDisplayed()
        compose.onNodeWithText("One sec").performClick()
        compose.onNodeWithText("Message").performTextInput("  On my way  ")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("One sec", "  On my way  "), sent)
    }
}
