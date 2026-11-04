package dev.findroid.interop

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.*
import java.io.File

class InteropActivity : Activity() {
    private lateinit var status: TextView
    private val prefs
        get() = getSharedPreferences("qa", 0)

    private val initial
        get() = DocumentsContract.buildDocumentUri("dev.findroid.documents", "workspace")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixtures()
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 50, 30, 24)
            }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        status =
            TextView(this).apply {
                textSize = 17f
                text =
                    prefs.getString(
                        "status",
                        "Separate-UID integration probe. Synthetic files only.",
                    )
                setPadding(0, 16, 0, 16)
            }
        content.addView(
            TextView(this).apply {
                text = "Findroid integration QA"
                textSize = 24f
            },
        )
        content.addView(status)
        fun button(title: String, action: () -> Unit) {
            content.addView(
                Button(this).apply {
                    text = title
                    setOnClickListener {
                        runCatching(action).onFailure {
                            report("${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                },
            )
        }
        button("Check ungranted Workspace access") {
            try {
                (contentResolver.query(initial, null, null, null, null)
                        ?: error("Provider is not visible"))
                    .close()
                report("Unexpected: root access succeeded")
            } catch (_: SecurityException) {
                report("Access correctly denied: no Workspace grant")
            }
        }
        button("Open a Workspace document") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                },
                10,
            )
        }
        button("Create a Workspace document") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/plain"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "From another app.txt")
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                },
                11,
            )
        }
        button("Pick a Workspace folder") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial),
                12,
            )
        }
        button("Read the last document") {
            val uri = Uri.parse(prefs.getString("document", null) ?: error("Pick a document first"))
            report(
                contentResolver
                    .openInputStream(uri)!!
                    .bufferedReader()
                    .use { it.readText() }
                    .take(400),
            )
        }
        button("Check last grant") {
            val uri = Uri.parse(prefs.getString("document", null) ?: error("Pick a document first"))
            val grant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            report(
                "Saved grant: read=${grant?.isReadPermission} write=${grant?.isWritePermission}; uri=$uri",
            )
        }
        button("Release all folder grants and close") {
            contentResolver.persistedUriPermissions
                .filter { DocumentsContract.isTreeUri(it.uri) }
                .forEach {
                    contentResolver.releasePersistableUriPermission(
                        it.uri,
                        (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                            (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            else 0),
                    )
                }
            report("Folder grants released")
            finishAndRemoveTask()
        }
        button("Retain only read access and close") {
            val uri = Uri.parse(prefs.getString("document", null) ?: error("Pick a document first"))
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            report("Write grant released. Cold-start this client to remove temporary access.")
            finishAndRemoveTask()
        }
        button("Try writing the last document") {
            val uri = Uri.parse(prefs.getString("document", null) ?: error("Pick a document first"))
            try {
                val permission =
                    checkUriPermission(
                        uri,
                        android.os.Process.myPid(),
                        android.os.Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                val mode =
                    contentResolver.openFileDescriptor(uri, "rw")!!.use {
                        android.system.Os.fcntlInt(
                            it.fileDescriptor,
                            android.system.OsConstants.F_GETFL,
                            0,
                        ) and android.system.OsConstants.O_ACCMODE
                    }
                report("Write descriptor available; URI permission=$permission fd mode=$mode")
            } catch (_: SecurityException) {
                report("Write correctly denied")
            }
        }
        button("Create inside selected folder") {
            val tree = Uri.parse(prefs.getString("tree", null) ?: error("Pick a folder first"))
            val folder =
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            val uri =
                DocumentsContract.createDocument(
                    contentResolver,
                    folder,
                    "text/plain",
                    "Created through tree.txt",
                )!!
            contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write("Created by the separate QA app.\n".toByteArray())
            }
            prefs.edit().putString("document", uri.toString()).apply()
            report("Created through selected tree: $uri")
        }
        button("Rename last document") {
            val before =
                Uri.parse(prefs.getString("document", null) ?: error("Pick a document first"))
            val after =
                DocumentsContract.renameDocument(
                    contentResolver,
                    before,
                    "Renamed by another app.txt",
                )!!
            prefs.edit().putString("document", after.toString()).apply()
            report(
                "Renamed. Stable document ID: ${DocumentsContract.getDocumentId(before) == DocumentsContract.getDocumentId(after)}",
            )
        }
        button("Send one file to Findroid") { send(listOf("Shared note.txt")) }
        button("Send multiple files to Findroid") {
            send(listOf("Shared note.txt", "Second note.md", "Sample photo.png"))
        }
        button("Send text to Findroid") {
            launchShare(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "A note shared from another Android app.\nhttps://example.com/\n",
                    )
                },
            )
        }
        button("Send a blocked stream") {
            FixturesProvider.gate = java.util.concurrent.CountDownLatch(1)
            send(listOf("Blocked.bin"))
        }
        button("Release blocked stream") {
            FixturesProvider.gate.countDown()
            report("Blocked writer released")
        }
        button("Send an unsafe filename") { send(listOf("Unsafe.txt")) }
    }

    private fun report(value: String) {
        status.text = value
        prefs.edit().putString("status", value).apply()
    }

    private fun send(names: List<String>) {
        val uris =
            ArrayList(
                names.map {
                    Uri.Builder()
                        .scheme("content")
                        .authority("dev.findroid.interop.files")
                        .appendPath(it)
                        .build()
                },
            )
        launchShare(
            Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData =
                    ClipData.newUri(contentResolver, "Synthetic QA files", uris[0]).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
            },
        )
    }

    private fun launchShare(intent: Intent) {
        startActivity(
            intent.setComponent(
                ComponentName("dev.findroid", "dev.findroid.ReceiveFilesActivity"),
            ),
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val flags =
            data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        if (requestCode == 12) {
            prefs.edit().putString("tree", uri.toString()).apply()
            val doc =
                DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri),
                )
            val children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    uri,
                    DocumentsContract.getDocumentId(doc),
                )
            val names =
                contentResolver
                    .query(children, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            report("Selected folder: ${names.joinToString()}")
        } else {
            prefs.edit().putString("document", uri.toString()).apply()
            if (requestCode == 11)
                contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write("Saved by a separate Android app through DocumentsUI.\n".toByteArray())
                }
            report("Document selected: $uri")
        }
    }

    private fun fixtures() {
        File(filesDir, "Shared note.txt")
            .writeText("A real incoming file from the separate QA app.\n")
        File(filesDir, "Second note.md")
            .writeText("# Shared field notes\nA second document in the same batch.\n")
        File(filesDir, "Unsafe.txt")
            .writeText(
                "Synthetic unsafe display name; payload must stay inside the chosen folder.\n",
            )
        val image = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(222, 239, 247))
            val p =
                Paint().apply {
                    color = Color.rgb(35, 113, 191)
                    textSize = 38f
                    isAntiAlias = true
                }
            drawText("Shared from Android", 70f, 200f, p)
        }
        File(filesDir, "Sample photo.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
