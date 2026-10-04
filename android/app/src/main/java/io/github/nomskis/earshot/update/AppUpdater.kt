package io.github.nomskis.earshot.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import io.github.nomskis.earshot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Finds a newer build and installs it over this one, the way an app store would: the APK
 * goes into an Android package installer session and Android asks "Update this app?".
 * Same app id, same signing key and a higher version code, so contacts and settings stay.
 * Off when [BuildConfig.UPDATE_REPO] is blank (debug builds and tests).
 */
class AppUpdater(context: Context, private val http: OkHttpClient) {
    sealed interface State {
        data object Idle : State
        /** Looking, because you asked (Settings); a check on start stays quiet. */
        data object Checking : State
        /** You asked, and this is the newest build. */
        data class UpToDate(val build: Int) : State
        /** You asked, and the check didn't get through. */
        data object CheckFailed : State
        data class Available(val release: AppUpdates.Release) : State
        data class Downloading(val release: AppUpdates.Release, val percent: Int) : State
        /** Android needs "Install unknown apps" allowed for Earshot first. */
        data class NeedsPermission(val release: AppUpdates.Release) : State
        data object Installing : State
        data class Failed(val reason: String) : State
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /**
     * Looks for a newer build. On its own (as the app starts) it stays quiet when offline or
     * there isn't one; [asked] from Settings, it says so.
     */
    suspend fun check(asked: Boolean = false) {
        if (!enabled || _state.value is State.Downloading || _state.value is State.Installing) return
        if (asked) _state.value = State.Checking
        val release = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(AppUpdates.releaseUrl(BuildConfig.UPDATE_REPO))
                    .header("Accept", "application/vnd.github+json")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else AppUpdates.parse(response.body.string())
                }
            }.getOrNull()
        }
        _state.value = when {
            release != null && AppUpdates.isNewer(release, BuildConfig.VERSION_CODE) -> State.Available(release)
            !asked -> return
            release != null -> State.UpToDate(BuildConfig.VERSION_CODE)
            else -> State.CheckFailed
        }
    }

    /** Downloads the newer build and hands it to Android's installer. */
    suspend fun install(release: AppUpdates.Release) {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            return
        }
        _state.value = State.Downloading(release, 0)
        val result = withContext(Dispatchers.IO) { runCatching { downloadInto(release) } }
        result.exceptionOrNull()?.let {
            _state.value = State.Failed("Couldn't download the update. Check the connection and try again.")
            return
        }
        _state.value = State.Installing
    }

    /** Opens the screen where "Install unknown apps" is allowed for Earshot. */
    fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    /** Android answered the installer session (see [UpdateReceiver]). */
    internal fun onInstallerStatus(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> State.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> State.Idle // "Cancel" on Android's prompt
            else -> State.Failed(message?.let { "The update didn't install: $it" } ?: "The update didn't install.")
        }
    }

    private fun downloadInto(release: AppUpdates.Release) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body
                    val total = body.contentLength()
                    session.openWrite(AppUpdates.APK_NAME, 0, total).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                done += read
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        _state.value = State.Downloading(release, percent)
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    Intent(appContext, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }
}
