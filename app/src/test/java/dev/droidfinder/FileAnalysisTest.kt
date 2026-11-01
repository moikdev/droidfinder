package dev.droidfinder

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileAnalysisTest {
    private class Source : AnalysisSource {
        val nodes = linkedMapOf<String, AnalysisNode>()
        val lists = mutableMapOf<String, List<String>>()
        val contents = mutableMapOf<String, ByteArray>()
        val replies = mutableMapOf<String, DirectoryReply>()
        var opened = 0
        var hook: ((String) -> Unit)? = null

        fun folder(
            path: String,
            children: List<String> = emptyList(),
            id: String = path,
        ): AnalysisNode =
            AnalysisNode(path, path.substringAfterLast('/'), true, null, 1, id, "$id:v1").also {
                nodes[path] = it
                lists[path] = children
            }

        fun file(
            path: String,
            text: String,
            known: Boolean = true,
            id: String = path,
            link: Boolean = false,
            kind: FileCategory = FileCategory.OTHER,
        ): AnalysisNode =
            AnalysisNode(
                    path,
                    path.substringAfterLast('/'),
                    false,
                    if (known) text.toByteArray().size.toLong() else null,
                    1,
                    id,
                    "$id:v1",
                    kind,
                    link,
                    true,
                )
                .also {
                    nodes[path] = it
                    contents[path] = text.toByteArray()
                }

        override fun read(path: String, control: ListingControl): AnalysisNode {
            control.check()
            return nodes[path] ?: error("Missing file")
        }

        override suspend fun children(
            path: String,
            control: ListingControl,
            visit: suspend (AnalysisNode) -> Boolean,
        ): DirectoryReply {
            for (child in lists[path].orEmpty()) {
                control.check()
                if (!visit(read(child, control))) break
            }
            return replies[path] ?: DirectoryReply()
        }

        override fun input(path: String, control: ListingControl): InputStream {
            opened++
            hook?.invoke(path)
            return ByteArrayInputStream(contents.getValue(path))
        }
    }

    private fun scan(
        source: Source,
        limits: AnalysisLimits = AnalysisLimits(),
        hidden: Boolean = true,
        control: ListingControl = ListingControl(),
        progress: (AnalysisProgress) -> Unit = {},
    ): AnalysisReport = runBlocking {
        FolderAnalyzer(source).scan("/root", hidden, limits, control, progress)
    }

    @Test
    fun metadataScanMeasuresCategoriesFoldersAndUnknownSizesWithoutOpeningFiles() {
        val s = Source()
        s.folder("/root", listOf("/root/Photo.png", "/root/Docs", "/root/Unknown.bin"))
        s.folder("/root/Docs", listOf("/root/Docs/Note.txt"))
        s.file("/root/Photo.png", "image", kind = FileCategory.IMAGES)
        s.file("/root/Docs/Note.txt", "note", kind = FileCategory.DOCUMENTS)
        s.file("/root/Unknown.bin", "unknown", known = false)
        val r = scan(s)
        assertEquals(3, r.files.size)
        assertEquals(1, r.folders)
        assertEquals(9L, r.knownBytes)
        assertEquals(1, r.unknown)
        assertEquals(4L, r.buckets.first { it.name == "Docs" }.bytes)
        assertEquals("Docs/Note.txt", r.files.first { it.node.name == "Note.txt" }.relative)
        assertEquals(0, s.opened)
        assertFalse(r.incomplete)
    }

    @Test
    fun scanSkipsLinksHiddenAndWorkingFilesAndReportsCycles() {
        val s = Source()
        s.folder(
            "/root",
            listOf(
                "/root/.hidden",
                "/root/.droidfinder-stage",
                "/root/link",
                "/root/cycle",
                "/root/Keep.txt",
            ),
        )
        s.file("/root/.hidden", "private")
        s.file("/root/.droidfinder-stage", "stage")
        s.file("/root/link", "outside", link = true)
        s.folder("/root/cycle", id = "/root")
        s.file("/root/Keep.txt", "keep")
        val r = scan(s, hidden = false)
        assertEquals(listOf("Keep.txt"), r.files.map { it.node.name })
        assertEquals(1, r.linksSkipped)
        assertEquals(1, r.hiddenExcluded)
        assertEquals(1, r.issueCount)
        assertTrue(r.incomplete)
    }

    @Test
    fun entryLimitAndProviderLoadingAreExplicitPartialResults() {
        val s = Source()
        s.folder("/root", listOf("/root/A", "/root/B", "/root/C"))
        listOf("A", "B", "C").forEach { s.file("/root/$it", it) }
        s.replies["/root"] = DirectoryReply(loading = true, error = "Offline member")
        val r = scan(s, AnalysisLimits(entries = 2))
        assertEquals(2, r.files.size)
        assertTrue(r.limited)
        assertEquals(2, r.issueCount)
        assertTrue(r.incomplete)
    }

    @Test
    fun stoppedScanRetainsOnlyCompletedMetadataAndIsNeverComplete() {
        val s = Source()
        s.folder("/root", listOf("/root/A", "/root/B"))
        s.file("/root/A", "a")
        s.file("/root/B", "b")
        val control = ListingControl()
        val r = scan(s, control = control) { if (it.entries == 1) control.cancel() }
        assertTrue(r.stopped)
        assertTrue(r.incomplete)
        assertEquals(1, r.files.size)
    }

    @Test
    fun duplicatesReadUnknownSizesAndIgnoreHardlinkAliasesAndEmptyFiles() {
        val s = Source()
        val names = listOf("A", "B", "Different", "Hardlink", "Empty", "Unknown")
        s.folder("/root", names.map { "/root/$it" })
        s.file("/root/A", "same", id = "inode1")
        s.file("/root/B", "same")
        s.file("/root/Different", "diff")
        s.file("/root/Hardlink", "same", id = "inode1")
        s.file("/root/Empty", "")
        s.file("/root/Unknown", "same", known = false)
        val r = FolderAnalyzer(s).duplicates(scan(s), AnalysisLimits(), ListingControl())
        assertEquals(1, r.groups.size)
        assertEquals(listOf("A", "B", "Unknown"), r.groups.single().files.map { it.node.name })
        assertEquals(8L, r.groups.single().extraBytes)
        assertEquals(4, r.checked)
        assertEquals(16L, r.bytes)
        assertEquals(4, s.opened)
        assertEquals(4L, r.observed["/root/Unknown"]!!.bytes)
    }

    @Test
    fun readBudgetNeverPublishesAPartialChecksumAsADuplicate() {
        val s = Source()
        s.folder("/root", listOf("/root/A", "/root/B"))
        s.file("/root/A", "same")
        s.file("/root/B", "same")
        val r =
            FolderAnalyzer(s).duplicates(scan(s), AnalysisLimits(hashBytes = 5), ListingControl())
        assertTrue(r.groups.isEmpty())
        assertEquals(1, r.checked)
        assertEquals(1, r.skipped)
        assertTrue(r.incomplete)
    }

    @Test
    fun changingFileAndIncompleteProviderStreamFailInsteadOfReturningAHash() {
        val s = Source()
        val node = s.file("/root/A", "abc")
        s.hook = { s.nodes[it] = node.copy(revision = "changed") }
        assertTrue(
            runCatching { fileChecksum(s, node, ChecksumAlgorithm.SHA256, ListingControl()) }
                .isFailure,
        )
        s.hook = null
        s.nodes[node.path] = node
        s.contents[node.path] = byteArrayOf(1)
        assertTrue(
            runCatching { fileChecksum(s, node, ChecksumAlgorithm.SHA256, ListingControl()) }
                .isFailure,
        )
    }

    @Test
    fun checksumMatchesKnownVectorsAndReportsActualUnknownSizeBytes() {
        val s = Source()
        val node = s.file("/root/A", "abc", known = false)
        val sum = fileChecksum(s, node, ChecksumAlgorithm.SHA256, ListingControl())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sum.hex)
        assertEquals(3L, sum.bytes)
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            fileChecksum(s, node, ChecksumAlgorithm.MD5, ListingControl()).hex,
        )
    }

    @Test
    fun cancellationDuringHashingClosesInputAndDoesNotReturnAHash() {
        val s = Source()
        val node = s.file("/root/A", "a".repeat(100_000))
        val token = ListingControl()
        assertTrue(
            runCatching {
                fileChecksum(s, node, ChecksumAlgorithm.SHA256, token) { token.cancel() }
            }
                .isFailure,
        )
    }

    @Test
    fun expectedValuesAcceptHexColonPairsAndCoreutilsLinesButRejectWrongLength() {
        val hex = "a1".repeat(32)
        assertEquals(hex, expectedChecksum(hex.uppercase(), ChecksumAlgorithm.SHA256))
        assertEquals(
            hex,
            expectedChecksum(hex.chunked(2).joinToString(":"), ChecksumAlgorithm.SHA256),
        )
        assertEquals(hex, expectedChecksum("SHA-256: $hex", ChecksumAlgorithm.SHA256))
        assertEquals(hex, expectedChecksum("$hex  File name.txt", ChecksumAlgorithm.SHA256))
        assertTrue(runCatching { expectedChecksum(hex, ChecksumAlgorithm.MD5) }.isFailure)
        assertTrue(
            runCatching { expectedChecksum("not a checksum", ChecksumAlgorithm.SHA256) }.isFailure,
        )
    }

    @Test
    fun checksumManifestEscapesAmbiguousNamesAndSidecarKeepsItsExtension() {
        val node = AnalysisNode("/root/A", "Back\\slash\nand\rline.txt", false, 3, 1, "a", "v")
        val sum = FileChecksum(node, ChecksumAlgorithm.SHA256, "a".repeat(64), 3)
        assertEquals("\\${sum.hex}  Back\\\\slash\\nand\\rline.txt\n", checksumLine(sum))
        val long = sum.copy(node = node.copy(name = "🎒日本語".repeat(100)))
        val name = checksumFilename(long)
        assertTrue(name.endsWith(".sha256"))
        assertTrue(name.toByteArray().size <= 240)
        assertFalse(name.dropLast(7).last().isHighSurrogate())
    }

    @Test
    fun saturatingTotalsDoNotWrapNegativeAndCategoryFallbacksAreStable() {
        assertEquals(Long.MAX_VALUE, analysisAdd(Long.MAX_VALUE - 1, 10))
        assertEquals(FileCategory.IMAGES, FileCategory.of("Picture.bin", "image/jpeg"))
        assertEquals(
            FileCategory.ARCHIVES,
            FileCategory.of("BACKUP.ZIP", "application/octet-stream"),
        )
        assertEquals(
            FileCategory.DOCUMENTS,
            FileCategory.of("Readme.md", "application/octet-stream"),
        )
        assertEquals(FileCategory.DOCUMENTS, FileCategory.of("Cloud document", "application/pdf"))
        assertEquals(
            FileCategory.DOCUMENTS,
            FileCategory.of("Cloud sheet", "application/vnd.ms-excel"),
        )
        assertEquals(FileCategory.ARCHIVES, FileCategory.of("Cloud backup", "application/zip"))
    }

    @Test
    fun unknownSizeCanMatchAKnownSizeSingletonWithinTheReadBudget() {
        val s = Source()
        s.folder("/root", listOf("/root/A", "/root/Unknown"))
        s.file("/root/A", "same")
        s.file("/root/Unknown", "same", known = false)
        val r =
            FolderAnalyzer(s).duplicates(scan(s), AnalysisLimits(hashBytes = 8), ListingControl())
        assertEquals(2, r.groups.single().files.size)
        assertEquals(8L, r.bytes)
        assertFalse(r.incomplete)
        val limited =
            FolderAnalyzer(s).duplicates(scan(s), AnalysisLimits(hashBytes = 6), ListingControl())
        assertTrue(limited.groups.isEmpty())
        assertTrue(limited.incomplete)
        assertTrue(limited.observed.isEmpty())
    }

    @Test
    fun unavailableEntryDoesNotDiscardReadableSiblingsAndScopeRemainsPartial() {
        val s = Source()
        s.folder("/root", listOf("/root/A", "/root/Bad", "/root/B"))
        s.file("/root/A", "same")
        s.file("/root/B", "same")
        val bad = s.file("/root/Bad", "bad")
        s.nodes[bad.path] = bad.copy(problem = "Permission denied")
        val report = scan(s)
        assertEquals(2, report.files.size)
        assertEquals(1, report.issueCount)
        assertTrue(
            FolderAnalyzer(s)
                .duplicates(report, AnalysisLimits(), ListingControl())
                .scopeIncomplete,
        )
    }

    @Test
    fun cancelledIoDoesNotBecomeAnOrdinaryErrorAndManifestPasteMustBeSingleEntry() {
        val s = Source()
        s.folder("/root", listOf("/root/A", "/root/B"))
        s.file("/root/A", "same")
        s.file("/root/B", "same")
        val control = ListingControl()
        s.hook = {
            control.cancel()
            throw java.io.IOException("Closed descriptor")
        }
        val r = FolderAnalyzer(s).duplicates(scan(s), AnalysisLimits(), control)
        assertTrue(r.stopped)
        assertTrue(r.groups.isEmpty())
        assertEquals(2, r.skipped)
        val hash = "a".repeat(64)
        assertTrue(
            runCatching { expectedChecksum("$hash  A\n$hash  B", ChecksumAlgorithm.SHA256) }
                .isFailure,
        )
    }

    @Test
    fun repeatedDocumentPathsAreNotCountedTwiceButDistinctHardlinkPathsRemainVisible() {
        val s = Source()
        s.folder("/root", listOf("/root/A", "/root/A", "/root/Link"))
        s.file("/root/A", "same", id = "inode1")
        s.file("/root/Link", "same", id = "inode1")
        val r = scan(s)
        assertEquals(listOf("/root/A", "/root/Link"), r.files.map { it.node.path })
        assertEquals(1, r.issueCount)
        assertTrue(r.incomplete)
        assertEquals(8L, r.knownBytes)
    }

    @Test
    fun recheckingObservedUnknownSizesDoesNotTreatThemAsNewProviderMetadata() {
        val s = Source()
        s.folder("/root", listOf("/root/Known", "/root/Unknown"))
        s.file("/root/Known", "longer contents")
        s.file("/root/Unknown", "short", known = false)
        val initial = scan(s)
        val first = FolderAnalyzer(s).duplicates(initial, AnalysisLimits(), ListingControl())
        assertTrue(first.groups.isEmpty())
        val observed = first.observed.getValue("/root/Unknown")
        val snapshot =
            initial.copy(
                files =
                    initial.files.map { file ->
                        if (file.node.path == "/root/Unknown")
                            file.copy(
                                node =
                                    file.node.copy(
                                        bytes = observed.bytes,
                                        observedHash = observed.hex,
                                    ),
                            )
                        else file
                    },
            )
        // This simulates a provider with unchanged/unknown size and unchanged timestamps.
        s.contents["/root/Unknown"] = "longer contents".toByteArray()
        val second = FolderAnalyzer(s).duplicates(snapshot, AnalysisLimits(), ListingControl())
        assertEquals(2, second.groups.single().files.size)
        assertEquals(15L, second.observed.getValue("/root/Unknown").bytes)
        assertFalse(second.incomplete)
    }
}
