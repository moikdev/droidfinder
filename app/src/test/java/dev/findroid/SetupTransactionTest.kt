package dev.findroid

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SetupTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Prefs : SetupPreferencePort {
        var value = mapOf("finder" to mapOf("dark" to SetupPref("boolean", listOf("true"))), "zip-limits" to emptyMap())
        var failOnce = false
        override fun read() = value
        override fun write(value: Map<String, Map<String, SetupPref>>) {
            this.value = value
            if (failOnce) { failOnce = false; throw IOException("Injected preference failure") }
        }
    }
    private fun setup(directory: File) { SetupTransaction.FILES.forEach { File(directory, it).writeText("old-$it") } }
    private fun files() = SetupTransaction.FILES.associateWith { "new-$it".toByteArray() }
    private fun prefs() = mapOf("finder" to mapOf("dark" to SetupPref("boolean", listOf("false"))), "zip-limits" to mapOf("entries" to SetupPref("int", listOf("10000"))))
    private fun assertOld(directory: File) { SetupTransaction.FILES.forEach { assertEquals("old-$it", File(directory, it).readText()) } }

    @Test fun successfulCommitChangesOnlyWhitelistedMetadataAndPreservesUserBytes() {
        val directory = temporary.newFolder()
        setup(directory)
        val original = File(directory, "Workspace").apply { mkdir() }
        File(original, "Keep.txt").writeText("Original user file")
        File(directory, "document-parents.properties").writeText("Provider cache stays")
        val preferences = Prefs()
        val transaction = SetupTransaction(directory, preferences)
        transaction.apply(transaction.fingerprint(), files(), prefs())
        SetupTransaction.FILES.forEach { assertEquals("new-$it", File(directory, it).readText()) }
        assertEquals(prefs(), preferences.value)
        assertEquals("Original user file", File(original, "Keep.txt").readText())
        assertEquals("Provider cache stays", File(directory, "document-parents.properties").readText())
        assertFalse(File(directory, ".setup-transaction").exists())
        assertFalse(transaction.recover())
    }

    @Test fun eachFileFailureRollsBackAllFilesAndPreferences() {
        for (boundary in SetupTransaction.FILES) {
            val directory = temporary.newFolder()
            setup(directory)
            val preferences = Prefs()
            val before = preferences.value
            val transaction = SetupTransaction(directory, preferences) { if (it == boundary) throw IOException("Injected failure") }
            assertThrows(IOException::class.java) { transaction.apply(transaction.fingerprint(), files(), prefs()) }
            assertOld(directory)
            assertEquals(before, preferences.value)
            assertFalse(File(directory, ".setup-transaction").exists())
        }
    }

    @Test fun preferenceFailureAfterWritesRestoresTheOriginalPreferenceTypesAndAbsences() {
        val directory = temporary.newFolder()
        setup(directory)
        val preferences = Prefs()
        val before = preferences.value
        preferences.failOnce = true
        val transaction = SetupTransaction(directory, preferences)
        assertThrows(IOException::class.java) { transaction.apply(transaction.fingerprint(), files(), prefs()) }
        assertOld(directory)
        assertEquals(before, preferences.value)
    }

    @Test fun processDeathAtEachBoundaryRecoversBeforeAnotherImport() {
        for (boundary in SetupTransaction.FILES + "preferences") {
            val directory = temporary.newFolder()
            setup(directory)
            val preferences = Prefs()
            val before = preferences.value
            val transaction = SetupTransaction(directory, preferences) { if (it == boundary) throw AssertionError("Simulated process death") }
            assertThrows(AssertionError::class.java) { transaction.apply(transaction.fingerprint(), files(), prefs()) }
            assertTrue(File(directory, ".setup-transaction/manifest.json").isFile)
            assertThrows(IllegalStateException::class.java) { transaction.fingerprint() }
            assertTrue(SetupTransaction(directory, preferences).recover())
            assertOld(directory)
            assertEquals(before, preferences.value)
            assertFalse(SetupTransaction(directory, preferences).recover())
        }
    }

    @Test fun startupRemovesNewFilesWhenTheOriginalSetupFilesWereAbsent() {
        val directory = temporary.newFolder()
        val preferences = Prefs()
        val before = preferences.value
        val transaction = SetupTransaction(directory, preferences) { if (it == "preferences") throw AssertionError("Simulated death") }
        assertThrows(AssertionError::class.java) { transaction.apply(transaction.fingerprint(), files(), prefs()) }
        SetupTransaction(directory, preferences).recover()
        SetupTransaction.FILES.forEach { assertFalse(File(directory, it).exists()) }
        assertEquals(before, preferences.value)
    }

    @Test fun staleReviewIsRejectedBeforeCreatingARecoveryJournal() {
        val directory = temporary.newFolder()
        setup(directory)
        val preferences = Prefs()
        val transaction = SetupTransaction(directory, preferences)
        val reviewed = transaction.fingerprint()
        File(directory, "tags.properties").writeText("New user tags")
        assertThrows(IllegalStateException::class.java) { transaction.apply(reviewed, files(), prefs()) }
        assertEquals("New user tags", File(directory, "tags.properties").readText())
        assertFalse(File(directory, ".setup-transaction").exists())
    }

    @Test fun invalidImportTargetsAndPreferenceValuesCannotWriteOutsideTheSetup() {
        val directory = temporary.newFolder()
        setup(directory)
        val preferences = Prefs()
        val transaction = SetupTransaction(directory, preferences)
        val fingerprint = transaction.fingerprint()
        assertThrows(IllegalArgumentException::class.java) { transaction.apply(fingerprint, files() + ("../outside" to byteArrayOf(1)), prefs()) }
        assertThrows(IllegalArgumentException::class.java) { transaction.apply(fingerprint, files(), prefs() + ("locations" to emptyMap())) }
        assertThrows(IllegalArgumentException::class.java) { transaction.apply(fingerprint, files(), prefs() + ("finder" to mapOf("dark" to SetupPref("boolean", listOf("yes"))))) }
        assertOld(directory)
        assertFalse(File(directory.parentFile, "outside").exists())
    }

    @Test fun corruptRecoveryMetadataStaysPreservedWithoutChangingSetupFiles() {
        val directory = temporary.newFolder()
        setup(directory)
        val journal = File(directory, ".setup-transaction").apply { mkdir() }
        File(journal, "manifest.json").writeText("{\"version\":99,\"phase\":\"prepared\"}")
        assertThrows(IllegalArgumentException::class.java) { SetupTransaction(directory, Prefs()).recover() }
        assertOld(directory)
        assertTrue(journal.exists())
    }

    @Test fun committedJournalCleanupDoesNotUndoACompletedRestore() {
        val directory = temporary.newFolder()
        setup(directory)
        val preferences = Prefs()
        val transaction = SetupTransaction(directory, preferences) { if (it == "preferences") throw AssertionError("Simulated exit") }
        assertThrows(AssertionError::class.java) { transaction.apply(transaction.fingerprint(), files(), prefs()) }
        val manifest = File(directory, ".setup-transaction/manifest.json")
        val record = JsonParser.parseString(manifest.readText()).asJsonObject
        record.addProperty("phase", "committed")
        manifest.writeText(record.toString())
        assertFalse(SetupTransaction(directory, preferences).recover())
        SetupTransaction.FILES.forEach { assertEquals("new-$it", File(directory, it).readText()) }
        assertEquals(prefs(), preferences.value)
        assertFalse(manifest.exists())
    }
}
