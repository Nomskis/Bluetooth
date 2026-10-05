package io.github.nomskis.earshot.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
    fun aWeakConnectionOffersToTurnOffVideo() {
        var turnedOff = 0
        var dismissed = 0
        compose.setContent { EarshotTheme { WeakVideoOffer(onTurnOff = { turnedOff++ }, onDismiss = { dismissed++ }) } }
        compose.onNodeWithText("Weak connection").assertIsDisplayed()
        compose.onNodeWithText("Turn off video").performClick()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, turnedOff)
        assertEquals(1, dismissed)
    }

    @Test
    fun theCallScreenCanBeMinimized() {
        var minimized = 0
        compose.setContent { EarshotTheme { TopBar(call, onMinimize = { minimized++ }) } }
        compose.onNodeWithContentDescription("Minimize").performClick()
        assertEquals(1, minimized)
    }

    @Test
    fun aMessageCanBeCopiedOrDeletedAndAChatCleared() {
        val sam = io.github.nomskis.earshot.calls.Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD")
        val chat = io.github.nomskis.earshot.messages.Conversation(sam.address).received("m1", "Landed!", 1_000).sending("m2", "Welcome home", 2_000)
        val deleted = mutableListOf<String>()
        var cleared = 0
        compose.setContent {
            EarshotTheme {
                ConversationScreen(sam, chat, onSend = { _, _ -> }, onCall = {}, onBack = {}, onDelete = { deleted += it.text }, onClear = { cleared++ })
            }
        }
        compose.onNodeWithText("Landed!").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        // Theirs: only from this phone, and only once confirmed.
        compose.onNodeWithText("Delete for everyone").assertDoesNotExist()
        compose.onNodeWithText("Delete for me").performClick()
        assertEquals(listOf("Landed!"), deleted)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Clear chat").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun aMessageCanBeAnsweredAndTheAnswerQuotesIt() {
        val sam = io.github.nomskis.earshot.calls.Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD")
        val chat = io.github.nomskis.earshot.messages.Conversation(sam.address).received("m1", "Landed!", 1_000)
        val sent = mutableListOf<Pair<String, io.github.nomskis.earshot.messages.Quote?>>()
        compose.setContent {
            EarshotTheme { ConversationScreen(sam, chat, onSend = { text, reply -> sent += text to reply }, onCall = {}, onBack = {}) }
        }
        compose.onNodeWithText("Landed!").performTouchInput { longClick() }
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Replying to Sam").assertIsDisplayed()
        compose.onNodeWithText("Message").performTextInput("Welcome home")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("Welcome home" to io.github.nomskis.earshot.messages.Quote("m1", mine = false, text = "Landed!")), sent)
        compose.onNodeWithText("Replying to Sam").assertDoesNotExist()
    }

    @Test
    fun anAnswerShowsWhatItAnswersAndADeletedMessageSaysSo() {
        val sam = io.github.nomskis.earshot.calls.Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD")
        val chat = io.github.nomskis.earshot.messages.Conversation(sam.address)
            .received("m1", "Landed!", 1_000)
            .sending("m2", "Welcome home", 2_000, reply = io.github.nomskis.earshot.messages.Quote("m1", mine = false, text = "Landed!"))
            .received("m3", "Oops", 3_000)
            .withdrawn("m3")
        compose.setContent { EarshotTheme { ConversationScreen(sam, chat, onSend = { _, _ -> }, onCall = {}, onBack = {}) } }
        compose.onNodeWithText("Welcome home").assertIsDisplayed()
        // The quote: whose, and what.
        compose.onAllNodesWithText("Landed!").assertCountEquals(2)
        compose.onNodeWithText("This message was deleted").assertIsDisplayed()
        compose.onNodeWithText("Oops").assertDoesNotExist()
    }

    @Test
    fun oursCanBeDeletedForEveryoneWhileItsRecent() {
        val sam = io.github.nomskis.earshot.calls.Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD")
        val chat = io.github.nomskis.earshot.messages.Conversation(sam.address).sending("m2", "Wrong chat", 10_000)
        val gone = mutableListOf<String>()
        compose.setContent {
            EarshotTheme {
                ConversationScreen(sam, chat, onSend = { _, _ -> }, onCall = {}, onBack = {}, onDeleteForEveryone = { gone += it.id }, now = { 20_000 })
            }
        }
        compose.onNodeWithText("Wrong chat").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete for everyone").performClick()
        assertEquals(listOf("m2"), gone)
    }

    @Test
    fun aPictureShowsWithItsWordsAndOneCanBePicked() {
        val sam = io.github.nomskis.earshot.calls.Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD")
        val chat = io.github.nomskis.earshot.messages.Conversation(sam.address)
            .received("m1", "The view", 1_000, photo = io.github.nomskis.earshot.messages.Photo("p1.jpg", 1600, 1200))
            .received("m2", "", 2_000, photo = io.github.nomskis.earshot.messages.Photo("p2.jpg", 1200, 1600))
        compose.setContent { EarshotTheme { ConversationScreen(sam, chat, onSend = { _, _ -> }, onCall = {}, onBack = {}) } }
        compose.onNodeWithText("The view").assertIsDisplayed()
        // A picture without words has nothing to copy.
        compose.onNodeWithText("The view").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Photo: The view").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send a picture").performClick()
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
    }

    @Test
    fun callsShowInTheConversationAndCallBack() {
        val sam = io.github.nomskis.earshot.calls.Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD")
        val chat = io.github.nomskis.earshot.messages.Conversation(sam.address).received("m1", "Call me?", 1_000).sending("m2", "Now?", 3_000)
        val missed = io.github.nomskis.earshot.calls.CallRecord("Sam", sam.address, io.github.nomskis.earshot.calls.CallRecord.Direction.INCOMING, io.github.nomskis.earshot.calls.CallRecord.Outcome.MISSED, video = false, atMillis = 2_000)
        val talked = io.github.nomskis.earshot.calls.CallRecord("Sam", sam.address, io.github.nomskis.earshot.calls.CallRecord.Direction.OUTGOING, io.github.nomskis.earshot.calls.CallRecord.Outcome.ANSWERED, video = true, atMillis = 4_000, durationSeconds = 754)
        // In time order, the call between the two messages.
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), timeline(chat.messages, listOf(talked, missed)).map { it.atMillis })
        assertEquals("Missed voice call", callEventText(missed))
        assertEquals("Video call · ${talked.summary}", callEventText(talked))
        val calledBack = mutableListOf<Boolean>()
        compose.setContent {
            EarshotTheme { ConversationScreen(sam, chat, onSend = { _, _ -> }, onCall = { calledBack += it }, onBack = {}, calls = listOf(talked, missed)) }
        }
        compose.onNodeWithText("Missed voice call", substring = true).performClick()
        assertEquals(listOf(false), calledBack)
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
