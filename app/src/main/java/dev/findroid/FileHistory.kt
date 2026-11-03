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
            check(Files.isRegularFile(item.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "${item.name} is not a regular file. Undo was stopped." }
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
    val description: String? get() = null
    fun validate(undo: Boolean)

    fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>>
    fun savedState(): LocalUndoStep? = null

    class Callback(
        override val description: String? = null,
        private val check: (Boolean) -> Unit,
        private val perform: (Boolean) -> List<Pair<String, String>>,
    ) : FileStep {
        override fun validate(undo: Boolean) = check(undo)

        override fun apply(engine: FileEngine, undo: Boolean) = perform(undo)
    }

    class Created private constructor(private val file: File, private val stamp: String, private var stash: File?) : FileStep {
        constructor(file: File) : this(file, fingerprint(file), null)
        override val description = file.absolutePath
        override fun savedState() = LocalUndoStep(LocalUndoKind.CREATED, listOf(file.path), listOf(stamp), stash?.path)
        internal companion object { fun restore(state: LocalUndoStep) = Created(File(state.paths[0]), state.stamps[0], state.recovery?.let(::File)) }

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
                engine.restoreExact(stash!!, file)
                stash = null
            }
            return emptyList()
        }
    }

    class Moved private constructor(private val before: File, private val after: File, private val stamp: String) : FileStep {
        constructor(before: File, after: File) : this(before, after, fingerprint(after))
        override val description = "${before.absolutePath} → ${after.absolutePath}"
        override fun savedState() = LocalUndoStep(LocalUndoKind.MOVED, listOf(before.path, after.path), listOf(stamp))
        internal companion object { fun restore(state: LocalUndoStep) = Moved(File(state.paths[0]), File(state.paths[1]), state.stamps[0]) }

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

    class Trashed private constructor(private val original: File, private var item: File, private val stamp: String) : FileStep {
        constructor(original: File, item: File) : this(original, item, fingerprint(item))
        override val description = original.absolutePath
        override fun savedState() = LocalUndoStep(LocalUndoKind.TRASHED, listOf(original.path, item.path), listOf(stamp))
        internal companion object { fun restore(state: LocalUndoStep) = Trashed(File(state.paths[0]), File(state.paths[1]), state.stamps[0]) }

        override fun validate(undo: Boolean) {
            verify(if (undo) item else original, stamp)
            if (undo) vacant(original)
        }

        override fun apply(engine: FileEngine, undo: Boolean): List<Pair<String, String>> {
            if (undo) engine.restoreExact(item, original) else item = engine.trash(original)
            return emptyList()
        }
    }

    class Restored private constructor(private val restored: File, private val stamp: String, private var item: File?) : FileStep {
        constructor(restored: File) : this(restored, fingerprint(restored), null)
        override val description = restored.absolutePath
        override fun savedState() = LocalUndoStep(LocalUndoKind.RESTORED, listOf(restored.path), listOf(stamp), item?.path)
        internal companion object { fun restore(state: LocalUndoStep) = Restored(File(state.paths[0]), state.stamps[0], state.recovery?.let(::File)) }

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
                engine.restoreExact(item!!, restored)
                item = null
            }
            return emptyList()
        }
    }

    class Renamed(private val pairs: List<Pair<File, File>>, checkedStamps: List<String>? = null) :
        FileStep {
        override val description = pairs.take(3).joinToString("\n") { "${it.first.absolutePath} → ${it.second.absolutePath}" } +
            if (pairs.size > 3) "\n…and ${pairs.size - 3} more" else ""
        private val stamps = checkedStamps ?: pairs.map { fingerprint(it.second) }

        init {
            require(stamps.size == pairs.size)
        }

        override fun savedState() = LocalUndoStep(LocalUndoKind.RENAMED, pairs.flatMap { listOf(it.first.path, it.second.path) }, stamps)
        internal companion object { fun restore(state: LocalUndoStep) = Renamed(state.paths.chunked(2).map { File(it[0]) to File(it[1]) }, state.stamps) }

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

    class Grouped private constructor(private val folder: File, private val pairs: List<Pair<File, File>>,
        private val stamp: String, private val stamps: List<String>, private var emptyFolder: File?) : FileStep {
        constructor(folder: File, pairs: List<Pair<File, File>>) : this(folder, pairs, fingerprint(folder), pairs.map { fingerprint(it.second) }, null)
        override val description = folder.absolutePath
        override fun savedState() = LocalUndoStep(LocalUndoKind.GROUPED, listOf(folder.path) + pairs.flatMap { listOf(it.first.path, it.second.path) }, listOf(stamp) + stamps, emptyFolder?.path)
        internal companion object { fun restore(state: LocalUndoStep) = Grouped(File(state.paths[0]), state.paths.drop(1).chunked(2).map { File(it[0]) to File(it[1]) }, state.stamps[0], state.stamps.drop(1), state.recovery?.let(::File)) }

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
            engine.restoreExact(emptyFolder!!, folder)
            emptyFolder = null
            pairs.forEach { engine.relocate(it.first, it.second) }
            return pairs.map { it.first.absolutePath to it.second.absolutePath }
        }
    }
}

