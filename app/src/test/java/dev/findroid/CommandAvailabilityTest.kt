package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class CommandAvailabilityTest {
    private val selected = CommandContext(selected = 1)
    @Test fun renamingUsesTheRenameCapabilityWithoutBlockingReadOnlyCopies() {
        val context = selected.copy(renameAllowed = false)
        assertEquals(CommandRecovery.COPY, commandProblem("rename", context)?.recovery)
        assertNull(commandProblem("copy", context)); assertNull(commandProblem("copyTo", context))
    }
    @Test fun removingOriginalsNeedsItsOwnCapability() {
        val context = selected.copy(removeAllowed = false)
        listOf("cut", "moveTo", "moveOtherPane", "trash", "groupSelection").forEach {
            assertEquals(it, CommandRecovery.COPY, commandProblem(it, context)?.recovery)
        }
        assertNull(commandProblem("rename", context))
    }
    @Test fun onlyOperationsThatNeedTheMutationSlotWaitForBusyWork() {
        val context = selected.copy(busy = "Copying demo files", clipboard = true, otherWritable = true)
        listOf("new", "newText", "rename", "batchRename", "trash", "duplicate", "undo").forEach {
            val value = if (it == "undo") context.copy(undo = true) else context
            assertEquals(it, CommandRecovery.TRANSFERS, commandProblem(it, value)?.recovery)
        }
        listOf("copy", "cut", "copyTo", "moveTo", "paste", "copyOtherPane", "info", "share").forEach { assertNull(it, commandProblem(it, context)) }
    }
    @Test fun anEmptySelectionDoesNotDisableFolderCreationOrBrowsing() {
        val context = CommandContext()
        listOf("rename", "copy", "moveTo", "share", "info").forEach { assertTrue(commandProblem(it, context)!!.reason.contains("Select")) }
        assertNull(commandProblem("new", context)); assertNull(commandProblem("recentActions", context)); assertNull(commandProblem("storage", context))
    }
    @Test fun aLoadingFolderExplainsWhyItsCapabilitiesAreNotReady() {
        val result = commandProblem("new", selected.copy(createAllowed = false, loading = true))!!
        assertTrue(result.reason.contains("finish loading")); assertNull(result.recovery)
    }
    @Test fun virtualViewsNeedARealCreationDestinationButCanRenameTheirResults() {
        val context = selected.copy(virtual = true, createAllowed = false, clipboard = true)
        assertEquals(CommandRecovery.FOLDER, commandProblem("new", context)?.recovery)
        assertEquals(CommandRecovery.FOLDER, commandProblem("paste", context)?.recovery)
        assertNull(commandProblem("rename", context)); assertNull(commandProblem("duplicate", context))
    }
    @Test fun disconnectedStorageOffersReconnectionWithoutHidingPathInformation() {
        val context = selected.copy(disconnected = true)
        listOf("rename", "copyTo", "share", "new").forEach { assertEquals(it, CommandRecovery.STORAGE, commandProblem(it, context)?.recovery) }
        listOf("copy", "copyPath", "info").forEach { assertNull(it, commandProblem(it, context)) }
    }
    @Test fun pasteExplainsAnEmptyClipboardBeforeDestinationAccess() {
        val context = selected.copy(createAllowed = false)
        assertTrue(commandProblem("paste", context)!!.reason.contains("Copy or cut"))
        assertEquals(CommandRecovery.STORAGE, commandProblem("paste", context.copy(clipboard = true))?.recovery)
    }
    @Test fun pasteIntoUsesTheSelectedFolderRatherThanItsReadOnlyParent() {
        val context = selected.copy(createAllowed = false, clipboard = true, selectedFolder = true, selectedFolderWritable = true)
        assertNull(commandProblem("pasteInto", context))
        assertEquals(CommandRecovery.STORAGE, commandProblem("pasteInto", context.copy(selectedFolderWritable = false))?.recovery)
        assertTrue(commandProblem("pasteInto", context.copy(selectedFolder = false))!!.reason.contains("one destination folder"))
    }
    @Test fun anUnavailableOtherPaneOffersThatPaneRatherThanChangingTheSource() {
        assertEquals(CommandRecovery.OTHER, commandProblem("copyOtherPane", selected)?.recovery)
        assertNull(commandProblem("copyOtherPane", selected.copy(otherWritable = true)))
    }
    @Test fun readOnlyArchivesCanChooseAnExtractionDestination() {
        val context = selected.copy(archive = true, createAllowed = false)
        assertNull(commandProblem("extract", context)); assertNull(commandProblem("extractTo", context))
        assertEquals(CommandRecovery.EXTRACT, commandProblem("extractHere", context)?.recovery)
    }
    @Test fun extractingHereDoesNotGuessTheRootOfSearchResults() {
        val context = selected.copy(archive = true, searchActive = true)
        assertEquals(CommandRecovery.EXTRACT, commandProblem("extractHere", context)?.recovery)
        assertTrue(commandProblem("extractTo", context.copy(archive = false))!!.reason.contains("one ZIP"))
    }
    @Test fun undoAndRedoDescribeTheSessionAndIgnoreAnUnrelatedOfflineBrowseRoot() {
        assertTrue(commandProblem("undo", CommandContext())!!.reason.contains("this session"))
        assertTrue(commandProblem("redo", CommandContext())!!.reason.contains("this session"))
        assertNull(commandProblem("undo", CommandContext(undo = true, disconnected = true)))
        assertNull(commandProblem("redo", CommandContext(redo = true, virtual = true)))
    }
    @Test fun trashItemsMustBeRestoredBeforeRenamingOrMoving() {
        val context = selected.copy(trash = true)
        assertTrue(commandProblem("rename", context)!!.reason.contains("Restore"))
        assertTrue(commandProblem("moveTo", context)!!.reason.contains("Restore"))
        assertNull(commandProblem("restore", context)); assertNull(commandProblem("copyTo", context))
    }
    @Test fun renameAndRemovePermissionsCanVaryIndependentlyWithinAProvider() {
        assertNull(commandProblem("moveTo", selected.copy(renameAllowed = false)))
        assertNull(commandProblem("rename", selected.copy(removeAllowed = false)))
        assertEquals(CommandRecovery.TRANSFERS, commandProblem("rename", selected.copy(renameAllowed = false, busy = "Copying"))?.recovery)
    }
}
