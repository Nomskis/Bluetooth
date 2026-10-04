package io.github.nomskis.earshot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    // The shape of GitHub's answer for the "nightly" release CI publishes, trimmed.
    private val nightly = """
        {"tag_name":"nightly","name":"Earshot for Android (build 103)","assets":[
          {"name":"earshot-debug.apk","browser_download_url":"https://github.com/Nomskis/Bluetooth/releases/download/nightly/earshot-debug.apk"},
          {"name":"earshot.apk","browser_download_url":"https://github.com/Nomskis/Bluetooth/releases/download/nightly/earshot.apk"}
        ]}
    """.trimIndent()

    @Test
    fun readsTheBuildNumberAndTheInstallableApk() {
        val release = AppUpdates.parse(nightly)!!
        assertEquals(103, release.build)
        // The optimized build, never the debug one.
        assertEquals("https://github.com/Nomskis/Bluetooth/releases/download/nightly/earshot.apk", release.apkUrl)
    }

    @Test
    fun onlyANewerBuildIsAnUpdate() {
        val release = AppUpdates.parse(nightly)!!
        assertTrue(AppUpdates.isNewer(release, installedBuild = 96))
        assertFalse(AppUpdates.isNewer(release, installedBuild = 103))
        assertFalse(AppUpdates.isNewer(release, installedBuild = 110))
    }

    @Test
    fun anythingElseIsNoUpdate() {
        assertNull(AppUpdates.parse("""{"name":"Something else","assets":[]}"""))
        assertNull(AppUpdates.parse("""{"name":"Earshot for Android (build 7)","assets":[]}"""))
        assertNull(AppUpdates.parse("not json"))
    }
}
