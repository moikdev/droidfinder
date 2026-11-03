package dev.findroid

import android.provider.DocumentsContract.Document
import org.junit.Assert.*
import org.junit.Test

class StorageCapabilitiesTest {
    private val path = "content://fixture/tree/root/document/item"
    private val all = Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_WRITE or
        Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
    private fun provider(flags: Int? = all, folder: Boolean = false, writeGrant: Boolean? = true) =
        StorageAccessFacts(path, "Selected item", folder, provider = true, loaded = true,
            writable = true, rename = true, delete = true,
            document = ProviderAccessEvidence(path, flags, true, writeGrant))
    private fun local(folder: Boolean = false) =
        StorageAccessFacts("/library/item", "Local item", folder, provider = false, loaded = true,
            readable = true, writable = true, rename = true, delete = true)
    private fun detail(facts: StorageAccessFacts, action: StorageAction) = storageActionDetail(facts, action)

    @Test fun missingFlagsStayUnknownEvenWhenLegacyBooleansAreTrue() {
        for (action in listOf(StorageAction.WRITE, StorageAction.RENAME, StorageAction.DELETE, StorageAction.TRASH))
            assertEquals(ActionSupport.UNKNOWN, detail(provider(null), action).support)
        assertEquals(ActionSupport.AVAILABLE, detail(provider(null), StorageAction.READ).support)
    }

    @Test fun explicitZeroFlagsMeanUnadvertisedRatherThanUnreported() {
        val report = detail(provider(0), StorageAction.RENAME)
        assertEquals(ActionSupport.UNAVAILABLE, report.support)
        assertTrue(report.reason.contains("does not advertise"))
        assertEquals(CommandRecovery.COPY, report.recovery)
    }

