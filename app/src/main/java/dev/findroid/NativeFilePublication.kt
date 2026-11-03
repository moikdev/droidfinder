package dev.findroid

import android.system.ErrnoException

/** Kernel no-replace rename. Capability is probed before any source data is copied. */
internal object NativeFilePublication {
    private val loaded = runCatching { System.loadLibrary("findroid_files") }
    private external fun renameNoReplace(from: ByteArray, to: ByteArray): Int
    fun moveNoReplace(from: String, to: String) {
        loaded.getOrElse { throw java.io.IOException("This device cannot safely publish a saved copy.", it) }
        val error = try { renameNoReplace(from.toByteArray(Charsets.UTF_8), to.toByteArray(Charsets.UTF_8)) }
        catch (error: LinkageError) { throw java.io.IOException("This device cannot safely publish a saved copy.", error) }
        if (error != 0) throw ErrnoException("renameat2(RENAME_NOREPLACE)", error)
    }
}
