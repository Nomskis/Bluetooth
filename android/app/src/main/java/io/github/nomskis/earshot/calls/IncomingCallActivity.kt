package io.github.nomskis.earshot.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nomskis.earshot.MainActivity
import io.github.nomskis.earshot.appGraph
import io.github.nomskis.earshot.settings.QuickReplies
import io.github.nomskis.earshot.ui.IncomingCallScreen
import io.github.nomskis.earshot.ui.theme.CallTheme

/**
 * The ringing screen: over the lock screen, screen on, Accept or Decline.
 * Also where the notification's Answer button lands, because starting the
 * microphone and camera needs the app in front.
 */
class IncomingCallActivity : ComponentActivity() {
    private val inbox get() = appGraph.callInbox
    private var answeringWithVideo = true

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        val camera = result[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA)
        if (mic) answer(answeringWithVideo && camera) else inbox.decline()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContent {
            CallTheme {
                val ring by inbox.ringing.collectAsStateWithLifecycle()
                val settings by appGraph.settings.settings.collectAsStateWithLifecycle(null)
                val current = ring
                // Cancelled, answered elsewhere, or timed out: nothing left to show.
                LaunchedEffect(current == null) { if (current == null) finish() }
                if (current != null) {
                    IncomingCallScreen(
                        ring = current,
                        onAccept = { startAnswer(withVideo = current.video) },
                        onAcceptVoiceOnly = { startAnswer(withVideo = false) },
                        onDecline = {
                            inbox.decline()
                            finish()
                        },
                        // Decline with a message, like the phone app's "Reply with message".
                        onReply = current.callerAddress?.let { address ->
                            { text: String ->
                                appGraph.messenger.send(address, text)
                                inbox.decline()
                                finish()
                            }
                        },
                        quickReplies = settings?.quickReplies ?: QuickReplies.DEFAULT,
                    )
                }
            }
        }
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) startAnswer(withVideo = inbox.ringing.value?.video ?: false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) startAnswer(withVideo = inbox.ringing.value?.video ?: false)
    }

    private fun startAnswer(withVideo: Boolean) {
        answeringWithVideo = withVideo
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (withVideo) add(Manifest.permission.CAMERA)
        }.filterNot(::granted)
        if (needed.isEmpty()) answer(withVideo) else permissions.launch(needed.toTypedArray())
    }

    private fun answer(withVideo: Boolean) {
        if (!inbox.answer(withVideo)) {
            finish()
            return
        }
        // The call screen shows over the lock screen too (MainActivity does that during calls).
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val EXTRA_ANSWER = "answer"

        fun intent(context: Context, answer: Boolean): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_ANSWER, answer)
    }
}
