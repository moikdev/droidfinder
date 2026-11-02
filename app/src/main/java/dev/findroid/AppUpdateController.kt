package dev.findroid

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val preparingInstall: Boolean = false,
    val checked: Boolean = false,
    val update: AppUpdate? = null,
    val downloaded: Long = 0,
    val ready: Boolean = false,
    val error: String? = null,
)

internal fun verifyUpdatePackage(app: Application, file: File, update: AppUpdate) {
    verifyUpdateBytes(file, update)
    val manager = app.packageManager
    val archive = requireNotNull(manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)) {
        "The download is not an installable Android app."
    }
    val installed = manager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    require(archive.packageName == app.packageName) { "This update is for another app." }
    require(archive.versionName == update.versionName) { "The update version does not match its release information." }
    require(archive.longVersionCode == update.versionCode &&
        archive.longVersionCode > installed.longVersionCode) { "This update is not newer than the installed app." }
    require(archive.applicationInfo?.minSdkVersion?.let { it <= android.os.Build.VERSION.SDK_INT } == true) {
        "This update requires a newer Android version."
    }
    val ours = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
    val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
    require(!ours.isNullOrEmpty() && ours == theirs) {
        "The update was signed with a different key and cannot replace this app."
    }
}

/** Shared by windows; downloads and installation state survive Activity recreation. */
internal class AppUpdateController(
    private val app: Application,
    private val source: UpdateSource = GithubUpdateSource(),
    private val verify: (File, AppUpdate) -> Unit = { file, update -> verifyUpdatePackage(app, file, update) },
    private val directory: File = File(app.cacheDir, "updates"),
    preferences: String = "app-updates",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val prefs = app.getSharedPreferences(preferences, 0)
    private val readyFile = File(directory, "ready.apk")
    private val manifestFile = File(directory, UPDATE_MANIFEST)
    private var job: Job? = null
    private val initialized = CompletableDeferred<Unit>()
    var visible by mutableStateOf(false)
        private set
    var automatic by mutableStateOf(prefs.getBoolean("automatic", true))
        private set
    var state by mutableStateOf(AppUpdateState())
        private set

    init {
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    File(directory, "download.part").delete()
                    if (!manifestFile.isFile || manifestFile.length() > 32 * 1024) return@runCatching null
                    AppUpdate.parse(manifestFile.readText(), BuildConfig.UPDATE_REPOSITORY).also {
                        verify(readyFile, it)
                    }
                }.getOrElse {
                    runCatching { readyFile.delete(); manifestFile.delete() }
                    null
                }
            }
            if (cached != null) state = state.copy(update = cached, ready = true)
            initialized.complete(Unit)
            if (visible && !state.ready) check() else onResume()
        }
    }

    fun open() {
        visible = true
        if (!state.ready && state.update == null) check()
    }

    fun dismiss() {
        visible = false
        prefs.edit().putLong("remindAfter", System.currentTimeMillis() + 24 * 60 * 60 * 1000L).apply()
    }

    fun changeAutomatic(value: Boolean) {
        automatic = value
        prefs.edit().putBoolean("automatic", value).apply()
    }

    fun onResume() {
        if (initialized.isCompleted && automatic && !state.ready &&
            System.currentTimeMillis() - prefs.getLong("lastAttempt", 0) >= 24 * 60 * 60 * 1000L)
            check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (manual) visible = true
        if (!initialized.isCompleted || state.checking || state.downloading || state.preparingInstall || job?.isCompleted == false) return
        state = state.copy(checking = true, error = null)
        prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
        job = scope.launch {
            try {
                val update = withContext(Dispatchers.IO) { source.latest(BuildConfig.VERSION_CODE.toLong()) }
                state = state.copy(checking = false, checked = true, update = update,
                    ready = state.ready && state.update == update)
                if (update != null && System.currentTimeMillis() >= prefs.getLong("remindAfter", 0))
                    visible = true
            } catch (cancelled: CancellationException) {
                state = state.copy(checking = false)
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(checking = false, error = if (error is java.io.IOException)
                    "Couldn't reach the update service. Check your connection and try again."
                    else error.message ?: "Couldn't check for updates. Please retry.")
            }
        }
    }

    fun download() {
        val update = state.update ?: return
        if (state.checking || state.downloading || state.preparingInstall || state.ready || job?.isCompleted == false) return
        state = state.copy(downloading = true, downloaded = 0, error = null)
        job = scope.launch {
            val partial = File(directory, "download.part")
            try {
                withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "Couldn't create update storage." }
                    var lastProgress = 0L
                    source.download(update, partial) { bytes ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 150 || bytes == update.bytes) {
                            lastProgress = now
                            scope.launch {
                                if (state.downloading && state.update == update)
                                    state = state.copy(downloaded = bytes)
                            }
                        }
                    }
                    verify(partial, update)
                    check(partial.renameTo(readyFile)) { "Couldn't save the verified update. Please retry." }
                    val manifestPart = File(directory, "manifest.part")
                    manifestPart.writeText(update.json())
                    check(manifestPart.renameTo(manifestFile)) { "Couldn't save update information. Please retry." }
                }
                state = state.copy(downloading = false, ready = true, downloaded = update.bytes)
            } catch (cancelled: CancellationException) {
                state = state.copy(downloading = false, error = "Download cancelled. You can retry anytime.")
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(downloading = false, ready = false,
                    error = if (error is java.io.IOException)
                        "The download couldn't finish. Check your connection and available storage, then retry."
                    else error.message ?: "Couldn't download the update. Please retry.")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { partial.delete() }
            }
        }
    }

    fun cancelDownload() { if (state.downloading) job?.cancel() }

    suspend fun verifiedInstallFile(): File? {
        initialized.await()
        val update = state.update ?: return null
        if (!state.ready || state.preparingInstall) return null
        state = state.copy(preparingInstall = true, error = null)
        return try {
            withContext(Dispatchers.IO) { verify(readyFile, update) }
            readyFile
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state = state.copy(ready = false, error = error.message ?: "Please download the update again.")
            null
        } finally {
            state = state.copy(preparingInstall = false)
        }
    }

    fun installError(message: String) { state = state.copy(error = message) }
}

internal fun appUpdateInstallIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri("Findroid update", uri)
    }
}
