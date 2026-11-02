package dev.findroid

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderMergeTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun exercise(block: Fixture.() -> Unit) {
        val id = "Merge-${System.nanoTime()}"
        val folder = File(context.filesDir, "ProviderFixtures/$id").apply { mkdirs() }
        val db = "$id.db"
        val engine = FileEngine(File(folder, "state"))
        val docs = DocumentAccess(context, engine)
        val store = SqliteTransferStore(context, db, File(folder, "legacy"))
        try {
            Fixture(folder, engine, docs, store).block()
        } finally {
            dev.findroid.testing.FixtureDocumentsProvider.beforeDelete = null
            store.close()
            context.deleteDatabase(db)
            folder.deleteRecursively()
        }
    }

    private class Fixture(
        val folder: File,
        val engine: FileEngine,
        val docs: DocumentAccess,
        val store: SqliteTransferStore,
    ) {
        val source = File(folder, "From/Project").apply { mkdirs() }
        val out = File(folder, "Out").apply { mkdirs() }
        val target = File(out, "Project").apply { mkdir() }
        val history = FileHistory(engine)
        var job = TransferJob(paths = listOf(source.path), destination = out.path, move = false)
        val control = TransferControl()
        val steps = mutableListOf<FileStep>()
        var onSave: (TransferJob) -> Unit = {}
        var metadata: (List<Pair<String, String>>, Boolean) -> Unit = { _, _ -> }
        var choose: (Entry, Entry, Boolean) -> ConflictDecision = { incoming, existing, allowed ->
            if (allowed && incoming.folder && existing.folder)
                ConflictDecision(ConflictAction.MERGE)
            else ConflictDecision(ConflictAction.SKIP)
        }

        fun file(root: File, name: String, text: String = name) =
            File(root, name).apply {
                parentFile!!.mkdirs()
                writeText(text)
            }

        fun provider(path: File): String {
            val tree =
                DocumentsContract.buildTreeDocumentUri(
                    "dev.findroid.fixtures",
                    "root/${folder.name}",
                )
            val uri =
                DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        "root/${folder.name}/${path.relativeTo(folder).invariantSeparatorsPath}",
                    )
                    .toString()
            // Listing teaches provider parent identities used by recovery and history.
            val parent = path.parentFile!!
            val parentUri =
                DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        "root/${folder.name}/${parent.relativeTo(folder).invariantSeparatorsPath}",
                    )
                    .toString()
            docs.list(parentUri)
            return uri
        }

        fun run(
            move: Boolean = false,
            sourcePath: String = source.path,
            parent: String = out.path,
        ) {
            job = TransferJob(paths = listOf(sourcePath), destination = parent, move = move)
            store.save(listOf(job))
            try {
                runBlocking {
                    TransferWorker(
                            job,
                            docs,
                            control,
                            current = { job },
                            checkpoint = {
                                job = it(job)
                                store.save(listOf(job))
                                onSave(job)
                            },
                            ask = { incoming, existing, allowed ->
                                choose(incoming, existing, allowed)
                            },
                            metadata = { routes, move -> metadata(routes, move) },
                            steps = steps,
                        )
                        .run()
                }
            } finally {
                history.record(if (move) "Move" else "Copy", steps)
            }
        }
    }

    @Test
    fun copyMergesNestedFoldersAndNewSubtreesWithRecoverableReplacement() = exercise {
        file(source, "Shared/Same.txt", "incoming")
        file(source, "Shared/New.txt")
        file(source, "New subtree/Child.txt")
        file(target, "Shared/Same.txt", "original")
        file(target, "Shared/Extra.txt", "keep")
        file(target, "Unrelated.txt", "untouched")
        choose = { a, b, allowed ->
            ConflictDecision(
                if (allowed && a.folder && b.folder) ConflictAction.MERGE
                else ConflictAction.REPLACE,
            )
        }
        var plannedBeforeWrite = false
        onSave = { snapshot ->
            if (snapshot.mergePlans.isNotEmpty() && !File(target, "New subtree").exists()) {
                assertEquals(snapshot, store.load().single())
                plannedBeforeWrite = true
            }
        }
        run()
        assertTrue(plannedBeforeWrite)
        assertEquals("incoming", File(target, "Shared/Same.txt").readText())
        assertEquals("keep", File(target, "Shared/Extra.txt").readText())
        assertEquals(3, job.mergePlans.single().members.size)
        assertEquals(setOf("Project"), out.list()!!.toSet())
        history.undo()
        assertEquals("original", File(target, "Shared/Same.txt").readText())
        assertFalse(File(target, "Shared/New.txt").exists())
        assertFalse(File(target, "New subtree").exists())
        assertEquals("untouched", File(target, "Unrelated.txt").readText())
        history.redo()
        assertEquals("incoming", File(target, "Shared/Same.txt").readText())
        assertTrue(File(target, "New subtree/Child.txt").exists())
        File(target, "Shared/New.txt").writeText("later edit")
        assertTrue(runCatching { history.undo() }.isFailure)
        assertEquals("later edit", File(target, "Shared/New.txt").readText())
        assertEquals("incoming", File(target, "Shared/Same.txt").readText())
    }

    private fun completeMove(providerSource: Boolean, providerTarget: Boolean) = exercise {
        file(source, "Shared/A.txt", "A")
        file(source, "New/B.txt", "B")
        File(source, "Empty").mkdir()
        file(target, "Shared/Extra.txt", "extra")
        val from = if (providerSource) provider(source) else source.path
        val parent = if (providerTarget) provider(out) else out.path
        run(true, from, parent)
        assertFalse(source.exists())
        assertEquals(SourceCleanup.RECOVERED, job.mergePlans.single().cleanup)
        assertNotNull(job.mergePlans.single().recovery)
        assertEquals(job, store.load().single())
        assertEquals("A", File(target, "Shared/A.txt").readText())
        assertEquals("B", File(target, "New/B.txt").readText())
        assertTrue(File(target, "Empty").isDirectory)
        history.undo()
        assertEquals("A", File(source, "Shared/A.txt").readText())
        assertEquals("B", File(source, "New/B.txt").readText())
        assertTrue(File(source, "Empty").isDirectory)
        assertFalse(File(target, "Shared/A.txt").exists())
        assertFalse(File(target, "New").exists())
        assertEquals("extra", File(target, "Shared/Extra.txt").readText())
        history.redo()
        assertFalse(source.exists())
        assertEquals("A", File(target, "Shared/A.txt").readText())
        assertEquals("B", File(target, "New/B.txt").readText())
    }

    @Test fun localMoveMergeCanUndoAndRedo() = completeMove(false, false)

    @Test fun localToProviderMoveMergeCanUndoAndRedo() = completeMove(false, true)

    @Test fun providerToLocalMoveMergeCanUndoAndRedo() = completeMove(true, false)

    @Test fun providerToProviderMoveMergeCanUndoAndRedo() = completeMove(true, true)

    @Test
    fun skippedMoveMembersStayAtSourceWhileCopiedMembersCanUndoAndRedo() = exercise {
        file(source, "Shared/Skip.txt", "incoming")
        file(source, "Shared/New.txt", "new")
        file(target, "Shared/Skip.txt", "original")
        run(true)
        assertEquals("incoming", File(source, "Shared/Skip.txt").readText())
        assertFalse(File(source, "Shared/New.txt").exists())
        assertEquals("original", File(target, "Shared/Skip.txt").readText())
        assertEquals("new", File(target, "Shared/New.txt").readText())
        assertTrue(job.retryTargets.isEmpty())
        history.undo()
        assertEquals("new", File(source, "Shared/New.txt").readText())
        assertFalse(File(target, "Shared/New.txt").exists())
        history.redo()
        assertFalse(File(source, "Shared/New.txt").exists())
        assertEquals("new", File(target, "Shared/New.txt").readText())
    }

    @Test
    fun cancellationKeepsSourcesAndRetriesOnlyUnpublishedPlannedUnits() = exercise {
        file(source, "Shared/A.txt")
        file(source, "Shared/B.txt")
        File(target, "Shared").mkdir()
        onSave = { snapshot ->
            if (snapshot.mergePlans.any { p -> p.members.any { it.destination != null } })
                control.cancel()
        }
        assertTrue(runCatching { run(true) }.exceptionOrNull() is CancellationException)
        val plan = job.mergePlans.single()
        val published = plan.members.single { it.destination != null }
        val pending = plan.members.single { it.destination == null }
        assertEquals(listOf(TransferRetryTarget(pending.source, pending.parent)), job.retryTargets)
        assertTrue(File(published.source).exists())
        assertTrue(File(published.destination!!).exists())
        assertTrue(source.exists())
        assertEquals(job, store.load().single())
        history.undo()
        assertFalse(File(published.destination).exists())
        assertTrue(File(published.source).exists())
    }

    @Test
    fun sourceEditsDuringCopyPreventMoveCleanup() = exercise {
        file(source, "A.txt", "before")
        var edited = false
        onSave = { snapshot ->
            if (
                !edited && snapshot.mergePlans.any { p -> p.members.any { it.destination != null } }
            ) {
                edited = true
                File(source, "A.txt").writeText("external edit")
            }
        }
        run(true)
        assertEquals("external edit", File(source, "A.txt").readText())
        assertEquals("before", File(target, "A.txt").readText())
        assertEquals(SourceCleanup.KEPT, job.mergePlans.single().cleanup)
        assertTrue(job.results.single().error!!.contains("source changed"))
        assertTrue(job.retryTargets.isEmpty())
        history.undo()
        assertEquals("external edit", File(source, "A.txt").readText())
        assertFalse(File(target, "A.txt").exists())
    }

    @Test
    fun emptyMoveMergePreservesExistingDestinationAndHasExplicitPlan() = exercise {
        file(target, "Extra.txt", "keep")
        run(true)
        assertTrue(job.mergePlans.single().members.isEmpty())
        assertFalse(source.exists())
        assertEquals("keep", File(target, "Extra.txt").readText())
        history.undo()
        assertTrue(source.isDirectory)
        assertTrue(source.list()!!.isEmpty())
        history.redo()
        assertFalse(source.exists())
        assertEquals("keep", File(target, "Extra.txt").readText())
    }

    @Test
    fun partialProviderRemovalKeepsRecoveryAndBlocksDestructiveUndo() = exercise {
        file(source, "A.txt", "A")
        file(source, "B.txt", "B")
        val from = provider(source)
        dev.findroid.testing.FixtureDocumentsProvider.beforeDelete = { id ->
            if (id.endsWith("/From/Project")) {
                File(source, "A.txt").delete()
                throw java.io.IOException("Simulated provider disconnect during removal")
            }
        }
        run(true, from)
        val plan = job.mergePlans.single()
        assertEquals(SourceCleanup.FAILED, plan.cleanup)
        val recovery = File(plan.recovery!!)
        assertEquals("A", File(recovery, "A.txt").readText())
        assertEquals("B", File(recovery, "B.txt").readText())
        assertFalse(File(source, "A.txt").exists())
        assertEquals("B", File(source, "B.txt").readText())
        assertTrue(job.retryTargets.isEmpty())
        assertTrue(runCatching { history.undo() }.isFailure)
        assertEquals("A", File(target, "A.txt").readText())
        assertEquals("B", File(target, "B.txt").readText())
        assertEquals(job, store.load().single())
    }

    @Test
    fun destinationEditsBeforeCleanupKeepSourceAndProtectBothCopies() = exercise {
        file(source, "A.txt", "source")
        var edited = false
        onSave = { snapshot ->
            if (
                !edited && snapshot.mergePlans.any { p -> p.members.any { it.destination != null } }
            ) {
                edited = true
                File(target, "A.txt").writeText("destination edit")
            }
        }
        run(true)
        assertEquals("source", File(source, "A.txt").readText())
        assertEquals("destination edit", File(target, "A.txt").readText())
        assertEquals(SourceCleanup.KEPT, job.mergePlans.single().cleanup)
        assertTrue(job.results.single().error!!.contains("differs"))
        assertTrue(runCatching { history.undo() }.isFailure)
        assertEquals("source", File(source, "A.txt").readText())
        assertEquals("destination edit", File(target, "A.txt").readText())
    }

    private fun ancestorReplacement(provider: Boolean) = exercise {
        val nested = File(target, "Project").apply { mkdir() }
        file(nested, "Source.txt", "source")
        file(target, "Extra.txt", "extra")
        choose = { _, _, _ -> ConflictDecision(ConflictAction.REPLACE) }
        run(
            true,
            if (provider) provider(nested) else nested.path,
            if (provider) provider(out) else out.path,
        )
        assertNotNull(job.results.single().error)
        assertEquals("source", File(nested, "Source.txt").readText())
        assertEquals("extra", File(target, "Extra.txt").readText())
        assertTrue(engine.trashItems().isEmpty())
        assertTrue(steps.isEmpty())
    }

    @Test fun replacingALocalAncestorCannotRemoveTheSource() = ancestorReplacement(false)

    @Test fun replacingAProviderAncestorCannotRemoveTheSource() = ancestorReplacement(true)

    @Test
    fun moveMergeCanReplaceFileFolderTypeConflictsAndUndoRedoBoth() = exercise {
        file(source, "Asset", "incoming file")
        file(source, "Package/Child.txt", "incoming folder")
        file(target, "Asset/Original.txt", "original folder")
        file(target, "Package", "original file")
        choose = { a, b, allowed ->
            ConflictDecision(
                if (allowed && a.folder && b.folder) ConflictAction.MERGE
                else ConflictAction.REPLACE,
            )
        }
        run(true)
        assertFalse(source.exists())
        assertEquals("incoming file", File(target, "Asset").readText())
        assertEquals("incoming folder", File(target, "Package/Child.txt").readText())
        history.undo()
        assertEquals("incoming file", File(source, "Asset").readText())
        assertEquals("incoming folder", File(source, "Package/Child.txt").readText())
        assertEquals("original folder", File(target, "Asset/Original.txt").readText())
        assertEquals("original file", File(target, "Package").readText())
        history.redo()
        assertFalse(source.exists())
        assertEquals("incoming file", File(target, "Asset").readText())
        assertEquals("incoming folder", File(target, "Package/Child.txt").readText())
    }

    private fun tagRecovery(provider: Boolean, partial: Boolean) = exercise {
        file(source, "Shared/New.txt", "new")
        File(target, "Shared").mkdir()
        if (partial) {
            file(source, "Shared/Skip.txt", "incoming")
            file(target, "Shared/Skip.txt", "original")
        }
        val from = if (provider) provider(source) else source.path
        val sourceChild =
            docs.list(docs.list(from).single().path).first { it.name == "New.txt" }.path
        val tags = TagStore(File(folder, "tags.properties"))
        tags.assign(listOf(sourceChild), "Blue")
        metadata = { routes, move -> tags.relocate(routes, copy = !move) }
        run(true, from)
        val destination = File(target, "Shared/New.txt").path
        assertEquals(setOf("Blue"), tags.assignments[organizationKey(destination)])
        assertNull(tags.assignments[organizationKey(sourceChild)])
        assertTrue(tags.assignments.keys.any { it.contains("/Trash/") })
        tags.relocate(history.undo())
        val restoredRoot = if (provider) provider(source) else source.path
        val restored =
            docs.list(docs.list(restoredRoot).single().path).first { it.name == "New.txt" }.path
        assertEquals(setOf("Blue"), tags.assignments[organizationKey(restored)])
        assertFalse(tags.assignments.keys.any { it.contains("/Trash/") })
        tags.relocate(history.redo())
        assertNull(tags.assignments[organizationKey(restored)])
        assertEquals(setOf("Blue"), tags.assignments[organizationKey(destination)])
        assertTrue(tags.assignments.keys.any { it.contains("/Trash/") })
    }

    @Test fun localMoveMergeTagsFollowSourceRecoveryAndUndoRedo() = tagRecovery(false, false)

    @Test fun providerMoveMergeTagsFollowSourceRecoveryAndUndoRedo() = tagRecovery(true, false)

    @Test
    fun partialProviderMoveMergeTagsFollowMemberRecoveryAndUndoRedo() = tagRecovery(true, true)
}
