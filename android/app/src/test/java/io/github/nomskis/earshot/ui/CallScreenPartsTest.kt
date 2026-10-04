package io.github.nomskis.earshot.ui

import android.app.Application
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.OutputDevice
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.call.Chat
import io.github.nomskis.earshot.call.ChatMessage
import io.github.nomskis.earshot.call.DelayBreakdown
import io.github.nomskis.earshot.call.LinkQuality
import io.github.nomskis.earshot.call.LipSync
import io.github.nomskis.earshot.call.RemoteMedia
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.signaling.ClientInfo
import io.github.nomskis.earshot.signaling.PeerInfo
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun topBarShowsWhoAndHowLongWithJustTheWordsThatMatter() {
        val connected = state.copy(connectedAt = SystemClock.elapsedRealtime() - 75_000, remoteMedia = RemoteMedia(micMuted = true))
        compose.setContent { EarshotTheme { TopBar(connected) } }
        compose.onNodeWithText("Sam").assertIsDisplayed()
        compose.onNodeWithText("1:15").assertIsDisplayed()
        compose.onNodeWithText("Talking").assertIsDisplayed()
        compose.onNodeWithText("Sam muted").assertIsDisplayed()
        // The details live a tap away, not over the video.
        assertTrue(compose.onAllNodesWithText("2.4 GHz Wi-Fi: lighter video so your earbuds stay smooth").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theTimerReadsLikeAPhoneCall() {
        assertEquals("0:07", callTimer(7_400))
        assertEquals("12:34", callTimer((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", callTimer((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("Connecting…", callStatus(state.copy(phase = CallPhase.NEGOTIATING), 0))
    }

    @Test
    fun callDetailsShowTheDelayAndWhatEarshotIsDoing() {
        compose.setContent {
            EarshotTheme { CallInfo(state.copy(echoGuard = true, videoPausedForVoice = true), route, listOf("Earbud game mode on")) }
        }
        compose.onNodeWithText("≈ 215 ms from their mouth to your ear").assertIsDisplayed()
        compose.onNodeWithText("Network 25 ms, 0.4% packets lost (repaired where possible)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Earbud game mode on").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Video held back 140 ms to match the earbuds (measured)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Playing out loud now, so echo cancellation is on.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Weak connection: your video is paused so your voice gets through. It comes back by itself.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aWeakConnectionDoesntBlameEitherSide() {
        val rough = state.copy(
            delay = state.delay!!.copy(senderMs = 40, lossPercent = 12.0, concealedPercent = 4.2, sendLossPercent = 0.0, packetMs = 40),
        )
        compose.setContent {
            EarshotTheme {
                Column {
                    TopBar(rough)
                    CallInfo(rough, route, emptyList())
                }
            }
        }
        // Both phones used to see "from" the other person: incoming voice is judged by gaps too.
        compose.onNodeWithText("Weak connection").assertIsDisplayed()
        compose.onNodeWithText("From Sam: poor (12% lost, 4% filled in)").assertIsDisplayed()
        compose.onNodeWithText("To Sam: good").assertIsDisplayed()
        compose.onNodeWithText("Their phone ≈ 40 ms (estimate, 40 ms packets for a rough link)").assertIsDisplayed()
    }

    @Test
    fun weakConnectionLabelOnlySaysWhetherItsWeak() {
        val fine = state.delay!!.copy(sendLossPercent = 0.0)
        assertEquals(null, weakConnectionLabel(fine, "Sam"))
        assertEquals("Weak connection", weakConnectionLabel(fine.copy(sendSqueeze = LinkQuality.POOR), "Sam"))
        assertEquals("Weak connection", weakConnectionLabel(fine.copy(lossPercent = 9.0), null))
        assertEquals("Weak connection", weakConnectionLabel(fine.copy(networkMs = 400), "Sam"))
        assertEquals("fair (3% lost)", describeDirection(LinkQuality.FAIR, 3.0, 0.1))
    }

    @Test
    fun theirPausedVideoIsExplainedInsteadOfCameraOff() {
        val paused = state.copy(remoteMedia = RemoteMedia(cameraOff = true, weakConnection = true))
        compose.setContent { EarshotTheme { RemotePlaceholder(paused, compact = false) } }
        compose.onNodeWithText(
            "Sam's video is paused: the connection is too weak for it right now, so the voice gets through. It comes back by itself.",
        ).assertIsDisplayed()
    }

    @Test
    fun heldVoiceBannerOffersTheSpeaker() {
        var played = 0
        compose.setContent { EarshotTheme { OutputHeldBanner(name = "Sam", onPlayOutLoud = { played++ }) } }
        compose.onNodeWithText("Your earbuds disconnected, so Sam's voice is paused. They can still hear you.").assertIsDisplayed()
        compose.onNodeWithText("Play on speaker").performClick()
        assertEquals(1, played)
    }

    @Test
    fun aVoiceCallSaysSoWithTheirInitial() {
        val voice = state.copy(hasRemoteVideo = false, cameraOff = true, remoteMedia = RemoteMedia(cameraOff = true))
        compose.setContent { EarshotTheme { RemotePlaceholder(voice, compact = false) } }
        compose.onNodeWithText("Voice call").assertIsDisplayed()
        compose.onNodeWithText("S").assertIsDisplayed()
    }

    private fun controls(
        state: CallState,
        onCamera: () -> Unit = {},
        onSpeaker: () -> Unit = {},
        onMore: () -> Unit = {},
        onHangUp: () -> Unit = {},
        unreadChat: Int = 0,
    ) = compose.setContent {
        EarshotTheme {
            Controls(
                state = state,
                onMic = {},
                onCamera = onCamera,
                onSwitchCamera = {},
                onSpeaker = onSpeaker,
                onMore = onMore,
                onHangUp = onHangUp,
                unreadChat = unreadChat,
            )
        }
    }

    @Test
    fun onVideoTheRowHasTheCameraSwitchAndHangUp() {
        var hungUp = 0
        controls(state, onHangUp = { hungUp++ })
        compose.onNodeWithContentDescription("Switch camera").assertIsDisplayed()
        compose.onNodeWithContentDescription("Turn camera off").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hang up").performClick()
        assertEquals(1, hungUp)
    }

    @Test
    fun aVoiceCallAtYourEarOffersTheSpeakerAndTheCamera() {
        var speaker = 0
        var camera = 0
        controls(state.copy(cameraOff = true, speakerOn = false), onCamera = { camera++ }, onSpeaker = { speaker++ })
        compose.onNodeWithContentDescription("Speaker on").performClick()
        // One tap turns the voice call into a video call.
        compose.onNodeWithContentDescription("Turn camera on").performClick()
        assertEquals(1, speaker)
        assertEquals(1, camera)
    }

    @Test
    fun unreadChatShowsOnMore() {
        var opened = 0
        controls(state, onMore = { opened++ }, unreadChat = 2)
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithContentDescription("More, 2 unread messages").performClick()
        assertEquals(1, opened)
    }

    private fun more(state: CallState, onChat: () -> Unit = {}, onFlip: () -> Unit = {}, onEarbudMic: () -> Unit = {}) = compose.setContent {
        EarshotTheme {
            MoreMenu(
                state = state,
                unreadChat = 2,
                onChat = onChat,
                onSpeaker = {},
                onSwitchCamera = {},
                onFlip = onFlip,
                onVolume = {},
                onVolumeDone = {},
                onReplay = {},
                onEarbudMic = onEarbudMic,
                onInfo = {},
            )
        }
    }

    @Test
    fun moreHasTheExtras() {
        var flips = 0
        var earbudMic = 0
        more(state.copy(flipped = true), onFlip = { flips++ }, onEarbudMic = { earbudMic++ })
        compose.onNodeWithText("Stop mirroring my video").performClick()
        compose.onNodeWithText("Use the earbuds' mic (call quality)").performClick()
        compose.onNodeWithText("Replay the last 8 seconds").assertIsDisplayed()
        compose.onNodeWithText("Call details").assertIsDisplayed()
        assertEquals(1, flips)
        assertEquals(1, earbudMic)
        // Sam's client doesn't list chat, so there's no chat.
        assertTrue(compose.onAllNodesWithText("Chat", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun moreOffersChatWhenTheOtherSideHasIt() {
        var opened = 0
        val withChat = state.copy(remotePeer = PeerInfo("p1", "Sam", ClientInfo("web", "0.1.0", listOf(Chat.CAPABILITY)), seq = 1))
        more(withChat, onChat = { opened++ })
        compose.onNodeWithText("Chat (2 new)").performClick()
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
