@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.*
import org.junit.Assert.*

class FilenameOrderingWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var folder: File
    private lateinit var provider: File
    private var providerRoot: String? = null
    private lateinit var baseline: FolderView
    private var extensions = true
    private val albums = listOf("Album 2", "Album 10")
    private val photos = listOf("Photo 1.png", "Photo 2.png", "Photo 02.png", "Photo 10.png", "Photo 20.png", "Photo 100.png", "写真２.png", "写真１０.png")

    @Before
    fun setup() {
        folder = File(compose.activity.filesDir, "Workspace/Sorting-${System.nanoTime()}/Photo library").apply { mkdirs() }
        provider = File(compose.activity.filesDir, "ProviderFixtures/Sorting-${System.nanoTime()}").apply { mkdirs() }
        // Deliberately create entries in the opposite order from their expected display order.
        for (parent in listOf(folder, provider)) {
            albums.reversed().forEach { name ->
                val album = File(parent, name).apply { mkdir() }
                File(album, "Chapter 10.txt").writeText("ten")
                File(album, "Chapter 2.txt").writeText("two")
            }
            photos.reversed().forEachIndexed { index, name ->
                val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(41 + index * 16, 85 + index * 9, 129 + index * 5))
                canvas.drawCircle(240f, 70f, 45f, Paint().apply { color = Color.rgb(230, 193, 121) })
                canvas.drawText(name.substringBefore('.'), 20f, 160f, Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true })
                File(parent, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            baseline = model.currentFolderView()
            extensions = model.showExtensions
            if (!model.showExtensions) model.toggleExtensions()
            model.changeGrouping(GroupBy.NONE)
            model.changeMode(ViewMode.LIST)
            if (model.sort != SortBy.NAME || !model.ascending) model.changeSort(SortBy.NAME)
            if (!model.foldersFirst) model.toggleFoldersFirst()
            model.updateSearchRules(SearchRules())
            model.navigate(folder.path)
            model.clearSelection()
        }
        loaded()
    }

    @After
    fun cleanup() {
        compose.onAllNodesWithContentDescription("Close archive").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            compose.onNodeWithContentDescription("Close archive").performClick()
        }
        compose.runOnIdle {
            model.updateSearchRules(SearchRules())
            model.navigate(model.workspace.path)
            providerRoot?.let(model::disconnectLocation)
            if (model.showExtensions != extensions) model.toggleExtensions()
            model.changeMode(baseline.mode)
            model.changeGrouping(baseline.group)
            if (model.sort != baseline.sort || model.ascending != baseline.ascending) model.changeSort(baseline.sort)
            if (model.ascending != baseline.ascending) model.changeSort(baseline.sort)
            if (model.foldersFirst != baseline.foldersFirst) model.toggleFoldersFirst()
        }
        loaded()
        folder.parentFile!!.deleteRecursively()
        provider.deleteRecursively()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun names() = model.entries.map { it.name }

    private fun capture(name: String, tag: String? = null) {
        val node = if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)
        File(compose.activity.filesDir, "$name.png").outputStream().use {
            node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun everyViewUsesNaturalOrderAndDirectionKeepsFoldersFirstAndSelection() {
        for (view in ViewMode.entries) {
            compose.runOnIdle { model.changeMode(view) }
            compose.waitForIdle()
            assertEquals(albums + photos, names())
        }
        compose.runOnIdle {
            model.changeMode(ViewMode.LIST)
            model.select(model.entries.single { it.name == "Photo 2.png" })
        }
        compose.waitForIdle()
        capture("natural-sorting-files")
        val selected = model.selected.toSet()
        compose.runOnIdle { model.changeSort(SortBy.NAME) }
        assertEquals(albums.reversed() + photos.reversed(), names())
        assertEquals(selected, model.selected)
        assertEquals("Photo 2.png", model.focused?.name)
        compose.runOnIdle { model.toggleFoldersFirst() }
        assertEquals((albums + photos).reversed(), names())
    }

    @Test
    fun connectedProviderAndChildListingsUseTheSameOrder() {
        val uri = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${provider.name}")
        compose.runOnIdle { model.connectLocation(uri, false) }
        compose.waitUntil(15_000) { !model.loading && model.location.contains(provider.name) && model.entries.size == albums.size + photos.size }
        providerRoot = model.location
        assertEquals(albums + photos, names())
        val album = model.entries.single { it.name == "Album 2" }
        // listPath supplies Columns view, disclosure rows and the destination browser.
        assertEquals(listOf("Chapter 2.txt", "Chapter 10.txt"), model.listPath(album.path).map { it.name })
        compose.runOnIdle { model.navigate(album.path) }
        loaded()
        assertEquals(listOf("Chapter 2.txt", "Chapter 10.txt"), names())
        compose.runOnIdle { model.changeSort(SortBy.NAME) }
        assertEquals(listOf("Chapter 10.txt", "Chapter 2.txt"), names())
    }

    @Test
    fun recursiveSearchKeepsDuplicateNamesInStablePathOrderAcrossRefresh() {
        compose.runOnIdle { model.updateQuery("Chapter") }
        loaded()
        val paths = model.entries.map { it.path }
        assertEquals(listOf("Chapter 2.txt", "Chapter 2.txt", "Chapter 10.txt", "Chapter 10.txt"), names())
        assertEquals(paths.sortedWith(compareBy<String, String>(NaturalFilenameOrder) { File(it).name }.thenBy { it }), paths)
        compose.runOnIdle { model.refresh() }
        loaded()
        assertEquals(paths, model.entries.map { it.path })
    }

    @Test
    fun archiveRowsFollowTheSameNaturalOrder() {
        val archive = File(folder, "Photo library.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in photos.reversed()) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(File(folder, name).readBytes())
                zip.closeEntry()
            }
        }
        compose.runOnIdle { model.refresh() }
        loaded()
        compose.onNode(hasText(archive.name) and hasAnyAncestor(hasTestTag("file-browser"))).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("archive-items").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Photo 2.png").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val visible = photos.take(3).map { name ->
            compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("archive-items")))
                .fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(visible.sorted(), visible)
        capture("natural-sorting-archive", "archive-browser")
        compose.onNodeWithContentDescription("Close archive").performClick()
    }
}
