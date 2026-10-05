package io.github.nomskis.earshot.ui

import android.app.Application
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.OutputDevice
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.messages.TextMessage
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** Home's two tabs, a person's options in their conversation, and how messages group. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatsAndCallsTest {
    @get:Rule
    val compose = createComposeRule()

    private val sam = Contact("Sam", "c2FtLWFkZHJlc3MtMDAwMD", lastCallAtMillis = 1L)

    @Test
    fun theCallsTabHasTheHistory() {
        val missed = CallRecord("Sam", sam.address, CallRecord.Direction.INCOMING, CallRecord.Outcome.MISSED, video = false, atMillis = System.currentTimeMillis() - 60_000)
        compose.setContent {
            EarshotTheme {
                HomeScreen(
                    settings = AppSettings(serverUrl = "https://calls.example.com", displayName = "Me"),
                    route = AudioRoute(OutputDevice(DeviceKind.BLUETOOTH_MUSIC, "Buds"), AudioManager.MODE_NORMAL, null, true),
                    error = null,
                    pendingRoom = null,
                    onConsumePendingRoom = {},
                    onDismissError = {},
                    onUpdateSettings = {},
                    earbuds = EarbudInfo(),
                    onDetectEarbuds = {},
                    onJoin = { _, _ -> },
                    onOpenSettings = {},
                    contacts = listOf(sam),
                    recentCalls = listOf(missed),
                )
            }
        }
        // Chats first: the people, each a tap from a call.
        compose.onNodeWithText("Earshot").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voice call Sam").assertIsDisplayed()
        compose.onNodeWithText("Calls").performClick()
        compose.onNodeWithContentDescription("Missed call").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voice call Sam").assertIsDisplayed()
    }

    @Test
    fun noCallsYetSaysSo() {
        compose.setContent { EarshotTheme { CallHistory(emptyList(), onCallBack = {}) } }
        compose.onNodeWithText("No calls yet").assertIsDisplayed()
    }

    @Test
    fun theirConversationHasRenameAndBlock() {
        var renamed: String? = null
        var blocked = 0
        compose.setContent {
            EarshotTheme {
                ConversationScreen(sam, null, onSend = {}, onCall = {}, onBack = {}, onRename = { renamed = it }, onBlock = { blocked++ }, onRemove = {})
            }
        }
        compose.onNodeWithText("Say hi to Sam").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove").assertIsDisplayed()
        compose.onNodeWithText("Block").performClick()
        compose.onNodeWithText("Block Sam?").assertIsDisplayed()
        compose.onNodeWithText("Block").performClick()
        assertEquals(1, blocked)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals("Sam", renamed)
    }

    @Test
    fun messagesFromOnePersonCloseTogetherReadAsOneRun() {
        fun text(id: String, mine: Boolean, at: Long) = ChatItem.Text(TextMessage(id, "hi", mine, at, TextMessage.Status.RECEIVED))
        val zone = ZoneOffset.UTC
        assertTrue(sameRun(text("a", mine = false, at = 0), text("b", mine = false, at = 60_000), zone))
        // Someone else, a long pause, or a call in between: a new run.
        assertFalse(sameRun(text("a", mine = false, at = 0), text("b", mine = true, at = 60_000), zone))
        assertFalse(sameRun(text("a", mine = true, at = 0), text("b", mine = true, at = 10 * 60_000), zone))
        val call = ChatItem.Call(CallRecord("Sam", sam.address, CallRecord.Direction.OUTGOING, CallRecord.Outcome.ANSWERED, video = false, atMillis = 30_000))
        assertFalse(sameRun(text("a", mine = true, at = 0), call, zone))
    }

    @Test
    fun anAvatarShowsTheWholeFirstLetter() {
        assertEquals("S", initial("sam"))
        assertEquals("É", initial("  élodie"))
        // An emoji is two chars; both stay together.
        assertEquals("😀", initial("😀 Sam"))
        assertEquals("?", initial(" "))
    }
}
