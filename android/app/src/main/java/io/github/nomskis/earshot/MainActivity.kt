package io.github.nomskis.earshot

import android.app.PictureInPictureParams
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.nomskis.earshot.call.RoomCodes
import io.github.nomskis.earshot.messages.MessageNotifications
import io.github.nomskis.earshot.signaling.ServerUrls
import io.github.nomskis.earshot.ui.EarshotRoot
import io.github.nomskis.earshot.ui.MainViewModel
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val inPictureInPicture = mutableStateOf(false)
    private var started = false

    // The pocket guard blanks the display without stopping the activity, so watch the display too.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) publishVisibility()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        inPictureInPicture.value = info.isInPictureInPictureMode
        // The floating window shows the call, and opening it again lands on the call.
        if (info.isInPictureInPictureMode) viewModel.showCall()
        publishVisibility()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        addOnPictureInPictureModeChangedListener(pipListener)
        handleIntent(intent)

        // While in a call, leaving the app shrinks it to a floating window, so
        // you can keep watching while you pick songs in your music app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.session.collect { updatePictureInPictureParams(inCall = it != null) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.session, viewModel.callMinimized) { session, minimized -> session != null && !minimized }
                    .collect { callScreen ->
                        // Like a phone call: the call screen, and only the call screen, stays in front of the
                        // lock screen. Your contacts and messages (the call minimized) need unlocking first.
                        showOverLockScreen(callScreen)
                        publishVisibility()
                    }
            }
        }

        setContent {
            EarshotTheme {
                EarshotRoot(viewModel = viewModel, inPictureInPicture = inPictureInPicture.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        started = true
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, null)
        publishVisibility()
    }

    override fun onStop() {
        started = false
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        publishVisibility()
        super.onStop()
    }

    private fun showOverLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /** Chat messages become notifications while you can't see the call screen. */
    private fun publishVisibility() {
        val displayOn = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.state?.let { it == Display.STATE_ON } ?: true
        appGraph.callScreenVisible.value = started && displayOn && !inPictureInPicture.value && !viewModel.callMinimized.value
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters picture-in-picture by itself (setAutoEnterEnabled).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && viewModel.session.value != null) {
            enterPictureInPictureMode(pipParams())
        }
    }

    private fun updatePictureInPictureParams(inCall: Boolean) {
        runCatching { setPictureInPictureParams(pipParams(autoEnter = inCall)) }
    }

    private fun pipParams(autoEnter: Boolean = true): PictureInPictureParams {
        // The whole window shrinks into the floating video; telling Android where it
        // starts makes the animation smooth instead of a jump.
        val bounds = Rect().also { window.decorView.getGlobalVisibleRect(it) }
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .apply { if (!bounds.isEmpty) setSourceRectHint(bounds) }
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter) }
            .build()
    }

    /**
     * An invite: https://<server>/r/<room> (the link itself), or earshot://join/<room>,
     * optionally ?server=<https origin>, from a web invite page.
     */
    private fun handleIntent(intent: Intent?) {
        // The ongoing call's notification: back to the call.
        if (intent?.getBooleanExtra(EXTRA_SHOW_CALL, false) == true) {
            viewModel.showCall()
            return
        }
        // A message notification: straight to that conversation (a call carries on in the bar).
        intent?.getStringExtra(MessageNotifications.EXTRA_CONVERSATION)?.let { address ->
            viewModel.minimizeCall()
            viewModel.openConversation(address)
            return
        }
        val data = intent?.data ?: return
        val serverParam = if (data.isHierarchical) data.getQueryParameter("server") else null
        val (room, server) = ServerUrls.invite(data.scheme, data.authority, data.pathSegments, serverParam) ?: return
        viewModel.offerRoom(RoomCodes.normalize(room) ?: return, server = server)
    }

    companion object {
        /** Opens the call screen rather than wherever the app was. */
        const val EXTRA_SHOW_CALL = "show_call"
    }
}
