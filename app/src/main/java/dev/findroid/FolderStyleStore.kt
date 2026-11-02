package dev.findroid

import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.Properties

data class FolderStyle(val color: String = "Blue", val symbol: String = "")

class FolderStyleStore(private val file: File) {
    var styles: Map<String, FolderStyle> =
        if (file.exists())
            Properties()
                .apply { file.inputStream().use(::load) }
                .entries
                .associate { (key, value) ->
                    val parts = value.toString().split('\u001f', limit = 2)
                    key.toString() to FolderStyle(parts[0], parts.getOrElse(1) { "" })
                }
        else emptyMap()
        private set

    private fun save() {
        Properties()
            .apply {
                styles.forEach { (path, style) ->
                    setProperty(path, "${style.color}\u001f${style.symbol}")
                }
            }
            .also { properties ->
                file.outputStream().use { properties.store(it, "Folder appearance") }
            }
    }

    fun set(path: String, style: FolderStyle?) {
        styles =
            styles.toMutableMap().also { if (style == null) it.remove(path) else it[path] = style }
        save()
    }

    fun relocate(pairs: List<Pair<String, String>>, copy: Boolean = false) {
        val before = styles
        val after = styles.toMutableMap()
        if (!copy)
            before.keys
                .filter { path -> pairs.any { pathWithin(path, it.first) } }
                .forEach(after::remove)
        before.forEach { (path, style) ->
            pairs
                .filter { pathWithin(path, it.first) }
                .maxByOrNull { it.first.length }
                ?.let { after[it.second + path.removePrefix(it.first)] = style }
        }
        styles = after
        save()
    }
}

/** An inert, inspectable app shortcut. Resolving it never executes code or opens arbitrary URLs. */
object FinderShortcut {
    private const val HEADER = "Findroid shortcut v1\n"
    private const val LEGACY_HEADER = "DroidFinder shortcut v1\n"

    fun write(file: File, target: String) {
        require(target.isNotBlank())
        file.writeText(HEADER + Base64.getEncoder().encodeToString(target.toByteArray()))
    }

    fun read(input: InputStream): String {
        val bytes = input.use { source ->
            val buffer = ByteArray(8193)
            var size = 0
            while (size < buffer.size) {
                val n = source.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            check(size <= 8192) { "This shortcut is too large." }
            buffer.copyOf(size)
        }
        val text = bytes.toString(Charsets.UTF_8)
        val header = listOf(HEADER, LEGACY_HEADER).firstOrNull(text::startsWith)
        require(header != null) { "This isn't a Findroid shortcut." }
        return Base64.getDecoder()
            .decode(text.removePrefix(header).trim())
            .toString(Charsets.UTF_8)
            .also {
                require(it.isNotBlank() && '\u0000' !in it) { "The shortcut target is invalid." }
            }
    }
}
