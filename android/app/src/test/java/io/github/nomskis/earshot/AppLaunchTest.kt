package io.github.nomskis.earshot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Starts the whole app the way a phone does (the real Application, its
 * dependency graph, MainActivity and the view model) and walks to each screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The app's call inbox would otherwise outlive this test and keep following the
     * settings (shared by every test in this JVM), answering other tests' servers.
     */
    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<EarshotApp>().graph.callInbox.close()
    }

    @Test
    fun launchesAndOpensSettingsAndTheTuner() {
        compose.onNodeWithText("Earshot").assertIsDisplayed()
        compose.onNodeWithText("Invite someone").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Open delay tuner").performScrollTo().performClick()
        compose.onNodeWithText("Delay tuner").assertIsDisplayed()
        compose.onNodeWithText("Find my fastest setup (about a minute)").performScrollTo().assertIsDisplayed()
    }
}
