package io.github.nomskis.earshot.audio

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LinkConditionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun activeNetwork(vararg transports: Int, validated: Boolean) {
        val caps = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(caps).addTransportType(it) }
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test
    fun workingWifiKeepsTheCallOffMobileData() {
        activeNetwork(NetworkCapabilities.TRANSPORT_WIFI, validated = true)
        assertTrue(LinkConditions.onWorkingWifi(context))
    }

    @Test
    fun wifiStuckAtALoginPageDoesNot() {
        activeNetwork(NetworkCapabilities.TRANSPORT_WIFI, validated = false)
        assertFalse(LinkConditions.onWorkingWifi(context))
    }

    @Test
    fun mobileDataAsThePhonesNetworkDoesNot() {
        activeNetwork(NetworkCapabilities.TRANSPORT_CELLULAR, validated = true)
        assertFalse(LinkConditions.onWorkingWifi(context))
    }
}
