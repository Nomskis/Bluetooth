package io.github.nomskis.earshot.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Newer builds of the app, from the repository's rolling "nightly" GitHub release:
 * CI names it "Earshot for Android (build N)", where N is also the APK's version code,
 * and attaches earshot.apk.
 */
object AppUpdates {
    data class Release(val build: Int, val apkUrl: String)

    private val BUILD = Regex("""\(build (\d+)\)""")

    /** The release a GitHub releases API answer describes, or null if it isn't one of ours. */
    fun parse(json: String): Release? = runCatching {
        val release = Json.parseToJsonElement(json).jsonObject
        val build = BUILD.find(release["name"]?.jsonPrimitive?.contentOrNull ?: return null)?.groupValues?.get(1)?.toInt() ?: return null
        val apk = release["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.string("name") == APK_NAME } ?: return null
        Release(build, apk.string("browser_download_url") ?: return null)
    }.getOrNull()

    /** Only ever forward: a release with a lower number is an older build. */
    fun isNewer(release: Release, installedBuild: Int): Boolean = release.build > installedBuild

    fun releaseUrl(repo: String): String = "https://api.github.com/repos/$repo/releases/tags/nightly"

    const val APK_NAME = "earshot.apk"

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
}
