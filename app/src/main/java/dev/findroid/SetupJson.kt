package dev.findroid

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Bound bytes and JSON structure before constructing Android's permissive JSON objects. */
internal object SetupJsonTree {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun read(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "Setup backups must be 32 MiB or smaller." }
            output.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }
    fun parse(text: String): JsonElement = consume(text, build = true)
    fun validate(text: String) { consume(text, build = false) }
    private fun consume(text: String, build: Boolean): JsonElement {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Setup backups must be 32 MiB or smaller." }
        var nodes = 0
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        fun value(depth: Int): JsonElement {
            require(depth <= 48 && ++nodes <= 1_500_000) { "This backup has too many nested fields or entries." }
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val result = if (build) JsonObject() else null
                    val names = mutableSetOf<String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(build || name.length <= 256) { "A backup field name is too long." }
                        require(names.add(name)) { "Repeated backup field: ${name.take(60)}." }
                        val child = value(depth + 1)
                        result?.add(name, child)
                    }
                    reader.endObject()
                    result ?: JsonNull.INSTANCE
                }
                JsonToken.BEGIN_ARRAY -> {
                    val result = if (build) JsonArray() else null
                    reader.beginArray()
                    while (reader.hasNext()) { val child = value(depth + 1); result?.add(child) }
                    reader.endArray()
                    result ?: JsonNull.INSTANCE
                }
                JsonToken.STRING -> reader.nextString().let {
                    require(build || it.length <= 32_768) { "A saved backup value is too long." }
                    if (build) JsonPrimitive(it) else JsonNull.INSTANCE
                }
                JsonToken.NUMBER -> reader.nextString().toBigDecimal().let { if (build) JsonPrimitive(it) else JsonNull.INSTANCE }
                JsonToken.BOOLEAN -> reader.nextBoolean().let { if (build) JsonPrimitive(it) else JsonNull.INSTANCE }
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> error("Invalid setup JSON.")
            }
        }
        return reader.use {
            val result = value(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after the backup." }
            result
        }
    }

}

internal fun SetupSnapshot.checked(): SetupSnapshot = apply {
    sidebar.checked()
    validateOrganizationSnapshot(organization)
    require(searches.size <= 1000 && searches.map { it.id }.distinct().size == searches.size) { "This backup has too many or repeated Smart Folders." }
    searches.forEach { setupUuid(it.id); libraryName(it.name, 128); checkSetupRules(it.rules) }
    require(workspaces.size <= 24 && workspaces.map { it.id }.distinct().size == workspaces.size) { "Keep at most 24 distinct saved workspaces. Deselect open tabs or saved workspaces and review again." }
    require(workspaces.map { setupNameKey(it.name) }.distinct().size == workspaces.size) { "Saved workspace names must be unique." }
    (workspaces + listOfNotNull(openTabs)).forEach { workspace ->
        require(workspace.id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid workspace ID." }
        libraryName(workspace.name, 60)
        require(workspace.ratio.isFinite() && workspace.ratio in .3f.. .7f && workspace.activePane in setOf("primary", "secondary"))
        listOf(workspace.primary, workspace.secondary).forEach { pane ->
            require(pane.tabs.size in 1..64 && pane.active in pane.tabs.indices) { "Invalid saved tab count or active tab." }
            require(pane.tabs.map { it.id }.distinct().size == pane.tabs.size) { "Repeated saved tab ID." }
            require(pane.windowsLayout in 0..9 && pane.iconSize in 90..200 && pane.columnWidth in 150..500 && pane.previewWidth in 200..480)
            require(pane.columns == pane.columns.normalized())
            pane.defaults?.let { require(FolderViewJson.view(FolderViewJson.view(it)) == it) { "Invalid pane view defaults." } }
            pane.tabs.forEach { tab ->
                require(tab.id.length in 1..128 && tab.history.size in 1..100 && tab.index in tab.history.indices)
                require(tab.search.length <= 8192)
                checkSetupRules(tab.rules)
                tab.history.forEach(::checkSetupLocation)
                val n = tab.navigation
                require(n.selected.size <= 50_000 && n.expanded.size <= 1000 && n.positions.size <= 32 && n.budgets.size <= 32)
                n.positions.forEach { (key, anchor) ->
                    require(anchor.index >= 0 && anchor.offset in 0..100_000 && key.search.length <= 32_768)
                }
                n.budgets.forEach { (key, budget) -> checkSetupRules(key.rules); require(budget == budget.normalized()) }
            }
        }
    }
    require(folderViews.size <= 2000 && folderStyles.size <= 10_000) { "This backup has too many saved folder views or styles." }
    require((folderViews.keys + folderStyles.keys).all { setupLocalPath(it) || it.startsWith("content://") }) { "Folder appearance needs a file-system or provider location." }
    folderViews.values.forEach { require(FolderViewJson.view(FolderViewJson.view(it)) == it) { "Invalid saved folder view." } }
    folderStyles.values.forEach { require(it.color in setOf("Blue", "Red", "Orange", "Yellow", "Green", "Purple", "Gray") && it.symbol.length <= 32 && it.symbol.none(Char::isISOControl)) { "Invalid folder appearance." } }
    require(presets.size <= 32 && presets.map { it.id }.distinct().size == presets.size && presets.map { setupNameKey(it.name) }.distinct().size == presets.size) { "Keep at most 32 distinct rename presets." }
    presets.forEach { setupUuid(it.id); libraryName(it.name); it.recipe.validateShape() }
    preferences.checked()
    val paths = locations()
    require(paths.size <= 200_000) { "This backup contains too many saved references." }
    paths.forEach(::checkSetupLocation)
    searches.forEach { require(!it.root.startsWith("@")) { "A Smart Folder needs a storage root." } }
}

private fun setupUuid(value: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)) { "Invalid identifier in this setup backup." }
}

