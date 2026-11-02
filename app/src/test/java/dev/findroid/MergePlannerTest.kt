package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class MergePlannerTest {
    private val tree =
        mapOf(
            "source" to
                listOf(
                    MergeNode("source/A.txt", "A.txt", false),
                    MergeNode("source/Shared", "Shared", true),
                    MergeNode("source/New", "New", true),
                ),
            "target" to
                listOf(
                    MergeNode("target/Shared", "Shared", true),
                    MergeNode("target/Extra.txt", "Extra.txt", false),
                ),
            "source/Shared" to listOf(MergeNode("source/Shared/B.txt", "B.txt", false)),
            "target/Shared" to emptyList(),
        )

    @Test
    fun mergesExistingFolderPairsAndKeepsNewSubtreesAtomic() {
        val calls = mutableListOf<String>()
        val plan =
            planMergeMembers(
                "source",
                "target",
                {
                    calls += it
                    tree.getValue(it)
                },
            )
        assertEquals(
            listOf(
                MergeMember("source/A.txt", "target"),
                MergeMember("source/New", "target"),
                MergeMember("source/Shared/B.txt", "target/Shared"),
            ),
            plan,
        )
        assertFalse("source/New" in calls)
    }

    @Test
    fun emptyFoldersHaveAnEmptyButCompletePlan() {
        assertTrue(planMergeMembers("source", "target", { emptyList() }).isEmpty())
    }

    @Test
    fun rejectsCyclesDuplicateDestinationsAndOversizedPlans() {
        assertTrue(
            runCatching {
                planMergeMembers(
                    "source",
                    "target",
                    {
                        if (it == "source") listOf(MergeNode("source", "Again", true))
                        else listOf(MergeNode("target", "Again", true))
                    },
                )
            }
                .isFailure,
        )
        assertTrue(
            runCatching {
                planMergeMembers(
                    "source",
                    "target",
                    {
                        if (it == "source") emptyList()
                        else listOf(MergeNode("a", "Same", false), MergeNode("b", "Same", false))
                    },
                )
            }
                .isFailure,
        )
        assertTrue(
            runCatching { planMergeMembers("source", "target", { tree.getValue(it) }, limit = 1) }
                .isFailure,
        )
    }

    @Test
    fun cancelledPlansStopBeforeListingOrWriting() {
        val control = TransferControl().apply { cancel() }
        var listed = false
        assertTrue(
            runCatching {
                planMergeMembers(
                    "source",
                    "target",
                    {
                        listed = true
                        emptyList()
                    },
                    control,
                )
            }
                .isFailure,
        )
        assertFalse(listed)
    }

    @Test
    fun symbolicSourceAndPairedDestinationLinksAreRejected() {
        assertTrue(
            runCatching {
                planMergeMembers(
                    "source",
                    "target",
                    {
                        if (it == "source") listOf(MergeNode("source/Link", "Link", true, true))
                        else emptyList()
                    },
                )
            }
                .isFailure,
        )
        assertTrue(
            runCatching {
                planMergeMembers(
                    "source",
                    "target",
                    {
                        if (it == "source") listOf(MergeNode("source/Shared", "Shared", true))
                        else listOf(MergeNode("target/Shared", "Shared", true, true))
                    },
                )
            }
                .isFailure,
        )
    }
}
