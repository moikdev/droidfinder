package dev.droidfinder

import androidx.compose.ui.input.key.Key
import org.junit.Assert.*
import org.junit.Test

class ExplorerShortcutsTest {
    @Test
    fun ctrlUsesWindowsAndMetaRetainsFinderAliases() {
        assertEquals("trash", explorerShortcut(Key.D, ctrl = true)!!.action)
        assertEquals("duplicate", explorerShortcut(Key.D, meta = true)!!.action)
        assertEquals("selectTab", explorerShortcut(Key.Two, ctrl = true)!!.action)
        assertEquals("finderView", explorerShortcut(Key.Two, meta = true)!!.action)
    }

    @Test
    fun windowFolderAndTabCommandsAreDistinct() {
        assertEquals("newWindow", explorerShortcut(Key.N, ctrl = true)!!.action)
        assertEquals("new", explorerShortcut(Key.N, ctrl = true, shift = true)!!.action)
        assertEquals("newTab", explorerShortcut(Key.T, ctrl = true)!!.action)
        assertEquals("reopenTab", explorerShortcut(Key.T, ctrl = true, shift = true)!!.action)
        assertEquals("closeWindow", explorerShortcut(Key.F4, alt = true)!!.action)
        assertEquals("closeTab", explorerShortcut(Key.W, ctrl = true)!!.action)
    }

    @Test
    fun functionAndAddressAliasesAgree() {
        assertEquals("path", explorerShortcut(Key.D, alt = true)!!.action)
        assertEquals("path", explorerShortcut(Key.L, ctrl = true)!!.action)
        assertEquals("path", explorerShortcut(Key.F4)!!.action)
        assertEquals("search", explorerShortcut(Key.F3)!!.action)
        assertEquals("search", explorerShortcut(Key.E, ctrl = true)!!.action)
        assertEquals("refresh", explorerShortcut(Key.F5)!!.action)
        assertEquals("info", explorerShortcut(Key.Enter, alt = true)!!.action)
    }

    @Test
    fun allNineNumberedTabsAndViewsResolve() {
        val digits =
            listOf(
                Key.One,
                Key.Two,
                Key.Three,
                Key.Four,
                Key.Five,
                Key.Six,
                Key.Seven,
                Key.Eight,
                Key.Nine,
            )
        digits.forEachIndexed { i, key ->
            assertEquals(ExplorerShortcut("selectTab", i + 1), explorerShortcut(key, ctrl = true))
            assertEquals(
                ExplorerShortcut("windowsView", i + 1),
                explorerShortcut(key, ctrl = true, shift = true),
            )
        }
    }

    @Test
    fun textFieldsKeepEditingShortcutsAndArrows() {
        listOf(
                Key.A,
                Key.C,
                Key.X,
                Key.V,
                Key.D,
                Key.Z,
                Key.Y,
                Key.Delete,
                Key.Backspace,
                Key.Spacebar,
                Key.DirectionLeft,
                Key.DirectionRight,
            )
            .forEach { key ->
                assertNull(
                    "Text editing must retain $key",
                    explorerShortcut(key, ctrl = true, editing = true),
                )
            }
        assertNull(explorerShortcut(Key.Delete, shift = true, editing = true))
        assertNull(explorerShortcut(Key.Enter, editing = true))
        assertEquals("path", explorerShortcut(Key.L, ctrl = true, editing = true)!!.action)
        assertEquals("focusArea", explorerShortcut(Key.F6, editing = true)!!.action)
    }

    @Test
    fun ordinaryTypingDoesNotTriggerFileCommands() {
        listOf(Key.N, Key.C, Key.D, Key.E, Key.Plus).forEach { assertNull(explorerShortcut(it)) }
        assertNull(explorerShortcut(Key.D, ctrl = true, meta = true))
    }

    @Test
    fun clipboardInsertAliasesAndPermanentDeleteAreMapped() {
        assertEquals("copy", explorerShortcut(Key.Insert, ctrl = true)!!.action)
        assertEquals("paste", explorerShortcut(Key.Insert, shift = true)!!.action)
        assertEquals("delete", explorerShortcut(Key.Delete, shift = true)!!.action)
        assertEquals("trash", explorerShortcut(Key.Delete)!!.action)
        assertEquals("back", explorerShortcut(Key.Backspace)!!.action)
    }

    @Test
    fun focusAndAdditiveSelectionKeepTheirModifiers() {
        assertTrue(explorerShortcut(Key.DirectionDown, ctrl = true)!!.focusOnly)
        assertFalse(explorerShortcut(Key.DirectionDown, ctrl = true)!!.extend)
        val extend = explorerShortcut(Key.DirectionDown, ctrl = true, shift = true)!!
        assertTrue(extend.extend)
        assertTrue(extend.additive)
        assertFalse(extend.focusOnly)
        assertTrue(explorerShortcut(Key.DirectionLeft, shift = true)!!.extend)
        assertEquals("toggleFocused", explorerShortcut(Key.Spacebar, ctrl = true)!!.action)
    }

    @Test
    fun treeInspectorsAndFittingAreAvailable() {
        assertEquals("navigationTree", explorerShortcut(Key.E, ctrl = true, shift = true)!!.action)
        assertEquals("expandAll", explorerShortcut(Key.NumPadMultiply)!!.action)
        assertEquals("expand", explorerShortcut(Key.NumPadAdd)!!.action)
        assertEquals("collapse", explorerShortcut(Key.NumPadSubtract)!!.action)
        assertEquals("fitColumns", explorerShortcut(Key.NumPadAdd, ctrl = true)!!.action)
        assertEquals("preview", explorerShortcut(Key.P, alt = true)!!.action)
        assertEquals("details", explorerShortcut(Key.P, alt = true, shift = true)!!.action)
    }

    @Test
    fun helpMenusAndFullscreenResolveWithoutPrintableKeys() {
        assertEquals("shortcuts", explorerShortcut(Key.F1)!!.action)
        assertEquals("context", explorerShortcut(Key.F10, shift = true)!!.action)
        assertEquals("actions", explorerShortcut(Key.F10)!!.action)
        assertEquals("fullscreen", explorerShortcut(Key.F11)!!.action)
        assertEquals("quicklook", explorerShortcut(Key.Spacebar)!!.action)
    }
}
