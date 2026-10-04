package io.github.nomskis.earshot.calls

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class RingPolicyTest {
    @Test
    fun ringsLikeThePhoneWould() {
        assertEquals(RingPolicy.Ring(sound = true, vibrate = true), RingPolicy.decide(AudioManager.RINGER_MODE_NORMAL, doNotDisturb = false))
        assertEquals(RingPolicy.Ring(sound = false, vibrate = true), RingPolicy.decide(AudioManager.RINGER_MODE_VIBRATE, doNotDisturb = false))
        assertEquals(RingPolicy.Ring(sound = false, vibrate = false), RingPolicy.decide(AudioManager.RINGER_MODE_SILENT, doNotDisturb = false))
        // Do Not Disturb holds calls back; the notification still shows, silently.
        assertEquals(RingPolicy.Ring(sound = false, vibrate = false), RingPolicy.decide(AudioManager.RINGER_MODE_NORMAL, doNotDisturb = true))
    }
}
