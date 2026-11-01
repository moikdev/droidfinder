package dev.droidfinder.interop

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch

/** A separate UID supplies synthetic shares; this module is never packaged in DroidFinder. */
class FixturesProvider : ContentProvider() {
    companion object {
        @Volatile var gate = CountDownLatch(1)
    }

    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (
            name !in
                setOf(
                    "Shared note.txt",
                    "Second note.md",
                    "Sample photo.png",
                    "Blocked.bin",
                    "Unsafe.txt",
                )
        )
            throw FileNotFoundException()
        return File(context!!.filesDir, name)
    }

    override fun getType(uri: Uri) =
        when (file(uri).extension) {
            "png" -> "image/png"
            "md" -> "text/markdown"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?,
    ): Cursor {
        val file = file(uri)
        return MatrixCursor(
                projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            )
            .apply {
                val row = newRow()
                columnNames.forEach { c ->
                    row.add(
                        c,
                        when (c) {
                            OpenableColumns.DISPLAY_NAME ->
                                if (file.name == "Unsafe.txt") "../escape.txt" else file.name
                            OpenableColumns.SIZE ->
                                if (file.name == "Blocked.bin") null else file.length()
                            else -> null
                        },
                    )
                }
            }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = file(uri)
        if (mode != "r") throw SecurityException("Synthetic fixtures are read only")
        if (file.name != "Blocked.bin")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
                gate.await()
                runCatching { stream.write("Released synthetic stream".toByteArray()) }
            }
        }
            .start()
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?,
    ) = throw UnsupportedOperationException()
}
