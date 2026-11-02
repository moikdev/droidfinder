package dev.findroid

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FolderComparisonTest {
    private val nodes = mutableMapOf<String, ComparisonNode>()
    private val children = mutableMapOf<String, List<ComparisonNode>>()
    private val hashes = mutableMapOf<String, String>()

    private fun node(
        path: String,
        folder: Boolean = false,
        bytes: Long? = 4,
        link: Boolean = false,
    ): ComparisonNode =
        ComparisonNode(path, path.substringAfterLast('/'), folder, bytes, 1, link).also {
            nodes[path] = it
        }

    private fun roots(): Pair<ComparisonNode, ComparisonNode> = node("L", true) to node("R", true)

    private fun compare(verify: Boolean = false, limit: Int = 20_000) =
        compareFolders(
            "L",
            "R",
            { nodes.getValue(it) },
            { children[it].orEmpty() },
            { path, _ -> hashes.getValue(path) },
            verify,
            limit = limit,
        )

    @Test
    fun matchingMetadataRequiresExplicitContentVerification() {
        roots()
        children["L"] = listOf(node("L/a"))
        children["R"] = listOf(node("R/a"))
        assertEquals(ComparisonState.UNVERIFIED, compare().rows.single().state)
        hashes["L/a"] = "one"
        hashes["R/a"] = "two"
        assertEquals(ComparisonState.CHANGED, compare(true).rows.single().state)
        hashes["R/a"] = "one"
        assertEquals(ComparisonState.IDENTICAL, compare(true).rows.single().state)
    }

    @Test
    fun recursivelyPairsMissingTypeSizeAndCaseSensitiveNames() {
        roots()
        children["L"] =
            listOf(
                node("L/Folder", true),
                node("L/type", true),
                node("L/size", bytes = 3),
                node("L/Case"),
            )
        children["R"] =
            listOf(
                node("R/Folder", true),
                node("R/type"),
                node("R/size", bytes = 4),
                node("R/case"),
            )
        children["L/Folder"] = listOf(node("L/Folder/Left"))
        children["R/Folder"] = listOf(node("R/Folder/Right"))
        val rows = compare().rows.associate { it.relative to it.state }
        assertEquals(ComparisonState.TYPE_CHANGED, rows["type"])
        assertEquals(ComparisonState.SIZE_CHANGED, rows["size"])
        assertEquals(ComparisonState.ONLY_LEFT, rows["Case"])
        assertEquals(ComparisonState.ONLY_RIGHT, rows["case"])
        assertEquals(ComparisonState.ONLY_LEFT, rows["Folder/Left"])
        assertEquals(ComparisonState.ONLY_RIGHT, rows["Folder/Right"])
    }

    @Test
    fun unknownSizesCanBeVerifiedButUnreadableAndChangingFilesStayUnknown() {
        roots()
        children["L"] = listOf(node("L/a", bytes = null))
        children["R"] = listOf(node("R/a", bytes = null))
        assertEquals(ComparisonState.UNVERIFIED, compare().rows.single().state)
        hashes["L/a"] = "same"
        hashes["R/a"] = "same"
        assertEquals(ComparisonState.IDENTICAL, compare(true).rows.single().state)
        val changed =
            compareFolders(
                "L",
                "R",
                { nodes.getValue(it) },
                { children[it].orEmpty() },
                { path, _ ->
                    nodes[path] = nodes.getValue(path).copy(modified = 2)
                    "same"
                },
                true,
            )
        assertEquals(ComparisonState.UNAVAILABLE, changed.rows.single().state)
        val denied =
            compareFolders(
                "L",
                "R",
                { nodes.getValue(it) },
                { children[it].orEmpty() },
                { _, _ -> error("Disconnected") },
                true,
            )
        assertTrue(denied.incomplete)
    }

    @Test
    fun limitsDuplicateNamesLinksAndCyclesNeverPretendToBeComplete() {
        roots()
        children["L"] = listOf(node("L/a"), node("L/b"))
        children["R"] = emptyList()
        assertTrue(compare(limit = 1).limited)
        children["L"] = listOf(node("L/a"), node("L/a"))
        assertTrue(compare().incomplete)
        children["L"] = listOf(node("L/link", true, link = true))
        assertTrue(compare().incomplete)
        children["L"] = listOf(nodes.getValue("L"))
        assertTrue(compare().incomplete)
    }

    @Test
    fun boundedIteratorAndCancellation() {
        roots()
        var consumed = 0
        val result =
            compareFolders(
                "L",
                "R",
                { nodes.getValue(it) },
                {
                    generateSequence {
                        consumed++
                        ComparisonNode("$it/$consumed", "$consumed", false, 0, 0)
                    }
                        .asIterable()
                },
                { _, _ -> "" },
                limit = 10,
            )
        assertTrue(result.limited)
        assertEquals(10, consumed)
        val control = TransferControl()
        control.cancel()
        try {
            compareFolders(
                "L",
                "R",
                { nodes.getValue(it) },
                { emptyList() },
                { _, _ -> "" },
                control = control,
            )
            fail("cancelled")
        } catch (_: CancellationException) {}
    }
}
