package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class SidebarLayoutTest {
    private val a = FavoriteShortcut("a", "/Workspace/Alpha")
    private val b = FavoriteShortcut("b", "/Workspace/Beta", "Writing")
    private val c = FavoriteShortcut("c", "/Workspace/Gamma")

    @Test fun legacySetMigratesDeterministicallyWithoutLosingPaths() {
        val paths = linkedSetOf(c.path, a.path, b.path)
        val first = SidebarLayout.migrate(paths)
        assertEquals(listOf(a.path, b.path, c.path), first.favorites.map { it.path })
        assertEquals(paths.toSet(), first.paths)
        assertEquals(first, SidebarLayout.migrate(paths.toList().asReversed().toSet()))
    }

    @Test fun orderingLabelsGroupsAndBuiltinsRoundTrip() {
        val layout = SidebarLayout(listOf(c.copy(group = "work"), b, a),
            listOf(FavoriteGroup("work", "Work", true)), SidebarBuiltin.entries.reversed(), setOf(SidebarBuiltin.MOVIES))
        assertEquals(layout, SidebarJson.decode(SidebarJson.encode(layout)))
    }

    @Test fun stableInsertionCanMoveInBothDirectionsAndAppend() {
        val layout = SidebarLayout(listOf(a, b, c))
        assertEquals(listOf(c, a, b), layout.place(listOf("c"), "a", null).favorites)
        assertEquals(listOf(b, a, c), layout.place(listOf("a"), "c", null).favorites)
        assertEquals(listOf(b, c, a), layout.place(listOf("a"), null, null).favorites)
        assertEquals(layout, layout.place(listOf("b"), "b", null))
    }

    @Test fun movingBetweenGroupsPreservesLabelAndOtherOrder() {
        val layout = SidebarLayout(listOf(a, b, c.copy(group = "work")), listOf(FavoriteGroup("work", "Work")))
        val result = layout.place(listOf("b"), "c", "work")
        assertEquals(listOf(a, b.copy(group = "work"), c.copy(group = "work")), result.favorites)
        assertEquals("Writing", result.favorites[1].label)
    }

    @Test fun staleInsertionAndDeletedGroupAreRejected() {
        val layout = SidebarLayout(listOf(a, b))
        assertThrows(IllegalArgumentException::class.java) { layout.place(listOf("a"), "gone", null) }
        assertThrows(IllegalArgumentException::class.java) { layout.place(listOf("a"), null, "gone") }
        assertThrows(IllegalStateException::class.java) { layout.place(listOf("gone"), null, null) }
    }

    @Test fun remappingKeepsIdentityLabelAndOrder() {
        val layout = SidebarLayout(listOf(b, a))
        val result = layout.remap(listOf("/Workspace" to "/New tablet"))
        assertEquals(listOf("b", "a"), result.favorites.map { it.id })
        assertEquals("Writing", result.favorites.first().label)
        assertEquals("/New tablet/Beta", result.favorites.first().path)
        assertEquals("/Workspace/Beta", layout.favorites.first().path)
    }

    @Test fun corruptAndFutureDataFailInsteadOfSilentlyResetting() {
        val good = SidebarJson.encode(SidebarLayout(listOf(a)))
        assertThrows(IllegalArgumentException::class.java) { SidebarJson.decode(good.replace("\"version\":1", "\"version\":2")) }
        assertThrows(Exception::class.java) { SidebarJson.decode("{broken") }
        assertThrows(IllegalArgumentException::class.java) { SidebarLayout(listOf(a, a)).checked() }
        assertThrows(IllegalArgumentException::class.java) { SidebarLayout(listOf(a.copy(group = "missing"))).checked() }
        assertThrows(IllegalArgumentException::class.java) { SidebarLayout(listOf(a.copy(label = "a\nb"))).checked() }
    }
}
