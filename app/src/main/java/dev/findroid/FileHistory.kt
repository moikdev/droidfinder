package dev.findroid

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Digest paths relative to the item, never following links. Protects Undo from later edits. */
fun fingerprint(file: File, control: TransferControl? = null): String {
    check(file.exists() || Files.isSymbolicLink(file.toPath())) {
        "${file.name} is no longer available."
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var visited = 0
    fun add(item: File, relative: String, depth: Int = 0) {
        control?.check()
        require(++visited <= 100_000 && depth <= 128) {
            "Undo verification exceeds the safe traversal limit."
        }
        digest.update(relative.toByteArray())
        if (Files.isSymbolicLink(item.toPath())) {
            digest.update("link:${Files.readSymbolicLink(item.toPath())}".toByteArray())
        } else if (item.isDirectory) {
            digest.update(0)
            (item.listFiles() ?: error("Couldn't read ${item.name}."))
                .sortedBy { it.name }
                .forEach { add(it, "$relative/${it.name}", depth + 1) }
        } else {
            digest.update("file:${item.length()}:${item.lastModified()}:".toByteArray())
            item.inputStream().buffered().use { input ->
                fun read() {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        control?.check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                if (control != null) control.guard(input, ::read) else read()
            }
        }
    }
    add(file, "")
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun verify(file: File, expected: String) {
    check(fingerprint(file) == expected) {
        "${file.name} changed after this operation. Undo was stopped to protect your edits."
    }
}

private fun vacant(file: File) {
    check(!file.entryExists()) { "${file.name} already exists. Move it before undoing or redoing." }
}

class HistoryFailure(val changes: List<Pair<String, String>>, cause: Exception) :
    Exception(
        "${changes.size} path changes completed before the operation stopped. Completed files were kept. ${cause.message}",
        cause,
    )

sealed interface FileStep {
    fun validate(undo: Boolean)

    fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>>

    class Callback(
        private val check: (Boolean) -> Unit,
        private val perform: (Boolean) -> List<Pair<String, String>>,
    ) : FileStep {
        override fun validate(undo: Boolean) = check(undo)

        override fun apply(engine: FileEngine, undo: Boolean) = perform(undo)
    }

    class Created(private val file: File) : FileStep {
        private val stamp = fingerprint(file)
        private var stash: File? = null

        override fun validate(undo: Boolean) {
            if (undo) verify(file, stamp)
            else {
                vacant(file)
                verify(stash ?: error("Recovery item unavailable."), stamp)
            }
        }

        override fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>> {
            if (undo) stash = engine.trash(file)
            else {
                engine.restore(stash!!)
                stash = null
            }
            return emptyList()
        }
    }

    class Moved(private val before: File, private val after: File) : FileStep {
        private val stamp = fingerprint(after)

        override fun validate(undo: Boolean) {
            verify(if (undo) after else before, stamp)
            vacant(if (undo) before else after)
        }

        override fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>> {
            val from = if (undo) after else before
            val to = if (undo) before else after
            engine.relocate(from, to)
            return listOf(from.absolutePath to to.absolutePath)
        }
    }

    class Trashed(private val original: File, private var item: File) : FileStep {
        private val stamp = fingerprint(item)

        override fun validate(undo: Boolean) {
            verify(if (undo) item else original, stamp)
            if (undo) vacant(original)
        }

        override fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>> {
            if (undo) engine.restore(item) else item = engine.trash(original)
            return emptyList()
        }
    }

    class Restored(private val restored: File) : FileStep {
        private val stamp = fingerprint(restored)
        private var item: File? = null

        override fun validate(undo: Boolean) {
            if (undo) verify(restored, stamp)
            else {
                verify(item!!, stamp)
                vacant(restored)
            }
        }

        override fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>> {
            if (undo) item = engine.trash(restored)
            else {
                engine.restore(item!!)
                item = null
            }
            return emptyList()
        }
    }

    class Renamed(private val pairs: List<Pair<File, File>>, checkedStamps: List<String>? = null) :
        FileStep {
        private val stamps = checkedStamps ?: pairs.map { fingerprint(it.second) }

        init {
            require(stamps.size == pairs.size)
        }

        override fun validate(undo: Boolean) {
            requireRenameDestinations(
                pairs.map { (before, after) ->
                    RenameProposal(
                        if (undo) after else before,
                        if (undo) before.name else after.name,
                    )
                },
            )
            pairs.forEachIndexed { index, (before, after) ->
                verify(if (undo) after else before, stamps[index])
            }
        }

        override fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>> =
            engine
                .renameBatch(
                    pairs.map { (before, after) ->
                        RenameProposal(
                            if (undo) after else before,
                            if (undo) before.name else after.name,
                        )
                    },
                )
                .map { it.first.absolutePath to it.second.absolutePath }
    }

    class Grouped(private val folder: File, private val pairs: List<Pair<File, File>>) : FileStep {
        private val stamp = fingerprint(folder)
        private val stamps = pairs.map { fingerprint(it.second) }
        private var emptyFolder: File? = null

        override fun validate(undo: Boolean) {
            if (undo) {
                verify(folder, stamp)
                pairs.forEach { vacant(it.first) }
            } else {
                vacant(folder)
                pairs.forEachIndexed { index, pair -> verify(pair.first, stamps[index]) }
                check(emptyFolder?.isDirectory == true)
            }
        }

        override fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>> {
            if (undo) {
                pairs.asReversed().forEach { engine.relocate(it.second, it.first) }
                emptyFolder = engine.trash(folder)
                return pairs.map { it.second.absolutePath to it.first.absolutePath }
            }
            engine.restore(emptyFolder!!)
            emptyFolder = null
            pairs.forEach { engine.relocate(it.first, it.second) }
            return pairs.map { it.first.absolutePath to it.second.absolutePath }
        }
    }
}

class FileHistory(private val engine: FileEngine) {
    data class Command(
        val label: String,
        val steps: List<FileStep>,
        val applied: BooleanArray = BooleanArray(steps.size) { true },
    )

    private val undo = ArrayDeque<Command>()
    private val redo = ArrayDeque<Command>()
    val undoLabel
        get() = undo.lastOrNull()?.label

    val redoLabel
        get() = redo.lastOrNull()?.label

    fun record(label: String, steps: List<FileStep>) {
        if (steps.isEmpty()) return
        undo.addLast(Command(label, steps))
        while (undo.size > 50) undo.removeFirst()
        redo.clear()
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    fun undo(): List<Pair<String, String>> = execute(true)

    fun redo(): List<Pair<String, String>> = execute(false)

    private fun execute(backward: Boolean): List<Pair<String, String>> {
        val source = if (backward) undo else redo
        val target = if (backward) redo else undo
        val command = source.lastOrNull() ?: return emptyList()
        val indices =
            (if (backward) command.steps.indices.reversed() else command.steps.indices).filter {
                command.applied[it] == backward
            }
        // Preflight every affected item before changing anything.
        indices.forEach { command.steps[it].validate(backward) }
        val changes = mutableListOf<Pair<String, String>>()
        try {
            indices.forEach { index ->
                changes += command.steps[index].apply(engine, backward)
                command.applied[index] = !backward
            }
        } catch (error: Exception) {
            if (error is RenameRecoveryException) {
                changes += error.changes
                clear()
            }
            throw HistoryFailure(changes, error)
        }
        source.removeLast()
        target.addLast(command)
        return changes
    }
}