    @Test fun renameSupportDoesNotImplyDeleteOrWrite() {
        val item = provider(Document.FLAG_SUPPORTS_RENAME)
        assertEquals(ActionSupport.AVAILABLE, detail(item, StorageAction.RENAME).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.WRITE).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.DELETE).support)
    }

    @Test fun removeFromParentSupportNeverAuthorizesPermanentDelete() {
        val item = provider(Document.FLAG_SUPPORTS_REMOVE)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.DELETE).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.TRASH).support)
    }

    @Test fun folderCreationAndFileWritingAreIndependent() {
        val folder = provider(Document.FLAG_SUPPORTS_WRITE, folder = true)
        assertEquals(ActionSupport.UNAVAILABLE, detail(folder, StorageAction.CREATE).support)
        assertEquals(ActionSupport.NOT_APPLICABLE, detail(folder, StorageAction.WRITE).support)
        val file = provider(Document.FLAG_DIR_SUPPORTS_CREATE)
        assertEquals(ActionSupport.NOT_APPLICABLE, detail(file, StorageAction.CREATE).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(file, StorageAction.WRITE).support)
    }

    @Test fun savedReadOnlyPermissionOverridesAdvertisedMutations() {
        val item = provider(writeGrant = false)
        for (action in listOf(StorageAction.WRITE, StorageAction.RENAME, StorageAction.DELETE, StorageAction.TRASH)) {
            val report = detail(item, action)
            assertEquals(ActionSupport.UNAVAILABLE, report.support)
            assertTrue(report.reason.contains("saved folder permission is read only"))
        }
        assertEquals(ActionSupport.AVAILABLE, detail(item, StorageAction.READ).support)
    }

    @Test fun knownReadOnlyPermissionRemainsUsefulWhenFlagsAreMissing() {
        val report = detail(provider(null, writeGrant = false), StorageAction.RENAME)
        assertEquals(ActionSupport.UNAVAILABLE, report.support)
        assertTrue(report.reason.contains("read only"))
    }

    @Test fun unknownSavedPermissionDoesNotClaimRevocation() {
        val item = provider(writeGrant = null).let {
            it.copy(document = it.document!!.copy(savedReadGrant = null))
        }
        val report = detail(item, StorageAction.READ)
        assertEquals(ActionSupport.UNKNOWN, report.support)
        assertTrue(report.reason.contains("Temporary access may work"))
        assertEquals(ActionSupport.AVAILABLE, detail(item, StorageAction.RENAME).support)
    }

    @Test fun rootEvidenceNeverSubstitutesForAChildsEvidence() {
        val item = provider().let { it.copy(document = it.document!!.copy(path = "content://fixture/tree/root")) }
        for (action in listOf(StorageAction.READ, StorageAction.WRITE, StorageAction.RENAME, StorageAction.DELETE))
            assertEquals(ActionSupport.UNKNOWN, detail(item, action).support)
    }

    @Test fun advertisedSupportDoesNotOverrideLoadedDenial() {
        val item = provider().copy(writable = false, rename = false, delete = false)
        for (action in listOf(StorageAction.WRITE, StorageAction.RENAME, StorageAction.DELETE))
            assertEquals(ActionSupport.UNAVAILABLE, detail(item, action).support)
    }

    @Test fun lostLocationTakesPrecedenceOverOldPositiveEvidence() {
        val item = provider().copy(unavailable = "Reconnect the missing location.")
        for (action in listOf(StorageAction.READ, StorageAction.WRITE, StorageAction.RENAME, StorageAction.DELETE)) {
            val report = detail(item, action)
            assertEquals(ActionSupport.UNAVAILABLE, report.support)
            assertEquals("Reconnect the missing location.", report.reason)
            assertEquals(CommandRecovery.STORAGE, report.recovery)
        }
    }

    @Test fun unloadedInformationDoesNotMeanReadOnly() {
        val item = provider().copy(loaded = false)
        assertEquals(ActionSupport.UNKNOWN, detail(item, StorageAction.RENAME).support)
        assertFalse(detail(item, StorageAction.RENAME).reason.contains("read only"))
    }

    @Test fun localReadOnlyFactsKeepReadAndMutationMeaningsSeparate() {
        val item = local(folder = true).copy(writable = false, rename = false, delete = false)
        assertEquals(ActionSupport.AVAILABLE, detail(item, StorageAction.READ).support)
        for (action in listOf(StorageAction.CREATE, StorageAction.RENAME, StorageAction.DELETE, StorageAction.TRASH))
            assertEquals(ActionSupport.UNAVAILABLE, detail(item, action).support)
    }

    @Test fun unreadableOriginalOffersStorageRecoveryInsteadOfCopy() {
        val item = local().copy(readable = false, rename = false)
        assertEquals(CommandRecovery.STORAGE, detail(item, StorageAction.READ).recovery)
        assertEquals(CommandRecovery.STORAGE, detail(item, StorageAction.RENAME).recovery)
    }

    @Test fun providerTrashExplainsLocalRecoveryAndChangedAddresses() {
        val report = detail(provider(), StorageAction.TRASH)
        assertEquals(ActionSupport.AVAILABLE, report.support)
        assertTrue(report.reason.contains("local recovery copy"))
        assertTrue(report.reason.contains("deletes the provider original"))
        assertTrue(report.reason.contains("new document addresses"))
        assertTrue(report.reason.contains("provider's own Trash is not used"))
    }

    @Test fun providerTrashNeverPromisesRecoveryWithoutDeleteSupport() {
        assertEquals(ActionSupport.UNAVAILABLE, detail(provider(0), StorageAction.TRASH).support)
        assertEquals(ActionSupport.UNKNOWN, detail(provider(null), StorageAction.TRASH).support)
    }

    @Test fun trashItemsExplainRestoreBeforeEditing() {
        val item = local().copy(inTrash = true)
        assertEquals(ActionSupport.NOT_APPLICABLE, detail(item, StorageAction.TRASH).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.RENAME).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.WRITE).support)
        assertEquals(ActionSupport.AVAILABLE, detail(item, StorageAction.DELETE).support)
    }

    @Test fun aProtectedRootCanStillBeReadAndReceiveChildren() {
        val reason = "This storage root cannot be renamed or deleted from Findroid."
        val item = local(folder = true).copy(restrictions = mapOf(StorageAction.RENAME to reason, StorageAction.DELETE to reason))
        assertEquals(ActionSupport.AVAILABLE, detail(item, StorageAction.READ).support)
        assertEquals(ActionSupport.AVAILABLE, detail(item, StorageAction.CREATE).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.RENAME).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.DELETE).support)
        assertEquals(ActionSupport.UNAVAILABLE, detail(item, StorageAction.TRASH).support)
    }

    @Test fun mixedSelectionCountsAreCompleteAndExamplesPrioritizeDeniedItems() {
        val items = List(20) { index ->
            when {
                index < 5 -> local(folder = true).copy(path = "/$index")
                index < 10 -> provider(null).copy(path = path, name = "Unknown $index")
                index < 15 -> provider(0).copy(name = "Denied $index")
                else -> local().copy(path = "/$index")
            }
        }
        val summary = storageActionSummary(items, StorageAction.WRITE)
        assertEquals(20, summary.total)
        assertEquals(5, summary.available)
        assertEquals(5, summary.unavailable)
        assertEquals(5, summary.unknown)
        assertEquals(5, summary.notApplicable)
        assertEquals(3, summary.examples.size)
        assertTrue(summary.examples.all { it.detail.support == ActionSupport.UNAVAILABLE })
    }

    @Test fun largeSelectionsKeepOnlyThreeNamedExamples() {
        val summary = storageActionSummary(List(10_000) { index -> local().copy(path = "/library/$index", name = "Denied $index", rename = false) }, StorageAction.RENAME)
        assertEquals(10_000, summary.unavailable)
        assertEquals(3, summary.examples.size)
        assertEquals(listOf("Denied 0", "Denied 1", "Denied 2"), summary.examples.map { it.name })
    }
}
