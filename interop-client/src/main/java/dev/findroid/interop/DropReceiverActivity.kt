package dev.findroid.interop

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.view.DragEvent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Separate-UID receiver for real Android drag/URI-grant acceptance checks. */
class DropReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val probe = intent.getStringExtra("probeUri")?.let(Uri::parse)
        val protectedUri = intent.getStringExtra("protectedUri")?.let(Uri::parse)
        val before = JSONObject().put("receiverUid", Process.myUid())
            .put("readDeniedBeforeDrop", probe == null || !readable(probe))
            .put("unselectedReadDeniedBeforeDrop", protectedUri == null || !readable(protectedUri))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(245, 248, 255))
        }
        content.addView(TextView(this).apply {
            text = "Another Android app"
            textSize = 23f
            setTextColor(Color.rgb(25, 38, 60))
        })
        val zone = TextView(this).apply {
            text = "Drop files here\n\nThe receiver copies the selected files."
            textSize = 19f
            gravity = Gravity.CENTER
            contentDescription = "Drop files here"
            setPadding(20, 24, 20, 24)
            setBackgroundColor(Color.WHITE)
        }
        content.addView(zone, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 24 })
        setContentView(content)
        zone.setOnDragListener { view, event ->
            if (event.action != DragEvent.ACTION_DRAG_LOCATION)
                File(filesDir, "drop-trace.json").writeText(before.put("lastEvent", event.action)
                    .put("x", event.x).put("y", event.y).toString(2))
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    before.put("dragStarted", true)
                    before.put("sourceLocalStateHidden", event.localState == null)
                    before.put("mimeTypes", JSONArray((0 until event.clipDescription.mimeTypeCount).map {
                        event.clipDescription.getMimeType(it)
                    }))
                    view.setBackgroundColor(Color.rgb(227, 239, 255))
                    true
                }
                DragEvent.ACTION_DRAG_ENTERED -> { zone.text = "Release to copy files"; true }
                DragEvent.ACTION_DRAG_EXITED -> { zone.text = "Drop files here"; true }
                DragEvent.ACTION_DROP -> {
                    val permissions = requestDragAndDropPermissions(event)
                    val clip = event.clipData
                    zone.text = "Reading dropped files…"
                    Thread {
                        val report = JSONObject(before.toString())
                        try {
                            check(permissions != null) { "Android did not grant drop permissions" }
                            check(clip != null && clip.itemCount > 0) { "No files in the drop" }
                            val items = JSONArray()
                            for (i in 0 until clip.itemCount) {
                                val uri = requireNotNull(clip.getItemAt(i).uri)
                                check(uri.scheme == "content") { "Expected a content URI" }
                                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file-$i"
                                val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { it.readBytes() }
                                File(filesDir, "drop-$i.bin").writeBytes(bytes)
                                items.put(JSONObject().put("name", name).put("uri", uri.toString())
                                    .put("bytes", bytes.size).put("type", contentResolver.getType(uri))
                                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                                        .joinToString("") { "%02x".format(it) })
                                    .put("writeDenied", checkUriPermission(uri, Process.myPid(), Process.myUid(),
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED))
                            }
                            report.put("files", items)
                            report.put("permissionAcquired", true)
                            report.put("unselectedReadDeniedDuringDrop", protectedUri == null || !readable(protectedUri))
                            permissions.release()
                            report.put("readDeniedAfterRelease", probe == null || !readable(probe))
                            report.put("success", true)
                            runOnUiThread { zone.text = "Received ${items.length()} file${if (items.length() == 1) "" else "s"}\n\n" +
                                (0 until items.length()).joinToString("\n") { items.getJSONObject(it).getString("name") } }
                        } catch (error: Exception) {
                            report.put("success", false).put("error", error.toString())
                            runOnUiThread { zone.text = "Drop failed: ${error.message}" }
                        } finally {
                            permissions?.release()
                            File(filesDir, "drop-report.json").writeText(report.toString(2))
                        }
                    }.start()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> { view.setBackgroundColor(Color.WHITE); true }
                else -> true
            }
        }
    }

    private fun readable(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { it.read() } != null
    } catch (_: Exception) { false }
}
