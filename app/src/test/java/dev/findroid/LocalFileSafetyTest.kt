package dev.findroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun deletingNestedLinksNeverTouchesTheirTargets() {
        val outside = temporary.newFolder("outside")
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val tree = temporary.newFolder("tree")
        val nested = File(tree, "nested").apply { mkdir() }
        File(nested, "remove.txt").writeText("remove")
        Files.createSymbolicLink(File(nested, "directory-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(tree, "file-link").toPath(), keep.toPath())
        Files.createSymbolicLink(File(tree, "dangling").toPath(), File(outside, "missing").toPath())
        assertTrue(tree.deleteTreeWithoutLinks())
        assertFalse(tree.entryExists())
        assertEquals("keep", keep.readText())
    }

    @Test
    fun deletingLinkAsRootOnlyRemovesTheLinkAndMissingTreesAreHarmless() {
        val outside = temporary.newFolder("outside")
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val link = File(temporary.root, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(link.deleteTreeWithoutLinks())
        assertFalse(link.entryExists())
        assertEquals("keep", keep.readText())
        assertTrue(link.deleteTreeWithoutLinks())
    }

    @Test
    fun transferTraversalRejectsCyclesAndExcessDepthBeforeRecursing() {
        val repeated = TransferTraversal()
        repeated.visit("root", 0)
        assertTrue(runCatching { repeated.visit("root", 1) }.isFailure)
        val deep = TransferTraversal()
        for (i in 0..128) deep.visit("entry-$i", i)
        assertTrue(runCatching { deep.visit("too-deep", 129) }.isFailure)
    }

    @Test
    fun transferTraversalRejectsExcessEntries() {
        val traversal = TransferTraversal()
        repeat(100_000) { traversal.visit("entry-$it", 1) }
        assertTrue(runCatching { traversal.visit("too-many", 1) }.isFailure)
    }
}