private fun checkSetupLocation(path: String) {
    require(path.length in 1..8192 && '\u0000' !in path && Charsets.UTF_8.newEncoder().canEncode(path)) { "Invalid saved location." }
    require(setupLocalPath(path) || (path.startsWith("content://") && java.net.URI(path).authority?.isNotBlank() == true) ||
        path in setOf("@recent", "@trash") || path.startsWith("@tag:") || path.startsWith("@collection:") || path.startsWith("@smart:")) { "Unsupported saved location in this backup." }
}

private fun checkSetupRules(rules: SearchRules) {
    require(rules.name.length <= 8192 && rules.content.length <= 8192 && rules.nameTerms.size <= 100 && rules.tags.size <= 256 && rules.excludedFolderNames.size <= 1000 && rules.excludedLocations.size <= 100) { "Saved search rules are too large." }
    require(rules.days >= 0 && rules.minimumBytes >= 0 && rules.maximumBytes >= 0)
    require(rules.excludedLocations.map { it.path }.distinct().size == rules.excludedLocations.size) { "Repeated search exclusion." }
    rules.excludedLocations.forEach { checkSetupLocation(it.path); require(!it.path.startsWith("@")) { "Choose a storage location for a search exclusion." } }
    require((rules.nameTerms + rules.tags + rules.excludedFolderNames + rules.tag).all { it.length <= 8192 && '\u0000' !in it })
}

internal object SetupJson {
    fun encode(value: SetupArchive): String {
        value.snapshot.checked()
        val text = json(value).toString(2) + "\n"
        require(text.toByteArray(Charsets.UTF_8).size <= SetupJsonTree.MAX_BYTES) { "This setup exceeds 32 MiB. Reduce saved metadata before exporting." }
        return text
    }

    fun decode(text: String): SetupArchive = try {
        decodeValidated(text)
    } catch (failure: org.json.JSONException) {
        throw IllegalArgumentException("Choose a valid Findroid setup backup. This file is incomplete or damaged.", failure)
    }

