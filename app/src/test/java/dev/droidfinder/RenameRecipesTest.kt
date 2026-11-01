package dev.droidfinder

import java.io.File
import java.nio.file.Files
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RenameRecipesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun item(name: String, parent: String = "root", folder: Boolean = false) =
        RenameInput(
            "$parent/$name",
            parent,
            name,
            folder,
            1L,
            if (folder) null else 42L,
            "revision",
            "Trips",
            listOf("Blue", "Review"),
        )

    private fun plan(
        rule: RenameRecipe,
        inputs: List<RenameInput> = listOf(item("Photo.txt")),
        excluded: Set<String> = emptySet(),
        extras: List<RenameSibling> = emptyList(),
    ) =
        planRecipeRename(
            inputs,
            inputs.map { RenameSibling(it.id, it.parent, it.name) } + extras,
            excluded,
            rule,
            zone,
        )

    private fun name(rule: RenameRecipe, input: RenameInput = item("Photo.txt")) =
        plan(rule, listOf(input)).rows.single().let {
            assertNull(it.error)
            it.name
        }

    @Test
    fun blankRulesAreNoopsAndPartsPreserveExtensionsAndDotfiles() {
        assertFalse(plan(RenameRecipe()).canApply)
        assertEquals(
            "archive.tar_v2.gz",
            name(RenameRecipe(method = RenameMethod.SUFFIX, text = "_v2"), item("archive.tar.gz")),
        )
        assertEquals(
            "copy-.profile",
            name(RenameRecipe(method = RenameMethod.PREFIX, text = "copy-"), item(".profile")),
        )
        assertEquals(
            "Photo.MD",
            name(
                RenameRecipe(
                    method = RenameMethod.REPLACE,
                    part = RenamePart.EXTENSION,
                    text = "txt",
                    replacement = "MD",
                ),
            ),
        )
        assertEquals(
            "Folder.txt_v2",
            name(
                RenameRecipe(method = RenameMethod.SUFFIX, text = "_v2"),
                item("Folder.txt", folder = true),
            ),
        )
        assertEquals(
            "Photo.md",
            name(RenameRecipe(part = RenamePart.FULL, text = ".txt", replacement = ".md")),
        )
    }

    @Test
    fun literalCaseMatchingAndRegexGroupsAreDistinct() {
        assertEquals(
            "pHOTO.txt",
            name(
                RenameRecipe(text = "PH", replacement = "pH", matchCase = false),
                item("PHOTO.txt"),
            ),
        )
        assertEquals(
            "PHOTO.txt",
            name(RenameRecipe(text = "ph", replacement = "x"), item("PHOTO.txt")),
        )
        val dollar = '$'
        assertEquals(
            "002_IMG.jpg",
            name(
                RenameRecipe(
                    text = "([A-Z]+)-([0-9]+)",
                    replacement = dollar + "2_" + dollar + "1",
                    regex = true,
                ),
                item("IMG-002.jpg"),
            ),
        )
        assertEquals(
            "photo_02.jpg",
            name(
                RenameRecipe(
                    text = "(?P<base>[a-z]+)-([0-9]+)",
                    replacement = dollar + "{base}_" + dollar + "2",
                    regex = true,
                ),
                item("photo-02.jpg"),
            ),
        )
        assertEquals(
            "x-aa.txt",
            name(RenameRecipe(text = "aa", replacement = "x", firstOnly = true), item("aa-aa.txt")),
        )
        assertEquals(
            "dollar" + dollar + "1.txt",
            name(RenameRecipe(text = "Photo", replacement = "dollar" + dollar + "1")),
        )
    }

    @Test
    fun unsupportedRegexFeaturesFailWithoutBlockingOrPublishingNames() {
        assertFalse(plan(RenameRecipe(text = "(?=Photo)", regex = true)).canApply)
        assertTrue(plan(RenameRecipe(text = "(?=Photo)", regex = true)).errors > 0)
        val pathological = item("a".repeat(200) + "!.txt")
        assertEquals(
            pathological.name,
            name(RenameRecipe(text = "(a+)+$", replacement = "x", regex = true), pathological),
        )
        assertTrue(plan(RenameRecipe(text = "[", regex = true)).errors > 0)
    }

    @Test
    fun sequencePaddingStepAndExclusionUseOnlyIncludedPositions() {
        val inputs = listOf(item("a.txt"), item("b.txt"), item("c.txt"))
        val result =
            plan(
                RenameRecipe(
                    method = RenameMethod.NUMBER,
                    text = "Trip",
                    start = 0,
                    step = 5,
                    padding = 3,
                    separator = "_",
                ),
                inputs,
                setOf(inputs[1].id),
            )
        assertEquals(listOf("Trip_000.txt", "Trip_005.txt"), result.changes.map { it.name })
        assertFalse(result.rows[1].included)
        assertEquals("1.txt", name(RenameRecipe(method = RenameMethod.NUMBER)))
        assertTrue(
            plan(RenameRecipe(method = RenameMethod.NUMBER, start = Long.MAX_VALUE), inputs)
                .errors > 0,
        )
    }

    @Test
    fun templatesUseCapturedMetadataTimezoneAndSafeMetadataText() {
        val input =
            item("Photo.JPG")
                .copy(
                    parentName = "Trips/2026",
                    photo =
                        RenamePhoto(
                            LocalDateTime.parse("2024-02-03T10:20:30"),
                            "ACME/One",
                            4000,
                            3000,
                        ),
                )
        val recipe =
            RenameRecipe(
                method = RenameMethod.TEMPLATE,
                template =
                    "{parent}_{modified:yyyyMMdd_HHmmss}_{taken:yyyyMMdd}_{camera}_{width}x{height}_{size}_{tag}_{index}{dotext}",
                padding = 3,
            )
        assertEquals(
            "Trips_2026_19700101_090000_20240203_ACME_One_4000x3000_42_Blue_001.JPG",
            name(recipe, input),
        )
        assertEquals("Blue_Review.txt", name(recipe.copy(template = "{tags}{dotext}")))
        assertEquals("Photo{x}.txt", name(recipe.copy(template = "{name}{{x}}{dotext}")))
    }

    @Test
    fun templatesHandleExtensionlessFilesFoldersAndExplicitUnknowns() {
        val recipe =
            RenameRecipe(method = RenameMethod.TEMPLATE, template = "{name}_{index}{dotext}")
        assertEquals("README_1", name(recipe, item("README")))
        assertEquals("Photos.raw_1", name(recipe, item("Photos.raw", folder = true)))
        assertTrue(plan(recipe.copy(template = "{taken:yyyy-MM-dd}")).errors > 0)
        assertTrue(
            plan(recipe.copy(template = "{size}"), listOf(item("unknown").copy(bytes = null)))
                .errors > 0,
        )
        assertTrue(
            plan(
                    recipe.copy(template = "{modified}"),
                    listOf(item("unknown").copy(modified = null)),
                )
                .errors > 0,
        )
        assertTrue(plan(recipe.copy(template = "{name:bad}")).errors > 0)
        assertTrue(plan(recipe.copy(template = "{unknown}")).errors > 0)
        assertTrue(plan(recipe.copy(template = "{name")).errors > 0)
        assertTrue(plan(recipe.copy(template = "name}")).errors > 0)
    }

    @Test
    fun sourceAndDestinationErrorsBlockTheWholeReviewedPlan() {
        val inputs = listOf(item("a.txt"), item("b.txt"))
        val rule = RenameRecipe(method = RenameMethod.TEMPLATE, template = "same.txt")
        assertEquals(2, plan(rule, inputs).errors)
        val external = RenameSibling("root/elsewhere", "root", "new.txt")
        assertFalse(
            plan(rule.copy(template = "new.txt"), inputs.take(1), extras = listOf(external))
                .canApply,
        )
        assertFalse(plan(rule.copy(template = "b.txt"), inputs, setOf(inputs[1].id)).canApply)
        assertEquals(
            1,
            plan(
                    RenameRecipe(method = RenameMethod.PREFIX, text = "new-"),
                    inputs.mapIndexed { i, it ->
                        if (i == 0) it.copy(problem = "Read only") else it
                    },
                )
                .errors,
        )
        val acrossParents = listOf(item("a.txt", "one"), item("b.txt", "two"))
        assertTrue(plan(rule, acrossParents).canApply)
    }

    @Test
    fun descendantsRemainBlockedUntilTheSelectedAncestorIsExcluded() {
        val folder = item("Folder", folder = true)
        val child = item("child.txt", folder.id).copy(ancestors = setOf(folder.id))
        val recipe = RenameRecipe(method = RenameMethod.PREFIX, text = "new-")
        assertFalse(plan(recipe, listOf(folder, child)).canApply)
        assertTrue(plan(recipe, listOf(folder, child), setOf(folder.id)).canApply)
    }

    @Test
    fun portableConflictsCoverCaseAndCanonicalUnicodeEquivalence() {
        val inputs = listOf(item("one"), item("two"))
        val rule =
            RenameRecipe(
                method = RenameMethod.REPLACE,
                text = "one",
                replacement = "TWO",
                part = RenamePart.FULL,
            )
        assertFalse(plan(rule, inputs).canApply)
        assertTrue(plan(rule.copy(portableConflicts = false), inputs).canApply)
        val unicode = item("Cafe\u0301")
        val existing = RenameSibling("root/other", "root", "Café")
        assertFalse(
            plan(
                    RenameRecipe(method = RenameMethod.CASE, letterCase = RenameLetterCase.UPPER),
                    listOf(unicode),
                    extras = listOf(existing),
                )
                .canApply,
        )
    }

    @Test
    fun unicodeCaseAndNormalizationDoNotSplitCombiningMarksOrEmoji() {
        val rule = RenameRecipe(method = RenameMethod.CASE, letterCase = RenameLetterCase.TITLE)
        assertEquals("E\u0301cole 📷 Trip.txt", name(rule, item("e\u0301COLE 📷 tRIP.txt")))
        assertEquals(
            "STRASSE.txt",
            name(rule.copy(letterCase = RenameLetterCase.UPPER), item("straße.txt")),
        )
        assertEquals(
            "Café.txt",
            name(
                rule.copy(
                    letterCase = RenameLetterCase.KEEP,
                    normalization = RenameNormalization.NFC,
                ),
                item("Cafe\u0301.txt"),
            ),
        )
        assertEquals(
            "Cafe\u0301.txt",
            name(
                rule.copy(
                    letterCase = RenameLetterCase.KEEP,
                    normalization = RenameNormalization.NFD,
                ),
                item("Café.txt"),
            ),
        )
    }

    @Test
    fun filenameBoundsReservedNamesAndMalformedUnicodeAreExplicit() {
        val rule = RenameRecipe(method = RenameMethod.TEMPLATE)
        listOf("../escape", ".droidfinder-output", "", "界".repeat(81), "\uD83D").forEach { target ->
            assertFalse(plan(rule.copy(template = target)).canApply)
        }
        assertTrue(plan(rule.copy(template = "界".repeat(76) + ".txt")).canApply)
    }

    @Test
    fun naturalNameOrderComparesLongNumbersWithoutIntegerOverflow() {
        val inputs =
            listOf(
                item("File 10"),
                item("File 002"),
                item("File 2"),
                item("File " + "9".repeat(100)),
            )
        assertEquals(
            listOf("File 2", "File 002", "File 10", "File " + "9".repeat(100)),
            orderedRenameInputs(inputs, RenameOrder.NATURAL, false).map { it.name },
        )
        assertEquals(inputs.reversed(), orderedRenameInputs(inputs, RenameOrder.CURRENT, true))
        assertEquals(
            inputs.last(),
            orderedRenameInputs(
                    inputs.mapIndexed { i, it -> it.copy(bytes = i.toLong()) },
                    RenameOrder.SIZE,
                    true,
                )
                .first()
                .let { inputs.first { input -> input.id == it.id } },
        )
    }

    @Test
    fun cancellationAndSelectionLimitsNeverReturnAPartialExecutablePlan() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            planRecipeRename(listOf(item("a")), emptyList(), emptySet(), RenameRecipe(), zone) {
                throw java.util.concurrent.CancellationException()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(RenameRecipe(), List(10_001) { item("file-$it") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(RenameRecipe(), listOf(item("a"), item("a")))
        }
    }

    @Test
    fun presetsRoundTripRulesWithoutSourcePathsAndRejectImplicitOverwrites() {
        val file = File(temporary.newFolder("presets"), "rename.properties")
        val store = RenamePresetStore(file)
        val rule =
            RenameRecipe(
                RenameMethod.TEMPLATE,
                RenamePart.FULL,
                "Text",
                "New",
                true,
                false,
                true,
                7,
                3,
                4,
                "-",
                "{name}_{index}{dotext}",
                RenameLetterCase.TITLE,
                RenameNormalization.NFC,
                false,
            )
        val saved = store.save("Travel", rule, RenameOrder.NATURAL, true)
        assertEquals(listOf(saved), RenamePresetStore(file).load())
        assertFalse(file.readText().contains("root/"))
        assertThrows(IllegalArgumentException::class.java) {
            store.save("travel", rule, RenameOrder.NAME, false)
        }
        val updated =
            store.save("Travel updated", rule.copy(padding = 5), RenameOrder.NAME, false, saved.id)
        assertEquals(saved.id, updated.id)
        assertEquals(5, RenamePresetStore(file).load().single().recipe.padding)
        store.remove(saved.id)
        assertTrue(RenamePresetStore(file).load().isEmpty())
    }

    @Test
    fun invalidPresetCatalogsAndWritesPreserveTheOriginalBytes() {
        val file = File(temporary.newFolder("invalid-presets"), "rename.properties")
        file.writeText("version=99\nids=\n")
        val original = file.readBytes()
        val store = RenamePresetStore(file)
        assertTrue(store.load().isEmpty())
        assertNotNull(store.error)
        assertThrows(IllegalStateException::class.java) {
            store.save("New", RenameRecipe(), RenameOrder.CURRENT, false)
        }
        assertArrayEquals(original, file.readBytes())
        file.delete()
        assertTrue(store.load().isEmpty())
        assertNull(store.error)
        assertThrows(IllegalArgumentException::class.java) {
            store.save("Bad", RenameRecipe(start = -1), RenameOrder.CURRENT, false)
        }
        assertFalse(file.exists())
    }

    @Test
    fun caseOnlyAndUnicodeRenamesRoundTripThroughUndoAndRedo() {
        val root = temporary.newFolder("case-files")
        val engine = FileEngine(temporary.newFolder("case-state"))
        val original = File(root, "MiXeD.txt").apply { writeText("Keep contents") }
        val renamed = engine.rename(original, "mixed.txt")
        assertEquals(listOf("mixed.txt"), root.listFiles()!!.map { it.name })
        val history = FileHistory(engine)
        history.record("Case rename", listOf(FileStep.Renamed(listOf(original to renamed))))
        history.undo()
        assertEquals(listOf("MiXeD.txt"), root.listFiles()!!.map { it.name })
        history.redo()
        assertEquals(listOf("mixed.txt"), root.listFiles()!!.map { it.name })
        val unicode = engine.rename(renamed, "Café 📷.txt")
        assertEquals("Keep contents", unicode.readText())
        val decomposed = engine.rename(unicode, "Cafe\u0301 📷.txt")
        assertEquals(
            "Café 📷.txt",
            Normalizer.normalize(root.listFiles()!!.single().name, Normalizer.Form.NFC),
        )
        assertEquals("Keep contents", decomposed.readText())
        val greek = File(root, "ΟΣ.txt").apply { writeText("Greek contents") }
        val greekResult = engine.rename(greek, "οσ.txt")
        assertEquals("Greek contents", greekResult.readText())
        assertTrue(root.listFiles()!!.any { it.name == "οσ.txt" })
    }

    @Test
    fun anUnselectedHardLinkIsNotMistakenForACaseAlias() {
        val root = temporary.newFolder("hardlink")
        val engine = FileEngine(temporary.newFolder("hardlink-state"))
        val source = File(root, "source.txt").apply { writeText("Keep contents") }
        val target = File(root, "occupied.txt")
        Files.createLink(target.toPath(), source.toPath())
        assertThrows(IllegalArgumentException::class.java) { engine.rename(source, target.name) }
        assertEquals("Keep contents", source.readText())
        assertEquals("Keep contents", target.readText())
        assertEquals(2, root.listFiles()!!.size)
    }

    @Test
    fun unchangedTargetsCannotBeOverwrittenAndCancellationRestoresStagedNames() {
        val root = temporary.newFolder("staged")
        val engine = FileEngine(temporary.newFolder("staged-state"))
        val a = File(root, "a.txt").apply { writeText("a") }
        val b = File(root, "b.txt").apply { writeText("b") }
        assertThrows(IllegalArgumentException::class.java) {
            engine.renameBatch(listOf(RenameProposal(a, b.name), RenameProposal(b, b.name)))
        }
        lateinit var control: TransferControl
        control = TransferControl { if (it.items == 2) control.cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            engine.renameBatch(
                listOf(RenameProposal(a, "new-a"), RenameProposal(b, "new-b")),
                control,
            )
        }
        assertEquals(setOf("a.txt", "b.txt"), root.listFiles()!!.map { it.name }.toSet())
        assertEquals("a", a.readText())
        assertEquals("b", b.readText())
    }

    @Test
    fun failedRollbackReturnsExactRecoveryRoutesWithoutOverwritingNewArrivals() {
        val root = temporary.newFolder("recovery")
        val engine = FileEngine(temporary.newFolder("recovery-state"))
        val a = File(root, "a.txt").apply { writeText("original-a") }
        val b = File(root, "b.txt").apply { writeText("original-b") }
        var arrived = false
        val control = TransferControl {
            if (it.items == 2 && !arrived) {
                arrived = true
                a.writeText("external-a")
                File(root, "new-b").writeText("external-b")
            }
        }
        val error =
            assertThrows(RenameRecoveryException::class.java) {
                engine.renameBatch(
                    listOf(RenameProposal(a, "new-a"), RenameProposal(b, "new-b")),
                    control,
                )
            }
        assertEquals(1, error.changes.size)
        assertEquals(a.path, error.changes.single().first)
        assertEquals("original-a", File(error.changes.single().second).readText())
        assertEquals("external-a", a.readText())
        assertEquals("original-b", b.readText())
        assertEquals("external-b", File(root, "new-b").readText())
        assertEquals("Recovered a.txt", File(error.changes.single().second).name)
    }

    @Test
    fun recoveryAndFieldBoundsPreserveUnicodeAndUsualExtensions() {
        val long = "旅📷".repeat(60) + ".jpg"
        val recovered = recoveredRenameName(long)
        assertTrue(recovered.startsWith("Recovered "))
        assertTrue(recovered.endsWith(".jpg"))
        assertTrue(recovered.toByteArray(Charsets.UTF_8).size <= 220)
        assertTrue(Charsets.UTF_8.newEncoder().canEncode(recovered))
        assertEquals("ab", renameFieldText("ab📷cd", 3))
        assertEquals("ab📷", renameFieldText("ab📷cd", 4))
    }

    @Test
    fun epochAndPreEpochDatesAreValidWhenTheSourceReportsThem() {
        val recipe =
            RenameRecipe(
                method = RenameMethod.TEMPLATE,
                template = "{modified:yyyyMMdd_HHmmss}{dotext}",
            )
        assertEquals("19700101_090000.txt", name(recipe, item("epoch.txt").copy(modified = 0)))
        assertEquals(
            "19691231_085959.txt",
            name(recipe, item("before.txt").copy(modified = -86_401_000)),
        )
    }

    @Test
    fun portableGreekSigmaAndExpandedCaseKeysMatchActualAliases() {
        assertEquals(renameCaselessKey("ος"), renameCaselessKey("οσ"))
        assertEquals(renameCaselessKey("straße"), renameCaselessKey("STRASSE"))
        val recipe = RenameRecipe(method = RenameMethod.CASE, letterCase = RenameLetterCase.LOWER)
        assertFalse(plan(recipe, listOf(item("ΟΣ"), item("οσ"))).canApply)
        assertTrue(
            plan(recipe.copy(portableConflicts = false), listOf(item("ΟΣ"), item("οσ"))).canApply,
        )
    }
}
