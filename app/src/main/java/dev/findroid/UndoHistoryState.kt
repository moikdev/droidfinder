package dev.findroid

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.StringReader

enum class LocalUndoKind { CREATED, MOVED, TRASHED, RESTORED, RENAMED, GROUPED }
data class LocalUndoStep(val kind: LocalUndoKind, val paths: List<String>, val stamps: List<String>, val recovery: String? = null) {
    val footprint: Set<String> get() = (paths + listOfNotNull(recovery)).toSet()
}
internal data class SavedUndoCommand(val id: Long, val time: Long, val label: String, val detail: String,
    val steps: List<LocalUndoStep>, val applied: List<Boolean>)
internal data class SavedUndoHistory(val nextId: Long = 1, val undo: List<SavedUndoCommand> = emptyList(),
    val redo: List<SavedUndoCommand> = emptyList(), val proofs: List<UndoPathProof> = emptyList())

internal class UndoHistoryCapacityException : IllegalArgumentException("This operation is too large for saved Undo. Its files were kept.")

internal object UndoHistoryJson {
    const val MAX_BYTES = 8 * 1024 * 1024
    private fun path(value: String) {
        require(value.length in 1..4096 && '\u0000' !in value && File(value).isAbsolute && File(value).toPath().normalize().toString() == value) { "Invalid saved Undo location." }
    }
    fun validate(state: SavedUndoHistory) {
        val commands = state.undo + state.redo
        require(state.nextId > 0 && commands.size <= 50 && commands.map { it.id }.distinct().size == commands.size)
        require(state.undo.zipWithNext().all { (a,b) -> a.id < b.id } && state.redo.zipWithNext().all { (a,b) -> a.id > b.id })
        require(state.undo.isEmpty() || state.redo.isEmpty() || state.undo.last().id < state.redo.last().id)
        for (command in commands) {
            require(command.id in 1 until state.nextId && command.time >= 0 && command.label.length in 1..512 && command.detail.length <= 16_384)
            require(command.steps.size in 1..256 && command.applied.size == command.steps.size)
            command.steps.forEach { step ->
                require(step.paths.size in 1..2049 && step.stamps.isNotEmpty() && step.stamps.all { it.matches(Regex("[0-9a-f]{64}")) })
                step.paths.forEach(::path); step.recovery?.let(::path)
                require(when (step.kind) {
                    LocalUndoKind.CREATED, LocalUndoKind.RESTORED -> step.paths.size == 1 && step.stamps.size == 1
                    LocalUndoKind.MOVED, LocalUndoKind.TRASHED -> step.paths.size == 2 && step.stamps.size == 1 && step.recovery == null && step.paths[0] != step.paths[1]
                    LocalUndoKind.RENAMED -> step.paths.size % 2 == 0 && step.stamps.size * 2 == step.paths.size && step.recovery == null
                    LocalUndoKind.GROUPED -> step.paths.size % 2 == 1 && step.stamps.size * 2 == step.paths.size + 1
                })
            }
            command.steps.forEachIndexed { index, step ->
                if (step.kind in setOf(LocalUndoKind.CREATED, LocalUndoKind.RESTORED, LocalUndoKind.GROUPED))
                    require((step.recovery == null) == command.applied[index]) { "Invalid saved Undo recovery state." }
            }
        }
        val roots = commands.flatMap { it.steps }.flatMap { it.footprint }.toSet()
        require(state.proofs.map { it.path }.toSet() == roots && state.proofs.size == roots.size && roots.size <= 4096)
        require(state.proofs.sumOf { it.entries.size } <= 20_000)
        state.proofs.forEach { proof ->
            listOf(proof.path, proof.resolved, proof.anchor, proof.resolvedAnchor).forEach(::path)
            require(File(proof.path).parentFile?.let { pathWithin(it.path, proof.anchor) } == true && proof.anchorDevice >= 0 && proof.anchorInode > 0)
            require(proof.entries.isEmpty() || proof.path in proof.entries)
            proof.entries.forEach { (name, stamp) ->
                path(name); require(pathWithin(name, proof.path))
                require(stamp.device >= 0 && stamp.inode > 0 && stamp.size >= 0 && stamp.links > 0 && stamp.regular != stamp.directory &&
                    stamp.modifiedNanos in 0..999_999_999 && stamp.changedNanos in 0..999_999_999)
            }
        }
    }
    fun encode(state: SavedUndoHistory): String {
        validate(state)
        // Refuse obviously oversized paths before constructing the JSON tree.
        val pathBytes = state.proofs.sumOf { proof -> proof.entries.keys.sumOf { it.toByteArray().size.toLong() } }
        if (pathBytes > MAX_BYTES) throw UndoHistoryCapacityException()
        fun step(value: LocalUndoStep) = JsonObject().apply {
            addProperty("kind", value.kind.name); add("paths", strings(value.paths)); add("stamps", strings(value.stamps)); add("recovery", value.recovery?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
        }
        fun commands(values: List<SavedUndoCommand>) = JsonArray().apply { values.forEach { value -> add(JsonObject().apply {
            addProperty("id", value.id.toString()); addProperty("time", value.time.toString()); addProperty("label", value.label); addProperty("detail", value.detail)
            add("steps", JsonArray().apply { value.steps.forEach { add(step(it)) } }); add("applied", JsonArray().apply { value.applied.forEach(::add) })
        }) } }
        val root = JsonObject().apply {
            addProperty("schema", 1); addProperty("nextId", state.nextId.toString()); add("undo", commands(state.undo)); add("redo", commands(state.redo))
            add("proofs", JsonArray().apply { state.proofs.forEach { value -> add(JsonObject().apply {
                addProperty("path", value.path); addProperty("resolved", value.resolved); addProperty("anchor", value.anchor); addProperty("resolvedAnchor", value.resolvedAnchor)
                addProperty("anchorDevice", value.anchorDevice.toString()); addProperty("anchorInode", value.anchorInode.toString())
                add("entries", JsonArray().apply { value.entries.forEach { (name, stamp) -> add(JsonObject().apply {
                    addProperty("path", name)
                    listOf("device" to stamp.device,"inode" to stamp.inode,"size" to stamp.size,"modifiedSeconds" to stamp.modifiedSeconds,"modifiedNanos" to stamp.modifiedNanos,
                        "changedSeconds" to stamp.changedSeconds,"changedNanos" to stamp.changedNanos,"links" to stamp.links).forEach { (key,number) -> addProperty(key,number.toString()) }
                    addProperty("regular", stamp.regular); addProperty("directory", stamp.directory)
                }) } })
            }) } })
        }
        val buffer = java.io.ByteArrayOutputStream()
        val bounded = object : java.io.OutputStream() {
            override fun write(value: Int) {
                if (buffer.size() >= MAX_BYTES) throw UndoHistoryCapacityException()
                buffer.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (buffer.size().toLong() + count > MAX_BYTES) throw UndoHistoryCapacityException()
                buffer.write(bytes, offset, count)
            }
        }
        bounded.writer(Charsets.UTF_8).use { GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(root, it) }
        return buffer.toString(Charsets.UTF_8.name())
    }
    private fun strings(values: List<String>) = JsonArray().apply { values.forEach(::add) }

    fun decode(text: String): SavedUndoHistory {
        require(text.toByteArray().size <= MAX_BYTES)
        // Reject duplicate keys and trailing values rather than silently choosing a recovery record.
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        var nodes = 0
        fun value(depth: Int): JsonElement {
            require(depth <= 16 && ++nodes <= 400_000)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                    reader.beginObject(); while (reader.hasNext()) { val key = reader.nextName(); require(!has(key)); add(key, value(depth + 1)) }; reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().apply { reader.beginArray(); while (reader.hasNext()) add(value(depth + 1)); reader.endArray() }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> { val number = reader.nextString(); require(number == "1"); JsonPrimitive(1) }
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> error("Invalid saved Undo data.")
            }
        }
        val root = reader.use { val element = value(0); require(reader.peek() == JsonToken.END_DOCUMENT); element.asJsonObject }
        fun JsonObject.fields(vararg names: String) { require(keySet() == names.toSet()) }
        fun JsonObject.text(key: String): String = get(key).let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        fun JsonObject.number(key: String): Long = text(key).let { require(it.matches(Regex("-?(0|[1-9][0-9]*)"))); it.toLong() }
        fun JsonObject.flag(key: String): Boolean = get(key).let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean }
        fun JsonObject.list(key: String): List<JsonElement> = get(key).asJsonArray.toList()
        fun JsonObject.texts(key: String) = list(key).map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        fun commands(key: String) = root.list(key).map { element -> element.asJsonObject.run {
            fields("id","time","label","detail","steps","applied")
            SavedUndoCommand(number("id"),number("time"),text("label"),text("detail"),list("steps").map { step -> step.asJsonObject.run {
                fields("kind","paths","stamps","recovery")
                LocalUndoStep(LocalUndoKind.valueOf(text("kind")),texts("paths"),texts("stamps"),get("recovery").let { if (it.isJsonNull) null else text("recovery") })
            } },list("applied").map { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean })
        } }
        root.fields("schema","nextId","undo","redo","proofs"); require(root.get("schema").let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1" })
        return SavedUndoHistory(root.number("nextId"),commands("undo"),commands("redo"),root.list("proofs").map { element -> element.asJsonObject.run {
            fields("path","resolved","anchor","resolvedAnchor","anchorDevice","anchorInode","entries")
            val entries = linkedMapOf<String,PartialFileStamp>()
            list("entries").forEach { entry -> entry.asJsonObject.run {
                fields("path","device","inode","size","modifiedSeconds","modifiedNanos","changedSeconds","changedNanos","links","regular","directory")
                val stamp = PartialFileStamp(number("device"),number("inode"),number("size"),number("modifiedSeconds"),number("modifiedNanos"),number("changedSeconds"),number("changedNanos"),flag("regular"),flag("directory"),number("links"))
                require(entries.put(text("path"),stamp) == null)
            } }
            UndoPathProof(text("path"),text("resolved"),text("anchor"),text("resolvedAnchor"),number("anchorDevice"),number("anchorInode"),entries)
        } }).also(::validate)
    }
}
