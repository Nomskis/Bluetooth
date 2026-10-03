package io.github.nomskis.earshot.earbuds

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.ParcelUuid
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which driver picks up which earbuds, from what they advertise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DriverRecognitionTest {
    private var next = 0

    private fun device(name: String, vararg uuids: String): BluetoothDevice {
        val adapter = ApplicationProvider.getApplicationContext<Application>().getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.getRemoteDevice("00:11:22:33:44:%02X".format(next++))
        shadowOf(device).setName(name)
        shadowOf(device).setUuids(uuids.map { ParcelUuid.fromString(it) }.toTypedArray())
        return device
    }

    private val spp = "00001101-0000-1000-8000-00805f9b34fb"

    private fun familyOf(device: BluetoothDevice) = EarbudDrivers.find(device)?.family

    @Test
    fun brandServicesWinOverNames() {
        assertEquals("OPPO / OnePlus / realme", familyOf(device("realme Buds Air8 Pro", "0000079a-d102-11e1-9b23-00025b00a5a5", spp)))
        assertEquals("Nothing / CMF", familyOf(device("Nothing Ear (a)", "aeac4a03-dff5-498f-843a-34487cf133eb", spp)))
        assertEquals("Xiaomi / Redmi", familyOf(device("Redmi Buds 6 Pro", "0000fd2d-0000-1000-8000-00805f9b34fb")))
        assertEquals("Soundcore", familyOf(device("Liberty 4 NC", "0cf12d31-fac3-4553-bd80-d6832e70abcd", spp)))
    }

    @Test
    fun serialPortEarbudsAreRecognisedByName() {
        assertEquals("OPPO / OnePlus / realme", familyOf(device("OnePlus Buds 3", spp)))
        assertEquals("Huawei / Honor", familyOf(device("HUAWEI FreeBuds 6i", spp)))
        assertEquals("Soundcore", familyOf(device("soundcore Space A40", spp)))
        assertEquals("EarFun", familyOf(device("EarFun Air Pro 4", spp)))
    }

    @Test
    fun othersAreLeftAlone() {
        // Jabra, Sony and friends have no documented game mode; a serial port alone means nothing.
        assertNull(familyOf(device("Jabra Elite 4 Active", spp)))
        assertNull(familyOf(device("WF-1000XM5", spp)))
        assertNull(familyOf(device("Unnamed")))
    }
}
