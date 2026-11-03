package dev.findroid

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Android's descriptor APIs avoid persisting an unspecified BasicFileAttributes.fileKey string. */
internal class AndroidPartialCopyStorage : PartialCopyStorage {
    override fun canonical(path: String) = File(path).canonicalPath
    override fun stat(path: String): PartialFileStamp? = try {
        Os.lstat(path).partialStamp()
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }
    override fun createDirectory(path: String) = Os.mkdir(path, 0b111000000)
    override fun moveNoReplace(existing: String, target: String) = NativeFilePublication.moveNoReplace(existing, target)
    override fun remove(path: String) = Os.remove(path)

    override fun open(path: String, writable: Boolean, createNew: Boolean, directory: Boolean): PartialCopyHandle {
        require(!createNew || writable && !directory)
        val flags = (if (writable) OsConstants.O_RDWR else OsConstants.O_RDONLY) or
            OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or
            (if (createNew) OsConstants.O_CREAT or OsConstants.O_EXCL else 0)
        val descriptor = Os.open(path, flags, 0b110000000)
        try {
            val opened = Os.fstat(descriptor)
            check(if (directory) OsConstants.S_ISDIR(opened.st_mode) else OsConstants.S_ISREG(opened.st_mode)) {
                "This location does not support a regular saved copy."
            }
        } catch (error: Exception) {
            runCatching { Os.close(descriptor) }
            throw error
        }
        return object : PartialCopyHandle {
            private val closed = AtomicBoolean(false)
            override fun stamp() = Os.fstat(descriptor).partialStamp()
            override fun seek(offset: Long) { require(offset >= 0); check(Os.lseek(descriptor, offset, OsConstants.SEEK_SET) == offset) }
            override fun read(buffer: ByteArray, offset: Int, count: Int) = Os.read(descriptor, buffer, offset, count)
            override fun write(buffer: ByteArray, offset: Int, count: Int) = Os.write(descriptor, buffer, offset, count)
            override fun truncate(size: Long) { require(size >= 0); Os.ftruncate(descriptor, size) }
            override fun sync() = Os.fsync(descriptor)
            override fun close() { if (closed.compareAndSet(false, true)) Os.close(descriptor) }
        }
    }
}

private fun StructStat.partialStamp() = PartialFileStamp(st_dev, st_ino, st_size,
    st_mtim.tv_sec, st_mtim.tv_nsec, st_ctim.tv_sec, st_ctim.tv_nsec,
    OsConstants.S_ISREG(st_mode), OsConstants.S_ISDIR(st_mode), st_nlink)
