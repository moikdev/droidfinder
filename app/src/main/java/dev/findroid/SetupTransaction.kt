package dev.findroid

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

internal data class SetupPref(val type: String, val values: List<String>)
internal interface SetupPreferencePort {
    fun read(): Map<String, Map<String, SetupPref>>
    fun write(value: Map<String, Map<String, SetupPref>>)
}

/** A private write-ahead undo journal, containing only a fixed set of app-owned metadata files.
 * A prepared transaction rolls back before a FinderSession is constructed after process death.
 * A committed transaction only needs cleanup. No user file or Android access grant is a target.
 */
internal class SetupTransaction(
    private val directory: File,
    private val preferences: SetupPreferencePort,
    private val afterWrite: (String) -> Unit = {},
) {
    companion object {
        val FILES = setOf("tags.properties", "workspaces.json", "folder-views.json", "folder-styles.properties", "rename-presets.properties", "setup-previous.json")
        val KEYS = mapOf(
            "finder" to setOf(SidebarJson.KEY, "favorites", "smartFolders", "density", "extensions", "dark", "sidebarWidth", "sidebarVisible", "tagsVisible", "gestureHintDismissed", "conflictPolicy"),
            "zip-limits" to setOf("entries", "total", "member", "ratio", "preview"),
        )
    }
    private val journal = File(directory, ".setup-transaction")
    private val manifest get() = File(journal, "manifest.json")

    @Synchronized
    fun fingerprint(): String {
        check(!journal.exists()) { "A setup recovery is pending. Restart Findroid to recover it before importing." }
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: ByteArray) { digest.update(value.size.toString().toByteArray()); digest.update(0); digest.update(value) }
        FILES.sorted().forEach { name ->
            add(name.toByteArray())
            val file = File(directory, name)
            add(if (file.exists()) byteArrayOf(1) + bounded(file) else byteArrayOf(0))
        }
        add(prefsJson(preferences.read()).toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun apply(expected: String, files: Map<String, ByteArray>, prefs: Map<String, Map<String, SetupPref>>) {
        require(files.keys == FILES) { "Invalid setup transaction targets." }
        require(files.values.sumOf { it.size.toLong() } <= 96L * 1024 * 1024) { "This setup is too large to restore." }
        validatePrefs(prefs)
        check(fingerprint() == expected) { "The current setup changed. Refresh the restore review before continuing." }
        check(journal.mkdir()) { "Couldn't prepare setup recovery storage." }
        val oldFiles = linkedSetOf<String>()
        var prepared = false
        try {
            val old = File(journal, "old").apply { check(mkdir()) }
            FILES.forEach { name ->
                val source = File(directory, name)
                if (source.exists()) { durableWrite(File(old, name), bounded(source)); oldFiles += name }
            }
            val record = JsonObject().apply {
                addProperty("version", 1); addProperty("phase", "prepared")
                add("oldFiles", JsonArray().apply { oldFiles.sorted().forEach(::add) })
                add("preferences", prefsJson(preferences.read()))
            }
            val journalBytes = record.toString().toByteArray()
            require(journalBytes.size <= SetupJsonTree.MAX_BYTES) { "Setup recovery metadata is too large." }
            atomicWrite(manifest, journalBytes)
            prepared = true
            FILES.sorted().forEach { name ->
                atomicWrite(File(directory, name), files.getValue(name))
                afterWrite(name)
            }
            preferences.write(prefs)
            afterWrite("preferences")
            record.addProperty("phase", "committed")
            atomicWrite(manifest, record.toString().toByteArray())
        } catch (failure: Exception) {
            if (prepared) {
                try { recover() } catch (recovery: Exception) {
                    throw IllegalStateException("Restore stopped and setup recovery is still pending. Restart Findroid to retry recovery. ${recovery.message}", failure)
                }
            }
            throw failure
        } finally {
            // An Error simulates process death in tests: leave a prepared journal for startup.
            if (!prepared || runCatching { phase() == "committed" }.getOrDefault(false)) journal.deleteRecursively()
        }
    }

    @Synchronized
    fun recover(): Boolean {
        if (!journal.exists()) return false
        if (!manifest.exists()) { check(journal.deleteRecursively()) { "Couldn't clean incomplete setup staging." }; return false }
        val record = SetupJsonTree.parse(bounded(manifest).toString(Charsets.UTF_8)).asJsonObject
        require(record.keySet() == setOf("version", "phase", "oldFiles", "preferences") && record.get("version").asInt == 1) { "Setup recovery metadata is invalid; the recovery files were kept." }
        val phase = record.get("phase").asString
        require(phase in setOf("prepared", "committed")) { "Unknown setup recovery phase." }
        if (phase == "prepared") {
            val names = record.getAsJsonArray("oldFiles").map { it.asString }
            require(names.distinct().size == names.size && names.all { it in FILES })
            val prefs = readPrefs(record.getAsJsonObject("preferences"))
            // Validate all recovery inputs before changing any target.
            val contents = names.associateWith { bounded(File(journal, "old/$it")) }
            FILES.sorted().forEach { name ->
                val file = File(directory, name)
                val bytes = contents[name]
                if (bytes != null) atomicWrite(file, bytes)
                else if (file.exists()) check(file.delete()) { "Couldn't restore missing setup file $name." }
            }
            preferences.write(prefs)
        }
        check(journal.deleteRecursively()) { "Couldn't clean recovered setup storage." }
        return phase == "prepared"
    }

    private fun phase(): String? = if (!manifest.exists()) null else SetupJsonTree.parse(manifest.readText()).asJsonObject.get("phase").asString
    private fun bounded(file: File, limit: Int = SetupJsonTree.MAX_BYTES): ByteArray {
        require(file.isFile && file.length() <= limit) { "Setup metadata is missing or too large: ${file.name}." }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limit) { "Setup metadata grew beyond its limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    private fun durableWrite(file: File, bytes: ByteArray) { FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val staging = File(file.parentFile, "${file.name}.setup-new")
        try {
            durableWrite(staging, bytes)
            try { Files.move(staging.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging.toPath(), file.toPath(), REPLACE_EXISTING) }
            // Android AtomicFile readers must not recover an older generation over this commit.
            listOf(".bak", ".new").forEach { suffix -> File(file.path + suffix).let { if (it.exists()) check(it.delete()) } }
        } finally { staging.delete() }
    }
    private fun validatePrefs(value: Map<String, Map<String, SetupPref>>) {
        require(value.keys == KEYS.keys)
        value.forEach { (store, values) ->
            require(values.keys.all { it in KEYS.getValue(store) }) { "Unknown setup preference target." }
            values.values.forEach { v ->
                require(v.type in setOf("string", "boolean", "int", "long", "set") && (v.type == "set" || v.values.size == 1))
                when (v.type) { "boolean" -> v.values.single().toBooleanStrict(); "int" -> v.values.single().toInt(); "long" -> v.values.single().toLong() }
                require(v.values.all { '\u0000' !in it && Charsets.UTF_8.newEncoder().canEncode(it) })
            }
        }
    }
    private fun prefsJson(value: Map<String, Map<String, SetupPref>>) = JsonObject().apply {
        validatePrefs(value)
        value.toSortedMap().forEach { (store, values) -> add(store, JsonObject().apply {
            values.toSortedMap().forEach { (key, v) -> add(key, JsonObject().apply {
                addProperty("type", v.type); add("values", JsonArray().apply { v.values.forEach(::add) })
            }) }
        }) }
    }
    private fun readPrefs(root: JsonObject): Map<String, Map<String, SetupPref>> = root.entrySet().associate { (store, value) ->
        store to value.asJsonObject.entrySet().associate { (key, raw) -> raw.asJsonObject.let {
            require(it.keySet() == setOf("type", "values")); key to SetupPref(it.get("type").asString, it.getAsJsonArray("values").map { item -> item.asString })
        } }
    }.also(::validatePrefs)
}
