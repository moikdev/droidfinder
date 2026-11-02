package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class SearchLocationsTest {
    @Test fun disconnectedReferencesStayInOrderAndTheStoredListDoesNotFollowCallerMutation() {
        val original = mutableListOf("/missing/Project", "content://dev.findroid.fixtures/tree/root/document/opaque%2Ffolder")
        val checked = checkedSearchLocations(original)
        original.clear()
        assertEquals(listOf("/missing/Project", "content://dev.findroid.fixtures/tree/root/document/opaque%2Ffolder"), checked)
    }

    @Test fun virtualRelativeMalformedAndControlReferencesAreRejected() {
        for (path in listOf("", "@recent", "@tag:Blue", "relative/folder", "file:///root", "content:///missing-authority",
            "content://provider/folder with space", "/root/\u0000hidden", "/root/\nline", "/root/\uD800")) {
            try { checkedSearchLocations(listOf(path)); fail("Accepted invalid folder reference: ${path.length} characters") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun storageIndependentCountLengthAndDuplicateBoundsAreEnforcedBeforeIo() {
        val invalid = listOf((1..11).map { "/folder-$it" }, listOf("/same", "/same"), listOf("/" + "x".repeat(8192)))
        for (values in invalid) {
            try { checkedSearchLocations(values); fail("Accepted unbounded/repeated roots") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(10, checkedSearchLocations((1..10).map { "/folder-$it" }).size)
        assertTrue(checkedSearchLocations(emptyList()).isEmpty())
    }

    @Test fun overlapAndDifferentGrantReferencesRemainAvailableForIndependentStatus() {
        val values = listOf("/project", "/project/Subfolder", "content://provider/tree/one/document/shared-id",
            "content://provider/tree/two/document/shared-id")
        assertEquals(values, checkedSearchLocations(values))
    }
}
