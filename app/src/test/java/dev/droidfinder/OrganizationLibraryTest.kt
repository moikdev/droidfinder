package dev.droidfinder

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrganizationLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(name: String = "library") = TagStore(File(temporary.root, name))

    private fun rejects(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    @Test
    fun virtualLocationsAreAtomicEvenWhenTagNamesContainSlashes() {
        val routes = listOf("@tag:Work" to "@tag:Job")
        assertEquals("@tag:Job", remapPath("@tag:Work", routes))
        assertEquals("@tag:Work/Ideas", remapPath("@tag:Work/Ideas", routes))
        assertFalse(pathWithin("@collection:id/child", "@collection:id"))
        assertTrue(pathWithin("/Work/Ideas", "/Work"))
    }

    @Test
    fun collectionsRoundTripWithoutTouchingReferencedFiles() {
        val file = File(temporary.root, "library")
        val first = TagStore(file)
        val id =
            first.createCollection(
                "Research",
                "Green",
                listOf("/missing/A.txt", "/missing/A.txt", "content://provider/document/42"),
            )
        val second = TagStore(file)
        assertNull(second.error)
        assertEquals(
            FinderCollection(
                id,
                "Research",
                "Green",
                listOf("/missing/A.txt", "content://provider/document/42"),
            ),
            second.collections.single(),
        )
        assertFalse(File("/missing/A.txt").exists())
    }

    @Test
    fun collectionNamesAndIdsStayDistinctAndColorEditsPreserveMembership() {
        val value = store()
        val id = value.createCollection("A", "Blue", listOf("/a"))
        rejects { value.createCollection("a", "Red", emptyList()) }
        value.updateCollection(id, "Travel", "Purple")
        assertEquals(listOf("/a"), value.collections.single().paths)
        assertEquals(id, value.collections.single().id)
        rejects { value.updateCollection(id, "  ", "Blue") }
        rejects { value.updateCollection(id, "Test", "Unknown") }
    }

    @Test
    fun membershipIsDeduplicatedAndRemovalNeverRemovesTags() {
        val value = store()
        value.assign(listOf("/a"), "Blue")
        val id = value.createCollection("Saved", "Blue", listOf("/a"))
        value.collectionItems(id, listOf("/a", "/b"), true)
        value.collectionItems(id, listOf("/a"), false)
        assertEquals(listOf("/b"), value.collections.single().paths)
        assertEquals(setOf("Blue"), value.assignments["/a"])
        value.deleteCollection(id)
        assertEquals(setOf("Blue"), value.assignments["/a"])
    }

    @Test
    fun movesAndSwapsRemapReferencesSimultaneouslyIncludingProviderChildren() {
        val value = store()
        val a = "content://p/tree/root/document/root%2FA"
        val b = "content://p/tree/root/document/root%2FB"
        value.createCollection("Set", "Blue", listOf("/a/X", "/b/Y", "$a%2Fnote"))
        value.relocate(listOf("/a" to "/b", "/b" to "/a", a to b))
        assertEquals(listOf("/b/X", "/a/Y", "$b%2Fnote"), value.collections.single().paths)
    }

    @Test
    fun copiesCarryTagsWithoutAddingReferencesToCollections() {
        val value = store()
        value.assign(listOf("/a"), "Red")
        value.createCollection("Set", "Blue", listOf("/a"))
        value.relocate(listOf("/a" to "/copy"), copy = true)
        assertEquals(setOf("Red"), value.assignments["/copy"])
        assertEquals(listOf("/a"), value.collections.single().paths)
    }

    @Test
    fun tagHistoryGuardsPreserveUnrelatedCollections() {
        val value = store()
        val before = value.snapshot().tags
        value.assign(listOf("/a"), "Blue")
        val after = value.snapshot().tags
        value.createCollection("Set", "Green", listOf("/a"))
        value.restoreTags(after, before)
        assertTrue(value.assignments.isEmpty())
        assertEquals("Set", value.collections.single().name)
        value.restoreTags(before, after)
        value.assign(listOf("/b"), "Red")
        rejects { value.restoreTags(after, before) }
        assertEquals(setOf("Red"), value.assignments["/b"])
    }

    @Test
    fun collectionHistoryGuardsPreserveTagsAndRefuseChangedMembership() {
        val value = store()
        val before = value.collections
        val id = value.createCollection("Set", "Blue", listOf("/a"))
        val after = value.collections
        value.assign(listOf("/a"), "Purple")
        value.restoreCollections(after, before)
        assertEquals(setOf("Purple"), value.assignments["/a"])
        value.restoreCollections(before, after)
        value.collectionItems(id, listOf("/b"), true)
        rejects { value.restoreCollections(after, before) }
    }

    @Test
    fun deletionReviewsRefuseNewAssignmentsOrReferences() {
        val value = store()
        val tags = value.snapshot().tags
        value.assign(listOf("/a"), "Blue")
        rejects { value.remove("Blue", tags) }
        val id = value.createCollection("Set", "Blue", listOf("/a"))
        val before = value.collections.single()
        value.collectionItems(id, listOf("/b"), true)
        rejects { value.deleteCollection(id, before) }
        assertEquals(2, value.collections.single().paths.size)
    }

    @Test
    fun legacyCatalogMigratesOnSuccessfulWriteAndKeepsEmptyCatalogsEmpty() {
        val file = File(temporary.root, "library")
        Properties()
            .apply {
                setProperty("catalog", "1")
                setProperty("definition:Client", "Green")
                setProperty("item:/a", "Client")
            }
            .let { p -> file.outputStream().use { p.store(it, "legacy") } }
        val value = TagStore(file)
        assertEquals(setOf("Client"), value.assignments["/a"])
        value.createCollection("Set", "Blue", listOf("/a"))
        val properties = Properties().apply { file.inputStream().use(::load) }
        assertEquals("2", properties.getProperty("catalog"))
        value.remove("Client")
        assertTrue(TagStore(file).definitions.isEmpty())
        assertEquals("Set", TagStore(file).collections.single().name)
    }

    @Test
    fun corruptionIsPreservedAndLastGoodStateCannotBeOverwritten() {
        val file = File(temporary.root, "library")
        val value = TagStore(file)
        value.assign(listOf("/a"), "Blue")
        val good = file.readBytes()
        val snapshot = value.snapshot()
        file.writeText("catalog=999\nitem:/lost=Blue\n")
        val damaged = file.readBytes()
        value.reload()
        assertNotNull(value.error)
        assertEquals(snapshot, value.snapshot())
        rejects { value.createCollection("Set", "Blue", emptyList()) }
        assertArrayEquals(damaged, file.readBytes())
        file.writeBytes(good)
        value.reload()
        assertNull(value.error)
    }

    @Test
    fun malformedIncompleteAndOversizedCatalogsRemainUntouched() {
        val file = File(temporary.root, "library")
        fun encoded(vararg fields: Pair<String, String>): String =
            ByteArrayOutputStream()
                .also { output ->
                    Properties()
                        .apply { fields.forEach { setProperty(it.first, it.second) } }
                        .store(output, "damaged fixture")
                }
                .toString(Charsets.ISO_8859_1.name())
        for (text in
            listOf(
                "",
                encoded("catalog" to "2", "collection:id:name" to "Set"),
                encoded("catalog" to "1", "definition:Blue" to "Blue", "item:/a" to "Missing"),
                encoded("catalog" to "2", "unknown" to "value"),
                encoded(
                    "catalog" to "2",
                    "collection:x:count" to "10001",
                    "collection:x:name" to "Set",
                    "collection:x:color" to "Blue",
                ),
            )) {
            file.writeText(text)
            val value = TagStore(file)
            assertNotNull(text, value.error)
            rejects { value.define("New", "Red") }
            assertEquals(text, file.readText())
        }
        file.outputStream().use { it.write(ByteArray(16 * 1024 * 1024 + 1) { 65 }) }
        assertNotNull(TagStore(file).error)
        assertEquals(16 * 1024 * 1024 + 1L, file.length())
    }

    @Test
    fun writesFromTwoInstancesReloadAndSerializeBeforePublication() {
        val a = store()
        val b = store()
        val go = CountDownLatch(1)
        val failed = AtomicReference<Throwable?>()
        val threads =
            listOf(a to "One", b to "Two").map { (value, name) ->
                Thread {
                    try {
                        go.await()
                        value.define(name, "Blue")
                    } catch (e: Throwable) {
                        failed.set(e)
                    }
                }
                    .apply { start() }
            }
        go.countDown()
        threads.forEach {
            it.join(5000)
            assertFalse(it.isAlive)
        }
        failed.get()?.let { throw AssertionError(it) }
        val result = store()
        assertTrue(result.definitions.any { it.name == "One" })
        assertTrue(result.definitions.any { it.name == "Two" })
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun reviewedChangesPreserveMixedAssignmentsAndUntouchedTags() {
        val changes =
            mapOf("Blue" to TagChange.KEEP, "Green" to TagChange.ADD, "Red" to TagChange.REMOVE)
        assertEquals(
            setOf("Blue", "Custom", "Green"),
            reviewedTags(setOf("Blue", "Custom", "Red"), changes),
        )
        assertEquals(setOf("Green"), reviewedTags(emptySet(), changes))
        assertEquals(setOf("Green"), reviewedTags(setOf("Blue"), changes, clear = true))
    }

    @Test
    fun namesRejectCanonicalCaseCollisionsInvalidUnicodeAndControlCharacters() {
        val value = store()
        value.define("Café", "Blue")
        rejects { value.define("CAFE\u0301", "Red") }
        rejects { value.define("Bad\nTag", "Blue") }
        rejects { value.define("\uD800", "Blue") }
        rejects { value.createCollection("x".repeat(65), "Blue", emptyList()) }
        rejects { value.createCollection("Set", "Blue", listOf("@recent")) }
        rejects { value.assign(listOf("/bad\u0000name"), "Blue") }
    }

    @Test
    fun boundsFailBeforePublishingOrChangingMemory() {
        val value = store()
        val initial = value.snapshot()
        rejects { value.createCollection("Set", "Blue", List(10_001) { "/$it" }) }
        assertEquals(initial, value.snapshot())
        repeat(128) { value.createCollection("Set $it", "Blue", emptyList()) }
        val full = value.snapshot()
        rejects { value.createCollection("Extra", "Blue", emptyList()) }
        assertEquals(full, value.snapshot())
    }

    @Test
    fun encodedByteLimitsStopOversizedWritesWithoutExposingPartialState() {
        val value = store()
        val initial = value.snapshot()
        rejects {
            value.createCollection("Large", "Blue", List(10_000) { "/${"界".repeat(300)}/$it" })
        }
        assertEquals(initial, value.snapshot())
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun exportsContainMetadataAndRelativeWorkspaceLocationsWithoutContents() {
        val value = store()
        value.assign(
            listOf("/workspace/A.txt", "/outside/B.txt", "content://p/document/42"),
            "Blue",
        )
        value.createCollection(
            "Reading",
            "Purple",
            listOf("/workspace/A.txt", "content://p/document/42"),
        )
        val export = LibraryExport(value.snapshot(), "/workspace", created = "2026-11-01T00:00:00Z")
        val root = JsonParser.parseString(export.json()).asJsonObject
        assertEquals("dev.droidfinder.organization", root["format"].asString)
        assertEquals(1, root["version"].asInt)
        val entries =
            root["assignments"].asJsonArray.map { it.asJsonObject["location"].asJsonObject }
        assertTrue(
            entries.any { it["type"].asString == "workspace" && it["path"].asString == "A.txt" },
        )
        assertTrue(
            entries.any {
                it["type"].asString == "provider" && it["uri"].asString == "content://p/document/42"
            },
        )
        assertFalse(export.json().contains("fileContents"))
        assertEquals(3, export.locations.size)
    }

    @Test
    fun exportsRecognizeBothWorkspaceAliasAndCanonicalLocations() {
        val workspace = temporary.newFolder("Workspace").canonicalFile
        val alias = File(temporary.root, "Workspace alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), workspace.toPath())
        val paths = listOf(File(alias, "A.txt").path, File(workspace, "Folder/B.txt").path)
        val value = store()
        value.assign(paths, "Blue")
        value.createCollection("Reading", "Blue", paths)
        val root =
            JsonParser.parseString(LibraryExport(value.snapshot(), alias.path).json()).asJsonObject
        val locations =
            root["assignments"].asJsonArray.map { it.asJsonObject["location"].asJsonObject } +
                root["collections"].asJsonArray.single().asJsonObject["items"].asJsonArray.map {
                    it.asJsonObject
                }
        assertTrue(locations.all { it["type"].asString == "workspace" })
        assertEquals(setOf("A.txt", "Folder/B.txt"), locations.map { it["path"].asString }.toSet())
        assertTrue(workspace.listFiles()!!.isEmpty())
    }

    @Test
    fun selectionExportsExcludeUnrelatedNamesPathsAndCollectionMembers() {
        val value = store()
        value.define("Secret category", "Red")
        value.assign(listOf("/workspace/A"), "Blue")
        value.assign(listOf("/other/secret"), "Secret category")
        value.createCollection("Reading", "Blue", listOf("/workspace/A", "/other/secret"))
        val export =
            LibraryExport(
                value.snapshot(),
                "/workspace",
                setOf("/workspace/A", "/workspace/Untagged"),
            )
        val json = export.json()
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("Secret category"))
        assertEquals(2, export.assignments.size)
        assertEquals(1, export.definitions.size)
        assertEquals(listOf("/workspace/A"), export.collections.single().paths)
    }
}
