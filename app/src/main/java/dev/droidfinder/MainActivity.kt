package dev.droidfinder

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    internal val finderWindowId: String
        get() =
            intent.getStringExtra("finderWindowId")?.takeIf {
                it.matches(Regex("[a-zA-Z0-9-]{1,64}"))
            } ?: "main"

    internal val model: FinderModel
        get() =
            (application as FinderApplication).windowModel(
                finderWindowId,
                intent.getStringExtra("finderLocation"),
            )

    internal fun openFinderWindow(path: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("finderWindowId", java.util.UUID.randomUUID().toString())
                .putExtra("finderLocation", path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) (application as FinderApplication).releaseWindow(finderWindowId)
    }

    private var immersive = false

    private fun toggleFullscreen() {
        immersive = !immersive
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            else show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.updateNotificationState()
        }

    private fun enableNotifications() {
        val asked = getSharedPreferences("notification-permission", 0)
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                (!asked.getBoolean("asked", false) ||
                    shouldShowRequestPermissionRationale(
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ))
        ) {
            asked.edit().putBoolean("asked", true).apply()
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
    }

    private var folderRequestPaneId: String? = null
    private var folderReconnectRoot: String? = null

    private fun chooseFolder(root: ConnectedLocation? = null) {
        folderRequestPaneId = model.activePaneModel.paneId
        folderReconnectRoot = root?.uri
        try {
            connectFolder.launch(root?.grant?.let(Uri::parse))
        } catch (error: ActivityNotFoundException) {
            model.message = "Android's folder picker isn't available."
        }
    }

    private val connectFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val pane =
                model.windowState.panes.firstOrNull { it.paneId == folderRequestPaneId }
                    ?: model.activePaneModel
            if (uri != null) {
                pane.activatePane()
                pane.connectLocation(uri, replacing = folderReconnectRoot)
            }
            folderRequestPaneId = null
            folderReconnectRoot = null
        }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folderRequestPane", folderRequestPaneId)
        outState.putString("folderReconnectRoot", folderReconnectRoot)
        super.onSaveInstanceState(outState)
    }

    private fun openProviderApp(authority: String) {
        val provider =
            packageManager.resolveContentProvider(
                authority,
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        if (provider == null) {
            model.message = "The provider app isn't installed or visible to Android."
            return
        }
        try {
            startActivity(
                packageManager.getLaunchIntentForPackage(provider.packageName)
                    ?: Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${provider.packageName}"),
                    ),
            )
        } catch (error: Exception) {
            model.message = "Couldn't open the provider's settings."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderRequestPaneId = savedInstanceState?.getString("folderRequestPane")
        folderReconnectRoot = savedInstanceState?.getString("folderReconnectRoot")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xFFF5F6F8.toInt(), 0xFFF5F6F8.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xFFF5F6F8.toInt(), 0xFFF5F6F8.toInt()),
        )
        setContent {
            DisposableEffect(model.darkTheme) {
                val controller =
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !model.darkTheme
                controller.isAppearanceLightNavigationBars = !model.darkTheme
                onDispose {}
            }
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        model.windowState.panes.toList().forEach {
                            it.flushNavigationState()
                            if (!isChangingConfigurations) it.stopAnalysisForBackground()
                        }
                    }
                    if (event == Lifecycle.Event.ON_RESUME) {
                        model.onResume()
                        model.shareRequested?.let {
                            shareFiles(it)
                            model.shareRequested = null
                        }
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(model.shareRequested) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    model.shareRequested?.let {
                        shareFiles(it)
                        model.shareRequested = null
                    }
            }
            FinderApp(
                model,
                onConnectFolder = { chooseFolder() },
                onReconnectFolder = { chooseFolder(it) },
                onProviderApp = ::openProviderApp,
                onEnableNotifications = ::enableNotifications,
                onNewWindow = ::openFinderWindow,
                onCloseWindow = { finish() },
                onToggleFullscreen = ::toggleFullscreen,
                onGrantAccess = {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    } catch (_: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                },
                onOpen = { entry -> openFile(entry, false) },
                onOpenWith = { entry -> openFile(entry, true) },
                onShare = { entries ->
                    if (entries.any { it.folder })
                        model.activePaneModel.prepareFolderShare(entries) {
                            model.shareRequested = it
                        }
                    else shareFiles(entries)
                },
            )
        }
    }

    private fun shareFiles(entries: List<Entry>) {
        runCatching {
            val uris =
                ArrayList(
                    entries.map {
                        it.documentUri
                            ?: FileProvider.getUriForFile(this, "$packageName.files", it.file)
                    },
                )
            if (uris.isEmpty()) return@runCatching
            val intent =
                Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                    .apply {
                        type = if (entries.size == 1) entries.first().mime else "*/*"
                        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData =
                            android.content.ClipData.newUri(contentResolver, "Files", uris.first())
                                .also { data ->
                                    uris.drop(1).forEach {
                                        data.addItem(android.content.ClipData.Item(it))
                                    }
                                }
                    }
            startActivity(
                Intent.createChooser(intent, "Share files")
                    .putExtra(
                        Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayOf(
                            android.content.ComponentName(this, ReceiveFilesActivity::class.java),
                        ),
                    ),
            )
        }
            .onFailure { model.message = it.message ?: "Couldn't share these files." }
    }

    private fun openFile(entry: Entry, chooser: Boolean) {
        try {
            val uri =
                entry.documentUri
                    ?: FileProvider.getUriForFile(this, "$packageName.files", entry.file)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, entry.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(if (chooser) Intent.createChooser(intent, "Open with") else intent)
        } catch (_: ActivityNotFoundException) {
            model.activePaneModel.quickLookRequested = entry
        } catch (error: Exception) {
            model.message = error.message ?: "Couldn't open this file."
        }
    }
}
