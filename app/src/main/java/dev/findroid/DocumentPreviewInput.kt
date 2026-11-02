package dev.findroid

import android.content.res.AssetFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Poll before reading provider descriptors: closing an AFD stream alone may leave a pipe read
 * blocked.
 */
internal class DocumentPreviewInput(
    private val asset: AssetFileDescriptor,
    private val check: () -> Unit,
) : InputStream() {
    private val closed = AtomicBoolean(false)
    private val descriptor = asset.fileDescriptor
    private var remaining = asset.declaredLength
    private val poll =
        StructPollfd().apply {
            fd = descriptor
            events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
        }

    private val polls = arrayOf(poll)

    init {
        try {
            try {
                Os.lseek(descriptor, asset.startOffset, OsConstants.SEEK_SET)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ESPIPE || asset.startOffset != 0L) throw error
            }
        } catch (error: Exception) {
            asset.close()
            throw IOException("Couldn't seek to the document's data.", error)
        }
    }

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length)
            throw IndexOutOfBoundsException()
        if (length == 0) return 0
        while (true) {
            check()
            if (closed.get()) throw IOException("Document stream closed.")
            if (remaining == 0L) return -1
            try {
                poll.revents = 0
                if (Os.poll(polls, 100) == 0) continue
                check()
                if (closed.get()) throw IOException("Document stream closed.")
                if (poll.revents.toInt() and OsConstants.POLLNVAL != 0)
                    throw IOException("Document descriptor is no longer valid.")
                val count =
                    Os.read(
                        descriptor,
                        bytes,
                        offset,
                        minOf(
                            length,
                            64 * 1024,
                            if (remaining < 0) Int.MAX_VALUE
                            else remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        ),
                    )
                check()
                if (count == 0) return -1
                if (remaining >= 0) remaining -= count
                return count
            } catch (error: ErrnoException) {
                check()
                if (error.errno != OsConstants.EINTR)
                    throw IOException("Couldn't read the document.", error)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) asset.close()
    }
}
