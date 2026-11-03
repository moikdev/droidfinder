package dev.findroid

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.InputStream

/** Check the opened descriptor before reading: a cached path may have been replaced since indexing. */
internal fun indexedLocalInput(entry: Entry, root: String, control: ListingControl): InputStream = control.stream {
    currentIndexedFile(entry.path, root)
    val raw = Os.open(entry.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
    val descriptor = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
    try {
        val actual = Os.readlink("/proc/self/fd/${descriptor.fd}")
        check(actual == entry.path && indexWithin(actual, root)) { "This indexed file changed location. Refresh the index." }
        val opened = Os.fstat(descriptor.fileDescriptor)
        check(OsConstants.S_ISREG(opened.st_mode)) { "This indexed item is no longer an ordinary file." }
        currentIndexedFile(entry.path, root)
        val current = Os.lstat(entry.path)
        check(current.st_dev == opened.st_dev && current.st_ino == opened.st_ino) { "This indexed file was replaced while opening. Try again." }
        control.check()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    } catch (failure: Exception) {
        descriptor.close()
        throw failure
    }
}
