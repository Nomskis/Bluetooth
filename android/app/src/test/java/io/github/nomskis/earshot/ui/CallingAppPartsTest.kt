package io.github.nomskis.earshot.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.nomskis.earshot.calls.CallRecord
import io.github.nomskis.earshot.calls.IncomingRing
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

/** What every calling app has: recent calls, and replying with a message instead of answering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallingAppPartsTest {
    @get:Rule
    val compose = createComposeRule()

    private val sam = "c2FtLWFkZHJlc3MtMDAwMD"

    @Test
    fun recentCallsShowWhatHappenedAndCallBackTheSameWay() {
        val now = System.currentTimeMillis()
        val calls = listOf(
            CallRecord("Sam", sam, CallRecord.Direction.INCOMING, CallRecord.Outcome.MISSED, video = true, atMillis = now - 60_000),
            CallRecord("Sam", sam, CallRecord.Direction.OUTGOING, CallRecord.Outcome.ANSWERED, video = false, atMillis = now - 120_000, durationSeconds = 754),
            // A call with someone in a browser: nothing to ring back.
            CallRecord("Guest", null, CallRecord.Direction.OUTGOING, CallRecord.Outcome.ANSWERED, video = true, atMillis = now - 180_000, durationSeconds = 30),
        )
        val back = mutableListOf<CallRecord>()
        compose.setContent { EarshotTheme { RecentCallsCard(calls, onCallBack = { back += it }, now = now) } }
        compose.onNodeWithText("Recent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Missed call").assertIsDisplayed()
        compose.onNodeWithText("Missed", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Video call Sam").performClick()
        assertEquals(listOf(calls[0]), back)
    }

    @Test
    fun recentTimesReadLikeThePhoneApp() {
        val zone = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 10, 4, 18, 0).toInstant(zone).toEpochMilli()
        val yesterday = LocalDateTime.of(2026, 10, 3, 9, 0).toInstant(zone).toEpochMilli()
        assertEquals("Yesterday", recentWhen(yesterday, now, zone))
    }

    @Test
    fun aCallCanBeAnsweredWithAMessage() {
        val replies = mutableListOf<String>()
        val ring = IncomingRing("ring-0001", "calm-otter-4821", "Sam", sam, video = true)
        compose.setContent {
            EarshotTheme { IncomingCallScreen(ring, onAccept = {}, onAcceptVoiceOnly = {}, onDecline = {}, onReply = { replies += it }) }
        }
        compose.onNodeWithText("Message").performClick()
        compose.onNodeWithText(QuickReplies.DEFAULT.first()).performClick()
        assertEquals(listOf(QuickReplies.DEFAULT.first()), replies)
    }

    @Test
    fun aContactCanBeRenamed() {
        val salma = io.github.nomskis.earshot.calls.Contact("Salma", sam)
        var renamed: Pair<String, String>? = null
        compose.setContent {
            EarshotTheme {
                ContactsCard(listOf(salma), onCall = { _, _ -> }, onRemove = {}, onRename = { c, name -> renamed = c.address to name })
            }
        }
        compose.onNodeWithContentDescription("More for Salma").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Salma ❤️")
        compose.onNodeWithText("Save").performClick()
        assertEquals(sam to "Salma ❤️", renamed)
    }

    @Test
    fun aContactCanBeBlockedAfterConfirming() {
        val salma = io.github.nomskis.earshot.calls.Contact("Salma", sam)
        val blocked = mutableListOf<String>()
        compose.setContent {
            EarshotTheme { ContactsCard(listOf(salma), onCall = { _, _ -> }, onRemove = {}, onBlock = { blocked += it.address }) }
        }
        compose.onNodeWithContentDescription("More for Salma").performClick()
        compose.onNodeWithText("Block").performClick()
        compose.onNodeWithText("Block Salma?").assertIsDisplayed()
        assertEquals(emptyList<String>(), blocked)
        compose.onNodeWithText("Block").performClick()
        assertEquals(listOf(sam), blocked)
    }

    @Test
    fun ourMessagesSayWhenTheyveBeenSeen() {
        val seen = io.github.nomskis.earshot.messages.TextMessage("m1", "Hi", mine = true, atMillis = 0, status = io.github.nomskis.earshot.messages.TextMessage.Status.READ)
        assertEquals(true, messageMeta(seen, ZoneOffset.UTC).endsWith(" · Seen"))
    }

    @Test
    fun aCallYouTalkedInEndsWithItsLength() {
        val now = 10_000_000L
        val talked = CallRecord("Sam", sam, CallRecord.Direction.OUTGOING, CallRecord.Outcome.ANSWERED, video = false, atMillis = now - 80_000, durationSeconds = 75)
        assertEquals("Call ended · 1:15", callEndedNote(talked, now))
        // Didn't connect: the history says what happened.
        assertEquals(null, callEndedNote(talked.copy(outcome = CallRecord.Outcome.MISSED, durationSeconds = 0), now))
        // Not from just now.
        assertEquals(null, callEndedNote(talked, now + 60 * 60_000))
    }

    @Test
    fun conversationDaysReadLikeAMessagingApp() {
        val today = java.time.LocalDate.of(2026, 10, 4)
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today))
    }

    @Test
    fun noMessageButtonForACallerWhoDidntSayWhoTheyAre() {
        val ring = IncomingRing("ring-0001", "calm-otter-4821", "Someone", null, video = false)
        compose.setContent { EarshotTheme { IncomingCallScreen(ring, onAccept = {}, onAcceptVoiceOnly = {}, onDecline = {}) } }
        compose.onNodeWithText("Message").assertDoesNotExist()
    }
}