    private fun decodeValidated(text: String): SetupArchive {
        SetupJsonTree.validate(text)
        val root = JSONObject(text)
        require(root.getString("format") == "dev.findroid.setup") { "Choose a Findroid setup backup. Organization-only exports use a different format." }
        require(root.getInt("version") == 1) { "This setup needs a newer Findroid version. The current setup has been kept." }
        val roots = root.array("roots", 256) { v -> SetupRoot(v.getString("id"), v.getString("name"), v.getString("source"), SetupRootKind.valueOf(v.getString("kind"))) }
        require(roots.isNotEmpty() && roots.map { it.id }.distinct().size == roots.size) { "Invalid storage roots." }
        roots.forEach { r ->
            require(r.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
            libraryName(r.name, 128); checkSetupLocation(r.source)
            require(!r.source.startsWith("@") && (r.kind == SetupRootKind.PROVIDER) == r.source.startsWith("content://"))
        }
        val references = root.array("references", 200_000) { v ->
            SetupReference(v.getString("path"), v.getString("root"), if (v.isNull("segments")) null else v.getJSONArray("segments").strings(128))
        }
        require(references.map { it.path }.distinct().size == references.size) { "Repeated saved reference." }
        val byId = roots.associateBy { it.id }
        references.forEach { ref ->
            checkSetupLocation(ref.path)
            val source = byId[ref.root] ?: error("A saved reference has no root.")
            ref.segments?.let { segments ->
                require(setupSegments(segments)) { "Unsafe relative location in this backup." }
                if (source.kind != SetupRootKind.PROVIDER) {
                    require(Paths.get(source.source, *segments.toTypedArray()).normalize().toString() == ref.path) { "A saved location falls outside its recorded root." }
                }
            }
            require((source.kind == SetupRootKind.PROVIDER) == ref.path.startsWith("content://")) { "A saved reference uses the wrong root type." }
            if (source.kind != SetupRootKind.PROVIDER) require(ref.segments != null) { "A local reference needs a relative location." }
        }
        val result = SetupArchive(root.getString("id"), root.getString("created"), root.getString("sourceVersion"), roots, references, snapshot(root.getJSONObject("setup")))
        setupUuid(result.id); Instant.parse(result.created)
        require(result.sourceVersion.length in 1..80)
        result.snapshot.checked()
        require(references.map { it.path }.toSet() == result.snapshot.locations()) { "The backup’s reference inventory does not match its setup." }
        require(sameJson(json(result), root)) { "This backup has unsupported, missing, or invalid fields. The current setup has been kept." }
        return result
    }

    // Compare the canonical schema with the parsed input without retaining two extra Gson trees.
    // This catches unknown fields and Android JSON type coercions, clamping and truncation.
    private fun sameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            val keys = a.keys().asSequence().toSet()
            keys == b.keys().asSequence().toSet() && keys.all { sameJson(a.get(it), b.get(it)) }
        }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toString().toBigDecimal().compareTo(b.toString().toBigDecimal()) == 0
        else -> a == b
    }

    private fun json(value: SetupArchive) = JSONObject()
        .put("format", "dev.findroid.setup").put("version", 1).put("id", value.id)
        .put("created", value.created).put("sourceVersion", value.sourceVersion)
        .put("roots", JSONArray(value.roots.map { JSONObject().put("id", it.id).put("name", it.name).put("source", it.source).put("kind", it.kind.name) }))
        .put("references", JSONArray(value.references.map { JSONObject().put("path", it.path).put("root", it.root).put("segments", it.segments?.let(::JSONArray) ?: JSONObject.NULL) }))
        .put("setup", snapshot(value.snapshot))

    internal fun searches(values: List<SmartFolder>) = JSONArray(values.map {
        JSONObject().put("id", it.id).put("name", it.name).put("root", it.root).put("rules", NavigationJson.rules(it.rules))
    })

    private fun snapshot(value: SetupSnapshot) = JSONObject().apply {
        put("sidebar", JSONObject(SidebarJson.encode(value.sidebar)))
        put("tags", JSONArray(value.organization.tags.definitions.map { JSONObject().put("name", it.name).put("color", it.color) }))
        put("assignments", JSONArray(value.organization.tags.assignments.toSortedMap().map { (path, tags) -> JSONObject().put("path", path).put("tags", JSONArray(tags.sorted())) }))
        put("collections", JSONArray(value.organization.collections.map { JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("paths", JSONArray(it.paths)) }))
        put("searches", searches(value.searches))
        put("workspaces", JSONObject(WorkspaceJson.encode(value.workspaces)))
        put("openTabs", value.openTabs?.let { JSONObject(WorkspaceJson.encode(listOf(it))) } ?: JSONObject.NULL)
        put("folderViews", JSONArray(value.folderViews.toSortedMap().map { (path, view) -> JSONObject().put("path", path).put("view", FolderViewJson.view(view)) }))
        put("folderStyles", JSONArray(value.folderStyles.toSortedMap().map { (path, style) -> JSONObject().put("path", path).put("color", style.color).put("symbol", style.symbol) }))
        put("presets", JSONArray(value.presets.map(::preset)))
        val p = value.preferences
        put("preferences", JSONObject().put("density", p.density.name).put("extensions", p.extensions).put("dark", p.dark)
            .put("sidebarWidth", p.sidebarWidth).put("sidebarVisible", p.sidebarVisible).put("tagsVisible", p.tagsVisible)
            .put("gestureHintDismissed", p.gestureHintDismissed).put("conflictPolicy", p.conflictPolicy.name)
            .put("zip", JSONObject().put("entries", p.zip.entries).put("total", p.zip.expandedBytes).put("member", p.zip.memberBytes).put("ratio", p.zip.ratio).put("preview", p.zip.previewBytes)))
    }

    private fun snapshot(v: JSONObject): SetupSnapshot {
        val p = v.getJSONObject("preferences")
        val z = p.getJSONObject("zip")
        val views = v.array("folderViews", 2000) { it.getString("path") to FolderViewJson.view(it.getJSONObject("view")) }
        val styles = v.array("folderStyles", 10_000) { it.getString("path") to FolderStyle(it.getString("color"), it.getString("symbol")) }
        val assignments = v.array("assignments", 100_000) { it.getString("path") to it.getJSONArray("tags").strings(256).toSet() }
        require(views.map { it.first }.distinct().size == views.size && styles.map { it.first }.distinct().size == styles.size && assignments.map { it.first }.distinct().size == assignments.size) { "Repeated saved location." }
        return SetupSnapshot(
            SidebarJson.decode(v.getJSONObject("sidebar").toString()),
            OrganizationSnapshot(TagSnapshot(v.array("tags", 256) { FinderTag(it.getString("name"), it.getString("color")) }, assignments.toMap()),
                v.array("collections", 128) { FinderCollection(it.getString("id"), it.getString("name"), it.getString("color"), it.getJSONArray("paths").strings(10_000)) }),
            v.array("searches", 1000) { SmartFolder(it.getString("id"), it.getString("name"), it.getString("root"), NavigationJson.rules(it.getJSONObject("rules"))) },
            WorkspaceJson.decode(v.getJSONObject("workspaces").toString()),
            if (v.isNull("openTabs")) null else WorkspaceJson.decode(v.getJSONObject("openTabs").toString()).single(),
            views.toMap(), styles.toMap(), v.array("presets", 32, ::preset),
            SetupPreferences(FinderDensity.valueOf(p.getString("density")), p.getBoolean("extensions"), p.getBoolean("dark"), p.getInt("sidebarWidth"), p.getBoolean("sidebarVisible"), p.getBoolean("tagsVisible"), p.getBoolean("gestureHintDismissed"), ConflictPolicy.valueOf(p.getString("conflictPolicy")), ZipLimits(z.getInt("entries"), z.getLong("total"), z.getLong("member"), z.getInt("ratio"), z.getLong("preview"))),
        )
    }

    private fun preset(p: RenamePreset): JSONObject = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("order", p.order.name); put("reverse", p.reverse)
        val r = p.recipe
        put("recipe", JSONObject().put("method", r.method.name).put("part", r.part.name).put("text", r.text).put("replacement", r.replacement)
            .put("regex", r.regex).put("matchCase", r.matchCase).put("firstOnly", r.firstOnly).put("start", r.start).put("step", r.step).put("padding", r.padding)
            .put("separator", r.separator).put("template", r.template).put("letterCase", r.letterCase.name).put("normalization", r.normalization.name).put("portableConflicts", r.portableConflicts))
    }
    private fun preset(p: JSONObject): RenamePreset {
        val r = p.getJSONObject("recipe")
        return RenamePreset(p.getString("id"), p.getString("name"), RenameRecipe(RenameMethod.valueOf(r.getString("method")), RenamePart.valueOf(r.getString("part")), r.getString("text"), r.getString("replacement"), r.getBoolean("regex"), r.getBoolean("matchCase"), r.getBoolean("firstOnly"), r.getLong("start"), r.getLong("step"), r.getInt("padding"), r.getString("separator"), r.getString("template"), RenameLetterCase.valueOf(r.getString("letterCase")), RenameNormalization.valueOf(r.getString("normalization")), r.getBoolean("portableConflicts")), RenameOrder.valueOf(p.getString("order")), p.getBoolean("reverse"))
    }
    private fun <T> JSONObject.array(key: String, max: Int, read: (JSONObject) -> T): List<T> {
        val array = getJSONArray(key)
        require(array.length() <= max) { "Too many $key in this backup." }
        return List(array.length()) { read(array.getJSONObject(it)) }
    }
    private fun JSONArray.strings(max: Int): List<String> {
        require(length() <= max) { "Too many saved values in this backup." }
        return List(length()) { getString(it) }
    }
}
