package dev.findroid

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

data class TransferEstimate(
    val knownBytes: Long = 0,
    val files: Int = 0,
    val folders: Int = 0,
    val unknownFiles: Int = 0,
    val unreadable: Int = 0,
    val limited: Boolean = false,
) {
    val complete
        get() = unknownFiles == 0 && unreadable == 0 && !limited

    val totalBytes
        get() = knownBytes.takeIf { complete }
}

data class EstimateNode(
    val path: String,
    val folder: Boolean,
    val bytes: Long? = null,
    val link: Boolean = false,
)

/** Metadata-only inventory: incomplete/provider-unknown sizes never become a false exact total. */
fun estimateTransfer(
    roots: List<String>,
    read: (String) -> EstimateNode,
    list: (String) -> Iterable<EstimateNode>,
    control: TransferControl = TransferControl(),
    limit: Int = 100_000,
): TransferEstimate {
    require(limit > 0)
    var result = TransferEstimate()
    var inspected = 0
    for (root in roots) {
        val pending = ArrayDeque<Pair<EstimateNode, Int>>()
        val seen = mutableSetOf<String>()
        try {
            control.check()
            pending.add(read(root) to 0)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            result = result.copy(unreadable = result.unreadable + 1)
            continue
        }
        while (pending.isNotEmpty()) {
            control.check()
            if (++inspected > limit) return result.copy(limited = true)
            val (node, depth) = pending.removeFirst()
            control.inspected()
            if (node.link || !seen.add(node.path)) {
                result = result.copy(unreadable = result.unreadable + 1)
                continue
            }
            if (node.folder) {
                result = result.copy(folders = result.folders + 1)
                if (depth >= 128) {
                    result = result.copy(limited = true)
                    continue
                }
                try {
                    val children = list(node.path).iterator()
                    while (children.hasNext()) {
                        if (pending.size >= limit - inspected) {
                            result = result.copy(limited = true)
                            break
                        }
                        pending.add(children.next() to depth + 1)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    result = result.copy(unreadable = result.unreadable + 1)
                }
            } else {
                result = result.copy(files = result.files + 1)
                val bytes = node.bytes?.takeIf { it >= 0 }
                if (bytes == null) result = result.copy(unknownFiles = result.unknownFiles + 1)
                else if (Long.MAX_VALUE - result.knownBytes < bytes)
                    result = result.copy(knownBytes = Long.MAX_VALUE, limited = true)
                else result = result.copy(knownBytes = result.knownBytes + bytes)
            }
        }
    }
    return result
}

fun estimateLocalTransfer(paths: List<String>, control: TransferControl): TransferEstimate {
    fun node(file: File): EstimateNode {
        check(file.exists() || Files.isSymbolicLink(file.toPath())) {
            "${file.name} is unavailable."
        }
        return EstimateNode(
            file.path,
            file.isDirectory,
            file.length(),
            Files.isSymbolicLink(file.toPath()),
        )
    }
    return estimateTransfer(
        paths,
        { node(File(it)) },
        {
            (File(it).listFiles() ?: error("Couldn't read this folder."))
                .asSequence()
                .map(::node)
                .asIterable()
        },
        control,
    )
}

class InsufficientSpaceException(val needed: Long, val available: Long) :
    java.io.IOException(
        "Not enough space: at least ${formatSize(needed)} needed, ${formatSize(available)} available. Free space or choose another destination.",
    )

fun requireTransferSpace(knownBytes: Long, available: Long?) {
    if (available != null && available >= 0 && knownBytes > available)
        throw InsufficientSpaceException(knownBytes, available)
}

// Includes device sleep so an old estimate expires when a tablet wakes.
fun transferClockMillis() = android.os.SystemClock.elapsedRealtime()
