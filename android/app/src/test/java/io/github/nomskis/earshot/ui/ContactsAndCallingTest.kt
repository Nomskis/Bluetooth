package io.github.nomskis.earshot.ui

import android.Manifest
import android.app.Application
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.nomskis.earshot.audio.AudioRoute
import io.github.nomskis.earshot.audio.DeviceKind
import io.github.nomskis.earshot.audio.OutputDevice
import io.github.nomskis.earshot.call.CallPhase
import io.github.nomskis.earshot.call.CallState
import io.github.nomskis.earshot.call.OutgoingCall
import io.github.nomskis.earshot.calls.CallBackRequest
import io.github.nomskis.earshot.calls.Contact
import io.github.nomskis.earshot.calls.InboxClient
import io.github.nomskis.earshot.calls.OutgoingRing.Status
import io.github.nomskis.earshot.settings.AppSettings
import io.github.nomskis.earshot.settings.AudioMode
import io.github.nomskis.earshot.signaling.PeerInfo
import io.github.nomskis.earshot.system.BackgroundHealth
import io.github.nomskis.earshot.system.CallReadiness
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** Calling someone from the home screen, and what the call screen says while it rings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContactsAndCallingTest {
    @get:Rule
    val compose = createComposeRule()

    private val salma = Contact("Salma", "1D8ANuTJStR4AyHh0kwUw6", lastCallAtMillis = 1L)
    private val settings = AppSettings(serverUrl = "https://calls.example.com", displayName = "Sam")
    private val route = AudioRoute(OutputDevice(DeviceKind.BLUETOOTH_MUSIC, "realme Buds Air8 Pro"), AudioManager.MODE_NORMAL, null, true)

    private fun grantCallPermissions() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

    private fun home(
        contacts: List<Contact>,
        onCall: (Contact, Boolean) -> Unit = { _, _ -> },
        onRemove: (Contact) -> Unit = {},
        callBack: CallBackRequest? = null,
        onConsumeCallBack: () -> Unit = {},
        inCall: Boolean = false,
    ) {
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
                    onJoin = { _, _ -> },
                    onOpenSettings = {},
                    contacts = contacts,
                    onCallContact = onCall,
                    onRemoveContact = onRemove,
                    callBack = callBack,
                    onConsumeCallBack = onConsumeCallBack,
                    inCall = inCall,
                )
            }
        }
    }

    @Test
    fun yourContactsComeFirstWithVoiceAndVideoButtons() {
        grantCallPermissions()
        val calls = mutableListOf<Pair<String, Boolean>>()
        home(listOf(salma), onCall = { c, video -> calls += c.name to video })
        compose.onNodeWithText("Salma").assertIsDisplayed()
        compose.onNodeWithContentDescription("Video call Salma").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Voice call Salma").performClick()
        assertEquals(listOf("Salma" to true, "Salma" to false), calls)
    }

    @Test
    fun aContactCanBeRemoved() {
        var removed: Contact? = null
        home(listOf(salma), onRemove = { removed = it })
        // A long press, as in a messaging app; their conversation's menu has it too.
        compose.onNodeWithText("Salma").performTouchInput { longClick() }
        compose.onNodeWithText("Remove").performClick()
        assertEquals(salma, removed)
    }

    @Test
    fun duringACallNoNewInviteStarts() {
        home(listOf(salma), inCall = true)
        assertTrue(compose.onAllNodesWithText("Invite").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun beforeTheFirstCallItSaysHowPeopleGetHere() {
        home(emptyList())
        compose.onNodeWithText("After your first call, they're saved here", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Invite").assertIsDisplayed()
    }

    @Test
    fun callBackFromAMissedCallRingsThemStraightAway() {
        grantCallPermissions()
        var called: Pair<Contact, Boolean>? = null
        var consumed = false
        home(
            listOf(salma),
            onCall = { c, video -> called = c to video },
            callBack = CallBackRequest(salma, video = true, atMillis = System.currentTimeMillis()),
            onConsumeCallBack = { consumed = true },
        )
        compose.waitForIdle()
        assertTrue(consumed)
        assertEquals(salma to true, called)
    }

    @Test
    fun aStaleCallBackIsDropped() {
        grantCallPermissions()
        var called: Pair<Contact, Boolean>? = null
        var consumed = false
        home(
            listOf(salma),
            onCall = { c, video -> called = c to video },
            callBack = CallBackRequest(salma, video = true, atMillis = System.currentTimeMillis() - CallBackRequest.FRESH_MS - 1_000),
            onConsumeCallBack = { consumed = true },
        )
        compose.waitForIdle()
        assertTrue(consumed)
        assertNull(called)
    }

    private val calling = CallState(
        phase = CallPhase.WAITING,
        room = "call-0123456789abcdef",
        inviteLink = "https://calls.example.com/r/call-0123456789abcdef",
        audioMode = AudioMode.HIFI,
        contactName = "Salma",
    )

    private fun text(status: Status, keepsTrying: Boolean = false, state: CallState = calling) =
        callingText(state.copy(outgoing = OutgoingCall("Salma", status, keepsTrying)))

    @Test
    fun theCallScreenSaysHowTheCallIsGoing() {
        // Short: their name is always right next to it.
        assertEquals("Calling…", text(Status.CALLING, state = calling.copy(phase = CallPhase.CONNECTING)))
        assertEquals("Ringing…", text(Status.RINGING))
        assertEquals("Answered. Connecting…", text(Status.ANSWERED))
        assertEquals("Declined", text(Status.DECLINED))
        assertEquals("On another call", text(Status.BUSY))
        assertEquals("No answer", text(Status.NO_ANSWER))
        assertEquals("Can't reach their phone. Still trying…", text(Status.UNREACHABLE, keepsTrying = true))
        assertEquals("Can't reach their phone", text(Status.UNREACHABLE))
        // Not a call to a contact, or they're here: the usual texts.
        assertNull(callingText(calling))
        assertNull(text(Status.RINGING, state = calling.copy(remotePeer = PeerInfo(peerId = "p1", name = "Salma", seq = 1))))
    }

    @Test
    fun ringingShowsWhoAndOffersTheLinkOnlyWhenTheirPhoneCantBeReached() {
        compose.setContent { EarshotTheme { RemotePlaceholder(calling.copy(outgoing = OutgoingCall("Salma", Status.RINGING)), compact = false) } }
        compose.onNodeWithText("Salma").assertIsDisplayed()
        compose.onNodeWithText("Ringing…").assertIsDisplayed()
        compose.onNodeWithText("S").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Send invite link").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun unreachableOffersTheInviteLink() {
        compose.setContent {
            EarshotTheme { RemotePlaceholder(calling.copy(outgoing = OutgoingCall("Salma", Status.UNREACHABLE, keepsTrying = true)), compact = false) }
        }
        compose.onNodeWithText("Send invite link").assertIsDisplayed()
    }

    @Test
    fun readyForCallsListsWhatsLeftAndTheXiaomiSwitchesUntilDone() {
        val steps = CallReadiness.steps(BackgroundHealth.Brand.XIAOMI, sdk = 35, notificationsOn = true, fullScreenOn = false)
        assertEquals(listOf(CallReadiness.StepId.NOTIFICATIONS, CallReadiness.StepId.FULL_SCREEN, CallReadiness.StepId.XIAOMI_LOCK_SCREEN), steps.map { it.id })
        var done = false
        compose.setContent { EarshotTheme { CallsReadyCard(InboxClient.State.LISTENING, setupDone = false, onSetupDone = { done = true }, stepsOverride = steps) } }
        compose.onNodeWithText("Finish setting up calls").assertIsDisplayed()
        compose.onNodeWithText("Allow full-screen calls").assertIsDisplayed()
        compose.onNodeWithText("Show on lock screen and pop up").assertIsDisplayed()
        // Notifications are already on, so they're not listed.
        assertTrue(compose.onAllNodesWithText("Allow notifications").fetchSemanticsNodes().isEmpty())
        // Android can check full-screen calls itself, so "Done" waits for that one.
        assertTrue(compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty())
        assertTrue(!done)
    }

    @Test
    fun readyForCallsOnceEverythingIsSet() {
        val steps = CallReadiness.steps(BackgroundHealth.Brand.XIAOMI, sdk = 35, notificationsOn = true, fullScreenOn = true)
        var done = false
        compose.setContent {
            EarshotTheme {
                var setUp by remember { mutableStateOf(false) }
                CallsReadyCard(InboxClient.State.LISTENING, setupDone = setUp, onSetupDone = { done = true; setUp = true }, stepsOverride = steps)
            }
        }
        compose.onNodeWithText("Done").performClick()
        assertTrue(done)
        // Ready: nothing left to say, so the card goes away.
        assertTrue(compose.onAllNodesWithText("Show on lock screen and pop up").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Ready for calls").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aServerThatCantBeReachedIsSaid() {
        compose.setContent { EarshotTheme { CallsReadyCard(InboxClient.State.WAITING_TO_RETRY, setupDone = true, onSetupDone = {}, stepsOverride = emptyList()) } }
        compose.onNodeWithText("Can't reach your server").assertIsDisplayed()
    }

    @Test
    fun androidBefore14HasNoFullScreenSwitch() {
        val steps = CallReadiness.steps(BackgroundHealth.Brand.OTHER, sdk = 33, notificationsOn = false, fullScreenOn = true)
        assertEquals(listOf(CallReadiness.StepId.NOTIFICATIONS), steps.map { it.id })
        assertEquals(false, steps.single().done)
    }

    @Test
    fun lastCallReadsLikeAPhone() {
        val day = 86_400_000L
        val now = 100 * day + 12 * 3_600_000L
        assertEquals("today", lastCallText(now - 3_600_000L, now, ZoneOffset.UTC))
        assertEquals("yesterday", lastCallText(now - day, now, ZoneOffset.UTC))
        assertEquals("3 days ago", lastCallText(now - 3 * day, now, ZoneOffset.UTC))
        assertTrue(lastCallText(now - 30 * day, now, ZoneOffset.UTC).startsWith("on "))
    }
}
