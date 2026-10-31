package dev.droidfinder

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect

class MainActivity : ComponentActivity() {
    internal val model: FinderModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0xFFF5F6F8.toInt(), 0xFFF5F6F8.toInt()), navigationBarStyle = SystemBarStyle.light(0xFFF5F6F8.toInt(), 0xFFF5F6F8.toInt()))
        setContent {
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onResume() }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            FinderApp(model, onGrantAccess = {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }, onOpen = { entry -> openFile(entry, false) }, onShare = { entries ->
                runCatching {
                    val uris = ArrayList(entries.filter { !it.folder }.map { FileProvider.getUriForFile(this, "$packageName.files", it.file) })
                    if (uris.isEmpty()) { model.message = "Compress folders to a ZIP before sharing."; return@runCatching }
                    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                        type = if (entries.size == 1) entries.first().mime else "*/*"
                        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newUri(contentResolver, "Files", uris.first()).also { data -> uris.drop(1).forEach { data.addItem(android.content.ClipData.Item(it)) } }
                    }
                    startActivity(Intent.createChooser(intent, "Share files"))
                }.onFailure { model.message = it.message ?: "Couldn't share these files." }
            })
        }
    }

    private fun openFile(entry: Entry, chooser: Boolean) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", entry.file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(if (chooser) Intent.createChooser(intent, "Open with") else intent)
        } catch (_: ActivityNotFoundException) { model.message = "No app can open this file type. You can still use Quick Look or share it." }
        catch (error: Exception) { model.message = error.message ?: "Couldn't open this file." }
    }
}
