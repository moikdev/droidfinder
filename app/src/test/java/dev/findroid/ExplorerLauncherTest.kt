package dev.findroid

import androidx.compose.ui.input.key.Key
import org.junit.Assert.*
import org.junit.Test

class ExplorerLauncherTest {
    @Test fun findsWordsAcrossNamesDescriptionsAndFullPaths() {
        val item = LauncherChoice("f", "Projects", "Workspace / Writing", path = "/data/Workspace/Writing/Projects", keywords = "favorite recent")
        assertTrue(item.matches("writing PROJECTS"))
        assertTrue(item.matches("/data/Workspace/Writing"))
        assertTrue(item.matches("favorite projects"))
        assertFalse(item.matches("holiday"))
        assertTrue(item.matches("  "))
    }
    @Test fun launcherBindingsWorkWhileEditingWithoutChangingAddressShortcuts() {
        assertEquals("quickJump", explorerShortcut(Key.K, ctrl = true, editing = true)!!.action)
        assertEquals("launcher", explorerShortcut(Key.P, ctrl = true, shift = true, editing = true)!!.action)
        assertEquals("path", explorerShortcut(Key.L, ctrl = true)!!.action)
        assertNull(explorerShortcut(Key.P, ctrl = true))
    }
}
