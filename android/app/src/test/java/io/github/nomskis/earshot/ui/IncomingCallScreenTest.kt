package io.github.nomskis.earshot.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.nomskis.earshot.calls.IncomingRing
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IncomingCallScreenTest {
    // Robolectric's default screen is smaller than any current phone; the layout must fit it anyway.
    @get:Rule
    val compose = createComposeRule()

    private val ring = IncomingRing("r-Zk3pQ81wLa", "calm-otter-4821", "Salma", "1D8ANuTJStR4AyHh0kwUw6", video = true)

    @Test
    fun showsWhoIsCallingAndOffersAcceptDeclineAndVoiceOnly() {
        val taps = mutableListOf<String>()
        compose.setContent {
            EarshotTheme {
                IncomingCallScreen(ring, onAccept = { taps += "accept" }, onAcceptVoiceOnly = { taps += "voice" }, onDecline = { taps += "decline" })
            }
        }
        compose.onNodeWithText("Salma").assertIsDisplayed()
        compose.onNodeWithText("Earshot video call").assertIsDisplayed()
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithText("Voice only").performClick()
        compose.onNodeWithText("Decline").performClick()
        assertEquals(listOf("accept", "voice", "decline"), taps)
    }

    @Test
    fun aVoiceCallHasNoVoiceOnlyOption() {
        compose.setContent {
            EarshotTheme { IncomingCallScreen(ring.copy(video = false), onAccept = {}, onAcceptVoiceOnly = {}, onDecline = {}) }
        }
        compose.onNodeWithText("Earshot voice call").assertIsDisplayed()
        compose.onNodeWithText("Voice only").assertDoesNotExist()
    }
}