internal enum class RecentActionState(val title: String) {
    COMPLETED("Completed"), UNDONE("Undone"), PARTLY_UNDONE("Partly undone"), PARTLY_REDONE("Partly redone"),
}
internal data class RecentFileAction(val id: Long, val label: String, val detail: String,
    val recordedAt: Long, val state: RecentActionState, val nextUndo: Boolean, val nextRedo: Boolean,
    val savedForRestart: Boolean = false)

class FileHistory(private val engine: FileEngine, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Command(
        val id: Long, val recordedAt: Long, val detail: String, val label: String, val steps: List<FileStep>,
        val applied: BooleanArray = BooleanArray(steps.size) { true }, var eligible: Boolean = true,
    ) {
        fun snapshot(): SavedUndoCommand? {
            if (!eligible || steps.size > 256 || steps.any { it.savedState()?.paths?.size?.let { count -> count > 2049 } != false }) return null
            return SavedUndoCommand(id, recordedAt, label, detail, steps.map { it.savedState()!! }, applied.toList())
        }
        fun paths() = steps.mapNotNull { it.savedState() }.flatMap { it.footprint }.toSet()
    }
    private var persistence: UndoHistoryPersistence? = null
    private var files: UndoHistoryFiles? = null
    private var durable = SavedUndoHistory()
    private var operationActive = false
    private val changedPaths = linkedSetOf<String>()
    private var savedIds = emptySet<Long>()
    internal var recoveryProblem: String? = null
        private set
    internal var persistenceNotice: String? = null
        private set
    private var nextId = 1L
    internal var lastFailure: String? = null
        private set
    private val undo = ArrayDeque<Command>()
    private val redo = ArrayDeque<Command>()
    val undoLabel get() = if (recoveryProblem == null) undo.lastOrNull()?.label else null
    val redoLabel get() = if (recoveryProblem == null) redo.lastOrNull()?.label else null

    internal constructor(engine: FileEngine, store: UndoHistoryPersistence, files: UndoHistoryFiles,
        clock: () -> Long = System::currentTimeMillis) : this(engine, clock) {
        persistence = store; this.files = files
        val loaded = store.load(); recoveryProblem = loaded.problem
        loaded.state?.let { state ->
            try {
                fun restore(command: SavedUndoCommand): Command {
                    val steps = command.steps.map { step ->
                        val recovery = when (step.kind) {
                            LocalUndoKind.TRASHED -> step.paths[1]
                            else -> step.recovery
                        }
                        check(recovery == null || engine.ownsRecovery(File(recovery))) { "Saved Undo recovery location is not owned by Findroid." }
                        when (step.kind) {
                            LocalUndoKind.CREATED -> FileStep.Created.restore(step)
                            LocalUndoKind.MOVED -> FileStep.Moved.restore(step)
                            LocalUndoKind.TRASHED -> FileStep.Trashed.restore(step)
                            LocalUndoKind.RESTORED -> FileStep.Restored.restore(step)
                            LocalUndoKind.RENAMED -> FileStep.Renamed.restore(step)
                            LocalUndoKind.GROUPED -> FileStep.Grouped.restore(step)
                        }
                    }
                    return Command(command.id, command.time, command.detail, command.label, steps, command.applied.toBooleanArray())
                }
                val restoredUndo = state.undo.map(::restore); val restoredRedo = state.redo.map(::restore)
                undo.addAll(restoredUndo); redo.addAll(restoredRedo); nextId = state.nextId; durable = state
                if (recoveryProblem == null) savedIds = (state.undo + state.redo).map { it.id }.toSet()
            } catch (_: Exception) { recoveryProblem = "Saved Undo references could not be read safely. The original record was kept for review." }
        }
    }

    /** Called before a normal file operation; the marker outlives file changes and metadata remapping. */
    @Synchronized internal fun beginOperation(label: String) {
        if (persistence == null || recoveryProblem != null) return
        check(!operationActive) { "Another saved Undo operation is in progress." }
        try { checkUndoProofs(durable.proofs, files!!) }
        catch (error: Exception) {
            recoveryProblem = error.message ?: "Saved Undo files changed. Review the list before continuing."
            savedIds = emptySet()
            persistence!!.begin("Saved Undo needs review")
            persistence!!.save(durable, complete = false)
            return
        }
        start(label)
    }
    private fun start(label: String) {
        try { persistence!!.begin(label); operationActive = true; changedPaths.clear() }
        catch (error: Exception) {
            recoveryProblem = "Couldn't preserve the Undo operation marker. No action was started. ${error.message}"
            savedIds = emptySet(); throw error
        }
    }
    private fun snapshot(): SavedUndoHistory {
        // Each side is the contiguous reachable suffix at the current cursor. Never skip a callback.
        fun suffix(values: ArrayDeque<Command>) = values.toList().takeLastWhile { it.snapshot() != null }.map { it.snapshot()!! }
        var savedUndo = suffix(undo); var savedRedo = suffix(redo)
        val previous = durable.proofs.associateBy { it.path }
        val observed = mutableMapOf<String, UndoPathProof>()
        var proofs: List<UndoPathProof>
        while (true) {
            val roots = (savedUndo + savedRedo).flatMap { it.steps }.flatMap { it.footprint }.toSet()
            proofs = if (roots.size <= 4096) roots.sorted().map { path ->
                previous[path]?.takeUnless { changedPaths.any { change -> pathWithin(path, change) || pathWithin(change, path) } }
                    ?: observed.getOrPut(path) { files!!.observe(path) }
            } else emptyList()
            if (roots.size <= 4096 && proofs.sumOf { it.entries.size } <= 20_000) {
                try {
                    UndoHistoryJson.encode(SavedUndoHistory(nextId, savedUndo, savedRedo, proofs))
                    break
                } catch (_: UndoHistoryCapacityException) { /* retire the farthest reference */ }
            }
            // Retire only the farthest references. Never delete a document or a Trash item.
            if (savedUndo.isNotEmpty()) savedUndo = savedUndo.drop(1) else savedRedo = savedRedo.drop(1)
            persistenceNotice = "The saved Undo list reached its storage limit. Older actions remain available in this session; files and Trash were kept."
        }
        val reachable = (savedUndo + savedRedo).map { it.id }.toSet()
        // Do not invent fresh identity proofs for a command after a session-only boundary hid it.
        (undo + redo).filter { it.id !in reachable }.forEach { it.eligible = false }
        return SavedUndoHistory(nextId, savedUndo, savedRedo, proofs).also(UndoHistoryJson::validate)
    }

    @Synchronized internal fun finishOperation(success: Boolean) {
        if (!operationActive) return
        try {
            if (success) {
                files!!.sync(changedPaths)
                val state = snapshot()
                persistence!!.save(state, complete = true)
                durable = state; savedIds = (state.undo + state.redo).map { it.id }.toSet()
            } else {
                persistence!!.save(snapshot(), complete = false)
                recoveryProblem = "An operation stopped after its Undo marker was saved. Review the files before resetting saved Undo. Nothing will run automatically."
                savedIds = emptySet()
            }
        } catch (error: Exception) {
            recoveryProblem = "The Undo save could not be completed. Files and recovery data were kept for manual review. ${error.message}"
            savedIds = emptySet(); throw error
        } finally { operationActive = false; changedPaths.clear() }
    }

    @Synchronized fun record(label: String, steps: List<FileStep>) {
        if (steps.isEmpty()) return
        val automatic = persistence != null && recoveryProblem == null && !operationActive
        if (automatic) start(label)
        val details = steps.mapNotNull { it.description }.distinct()
        val detail = details.take(3).joinToString("\n") + if (details.size > 3) "\n…and ${details.size - 3} more" else ""
        val command = Command(nextId++, clock(), detail, label, steps)
        undo.addLast(command); redo.clear(); lastFailure = null
        while (undo.size > 50) undo.removeFirst()
        if (persistence != null && recoveryProblem == null) {
            try {
                val local = command.snapshot()
                if (local != null) {
                    files!!.sync(command.paths())
                    val proofs = command.paths().sorted().map { files!!.observe(it) }
                    UndoHistoryJson.encode(SavedUndoHistory(nextId, listOf(local), proofs = proofs))
                } else if (steps.all { it.savedState() != null }) {
                    persistenceNotice = "This operation is too large for saved Undo. It remains available in this session."
                }
            } catch (error: Exception) {
                command.eligible = false
                persistenceNotice = "This action is available in this session only. Its storage or history size could not be verified for restart."
            }
            if (command.eligible && command.snapshot() != null) changedPaths += command.paths()
        }
        if (automatic) finishOperation(true)
    }

    @Synchronized fun clear() {
        val automatic = persistence != null && recoveryProblem == null && !operationActive
        if (automatic) start("Clear Undo references")
        lastFailure = null; undo.clear(); redo.clear(); savedIds = emptySet()
        if (automatic) finishOperation(true)
    }

    /** Manual reset preserves the complete old journal directory, including corrupt or pending data. */
    @Synchronized internal fun resetSavedHistory(): String? {
        check(!operationActive) { "Wait for the current operation." }
        val preserved = persistence?.reset()
        undo.clear(); redo.clear(); savedIds = emptySet(); durable = SavedUndoHistory(nextId)
        recoveryProblem = null; lastFailure = null; persistenceNotice = null
        return preserved
    }

    /** Immutable display values never expose recovery objects or mutable step state. */
    @Synchronized internal fun recentActions(): List<RecentFileAction> {
        fun view(command: Command, onUndoStack: Boolean): RecentFileAction {
            val partial = command.applied.any { it } && command.applied.any { !it }
            val state = when {
                partial && onUndoStack -> RecentActionState.PARTLY_UNDONE
                partial -> RecentActionState.PARTLY_REDONE
                onUndoStack -> RecentActionState.COMPLETED
                else -> RecentActionState.UNDONE
            }
            return RecentFileAction(command.id, command.label, command.detail, command.recordedAt, state,
                recoveryProblem == null && onUndoStack && command === undo.lastOrNull(),
                recoveryProblem == null && !onUndoStack && command === redo.lastOrNull(), command.id in savedIds)
        }
        return (undo.map { view(it, true) } + redo.map { view(it, false) }).sortedByDescending { it.id }
    }
    fun undo(): List<Pair<String, String>> = execute(true, false)
    fun redo(): List<Pair<String, String>> = execute(false, false)
    internal fun undoDeferred(): List<Pair<String, String>> = execute(true, true)
    internal fun redoDeferred(): List<Pair<String, String>> = execute(false, true)

    @Synchronized private fun execute(backward: Boolean, deferred: Boolean): List<Pair<String, String>> {
        check(recoveryProblem == null) { recoveryProblem!! }
        val source = if (backward) undo else redo
        val target = if (backward) redo else undo
        val command = source.lastOrNull() ?: return emptyList()
        var automatic = false
        try {
            if (persistence != null) checkUndoProofs(durable.proofs, files!!)
            val indices = (if (backward) command.steps.indices.reversed() else command.steps.indices).filter { command.applied[it] == backward }
            indices.forEach { command.steps[it].validate(backward) }
            if (persistence != null && !operationActive) { start((if (backward) "Undo " else "Redo ") + command.label); automatic = !deferred }
            changedPaths += command.paths()
            val changes = mutableListOf<Pair<String, String>>()
            try {
                indices.forEach { index ->
                    changes += engine.withHistoryPublication(files?.let { fileSystem -> { from, to -> fileSystem.moveNoReplace(from, to) } }) {
                        command.steps[index].apply(engine, backward)
                    }
                    command.applied[index] = !backward
                    changedPaths += command.paths()
                }
            } catch (error: Exception) {
                if (error is RenameRecoveryException) { changes += error.changes; clear() }
                throw HistoryFailure(changes, error)
            }
            source.removeLast(); target.addLast(command); lastFailure = null
            if (automatic) finishOperation(true)
            return changes
        } catch (error: Exception) {
            lastFailure = error.message ?: "This action could not safely continue."
            if (automatic && operationActive) runCatching { finishOperation(false) }
            throw error
        }
    }
}
