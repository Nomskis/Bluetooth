package io.github.nomskis.earshot.messages

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessageNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aMessageCanBeAnsweredOrMarkedReadFromTheNotification() {
        MessageNotifications.createChannel(context)
        val sam = "c2FtLWFkZHJlc3MtMDAwMD"
        MessageNotifications.show(context, "Sam", sam, Conversation(sam).received("m1", "Landed!", 1_000))
        val shown = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(listOf("Reply", "Mark as read"), shown.actions.map { it.title.toString() })
        MessageNotifications.cancel(context, sam)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }
}
