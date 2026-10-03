package io.github.nomskis.earshot

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import io.github.nomskis.earshot.ui.EarshotRoot
import io.github.nomskis.earshot.ui.MainViewModel
import io.github.nomskis.earshot.ui.theme.EarshotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val inPictureInPicture = mutableStateOf(false)

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        inPictureInPicture.value = info.isInPictureInPictureMode
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

        setContent {
            EarshotTheme {
                EarshotRoot(
                    viewModel = viewModel,
                    inPictureInPicture = inPictureInPicture.value,
                    onLeaveCallScreen = ::leaveCallScreen,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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

    private fun leaveCallScreen() {
        if (packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            enterPictureInPictureMode(pipParams())
        } else {
            moveTaskToBack(true)
        }
    }

    private fun updatePictureInPictureParams(inCall: Boolean) {
        runCatching { setPictureInPictureParams(pipParams(autoEnter = inCall)) }
    }

    private fun pipParams(autoEnter: Boolean = true): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter) }
            .build()

    /** earshot://join/<room> */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "earshot" || data.host != "join") return
        val room = data.pathSegments.firstOrNull()?.let(RoomCodes::normalize) ?: return
        viewModel.offerRoom(room)
    }
}
