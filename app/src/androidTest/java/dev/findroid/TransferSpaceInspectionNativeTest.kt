package dev.findroid

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransferSpaceInspectionNativeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: File
    private lateinit var destination: File
    private lateinit var source: File
    private lateinit var documents: DocumentAccess
    private fun uri(file: File): String {
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture.name}")
        val relative = file.relativeTo(fixture).path.takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()
        return DocumentsContract.buildDocumentUriUsingTree(tree, "root/${fixture.name}$relative").toString()
    }
    @Before fun setup() {
        fixture = File(context.filesDir, "ProviderFixtures/Space-plan-${UUID.randomUUID()}").apply { check(mkdirs()) }
        source = File(fixture, "Original.txt").apply { writeText("synthetic source for metadata-only review") }
        destination = File(fixture, "Destination").apply { check(mkdir()) }
        documents = DocumentAccess(context)
    }
    @After fun cleanup() {
        FixtureDocumentsProvider.unknownSizePaths = emptySet()
        FixtureDocumentsProvider.unreportedCapacity = false
        if (::fixture.isInitialized) {
            check(fixture.parentFile == File(context.filesDir, "ProviderFixtures") && fixture.name.startsWith("Space-plan-"))
            check(fixture.deleteRecursively())
        }
    }
    private fun inspect(sourcePath: String = source.path, destinationPath: String = destination.path,
        probe: ((String) -> Long?)? = null, policy: ConflictPolicy = ConflictPolicy.ASK): TransferSpaceInspection =
        (probe?.let { DocumentAccess(context, spaceProbe = it) } ?: documents).inspectTransferSpace(
            TransferSpaceRequest(listOf(sourcePath), destinationPath, false, policy), TransferControl())
    private fun bytes() = fixture.walkTopDown().filter(File::isFile).associate { it.relativeTo(fixture).path to it.readBytes().toList() }

    @Test fun localInspectionKeepsAllBytesAndCreatesNoWorkingOrRecoveryFiles() {
        val original = bytes(); val result = inspect()
        assertEquals(source.length(), result.plan.contents.knownBytes)
        assertEquals(original, bytes())
        assertEquals(1, result.plan.budgets.single().requirements.size)
        assertFalse(fixture.walkTopDown().any { it.name.startsWith(".findroid-") })
    }
    @Test fun replacementReviewIncludesExistingOriginalAndReportsExactKnownShortfall() {
        val target = File(destination, source.name).apply { writeText("synthetic existing original") }
        val original = bytes()
        val result = inspect(probe = { 10L })
        assertEquals(source.length() + target.length(), result.plan.budgets.single().knownBytes)
        assertEquals(source.length() + target.length() - 10, result.plan.budgets.single().shortfallBytes)
        assertEquals(original, bytes())
    }
    @Test fun providerUnknownSizeStaysIncompleteAndDoesNotOpenOrChangeTheDocument() {
        FixtureDocumentsProvider.unknownSizePaths = setOf(source.canonicalPath)
        val original = bytes(); val result = inspect(sourcePath = uri(source))
        assertEquals(1, result.plan.contents.unknownFiles)
        assertTrue(result.plan.uncertain)
        assertEquals(original, bytes())
    }
    @Test fun unreportedProviderCapacityIsVisibleRatherThanInferredFromAppStorage() {
        FixtureDocumentsProvider.unreportedCapacity = true
        val result = inspect(destinationPath = uri(destination))
        assertNull(result.plan.budgets.single().availableBytes)
        assertEquals(TransferSpaceAssessment.UNREPORTED, result.plan.budgets.single().assessment)
        assertNull(result.plan.budgets.single().requirements.single().location.storageVolumeId)
    }
    @Test fun providerReplacementAndPrivateRecoveryDoNotAssumeIndependentStorage() {
        File(destination, source.name).writeText("synthetic destination")
        val original = bytes(); val result = inspect(destinationPath = uri(destination), probe = { 100_000L })
        assertEquals(2, result.plan.budgets.size)
        assertTrue(result.plan.sharedStorageUnproven)
        assertTrue(result.plan.uncertain)
        assertEquals(original, bytes())
    }
    @Test fun zeroReportedFreeSpaceProducesAShortfallWithoutDocumentWork() {
        val original = bytes(); val result = inspect(probe = { 0L })
        assertTrue(result.plan.knownShortfall)
        assertEquals(source.length(), result.plan.budgets.single().shortfallBytes)
        assertEquals(original, bytes())
    }
    @Test fun unknownReportedCapacityPreservesKnownSizes() {
        val result = inspect(probe = { null })
        assertEquals(source.length(), result.plan.contents.knownBytes)
        assertNull(result.plan.budgets.single().availableBytes)
        assertTrue(result.plan.uncertain)
    }
    @Test fun cancelledInspectionLeavesEveryOriginalIntact() {
        val original = bytes()
        try {
            documents.inspectTransferSpace(TransferSpaceRequest(listOf(source.path), destination.path, false, ConflictPolicy.ASK),
                TransferControl().apply { cancel() })
            fail("A cancelled review must stop")
        } catch (_: CancellationException) { }
        assertEquals(original, bytes())
    }
}
