package dev.findroid

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in bridge for the documented real DocumentsUI grant/revoke/cold-start/reconnect check. */
class StorageGrantRecoveryTest {
    private fun syntheticRelativePath(): String {
        val requested = InstrumentationRegistry.getArguments().getString("storageQaRoot")
            ?: return "Documents/Findroid-Storage-QA"
        require(Regex("Documents/Findroid actions demo [0-9]{10,20}").matches(requested)) {
            "Custom grant acceptance is limited to a uniquely named synthetic folder"
        }
        return requested
    }

    private fun syntheticTree() = DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents", "primary:${syntheticRelativePath()}",
    )

    @Test
    fun grantExplicitSyntheticTreeThroughSystemPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("storageQaGrant") == "true")
        val tree = syntheticTree()
        val document = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        instrumentation.runOnMainSync {
            // Exercise the existing reconnect launch and result callback, starting at the exact
            // synthetic tree. This does not inject a permission or modify DocumentsUI state.
            MainActivity::class.java.getDeclaredMethod("chooseFolder", ConnectedLocation::class.java)
                .apply { isAccessible = true }
                .invoke(activity, ConnectedLocation(document.toString(), tree.toString(), syntheticRelativePath().substringAfterLast('/')))
        }
        fun find(text: String): android.view.accessibility.AccessibilityNodeInfo? =
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString() == text && it.isVisibleToUser }
        fun waitFor(description: String, condition: () -> Boolean) {
            val end = android.os.SystemClock.uptimeMillis() + 20_000
            while (!condition()) {
                check(android.os.SystemClock.uptimeMillis() < end) { "Timed out: $description" }
                Thread.sleep(100)
            }
        }
        waitFor("system picker at the synthetic folder") {
            find(syntheticRelativePath().substringAfterLast('/'))?.packageName?.toString()?.endsWith("documentsui") == true &&
                find("USE THIS FOLDER")?.isEnabled == true
        }
        assertTrue(find("USE THIS FOLDER")!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("system folder grant confirmation") { find("ALLOW") != null || find("Allow") != null }
        assertTrue((find("ALLOW") ?: find("Allow"))!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("persisted read/write grant in Findroid") {
            instrumentation.targetContext.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            }
        }
        assertTrue(instrumentation.targetContext.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        })
    }

    @Test
    fun restrictExplicitSyntheticTreeToReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("storageQaReadOnly") == "true")
        val tree = syntheticTree()
        val resolver = instrumentation.targetContext.contentResolver
        assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
        resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val grant = resolver.persistedUriPermissions.single { it.uri == tree }
        assertTrue(grant.isReadPermission)
        assertFalse(grant.isWritePermission)
    }

    @Test
    fun revokeExplicitSyntheticTreeOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("storageQaRevoke") == "true")
        val tree = syntheticTree()
        val resolver = instrumentation.targetContext.contentResolver
        assertTrue(
            "Grant the synthetic QA folder through DocumentsUI first",
            resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission },
        )
        resolver.releasePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        assertFalse(resolver.persistedUriPermissions.any { it.uri == tree })
    }
    @Test
    fun releaseOnlySyntheticTransferProviderPermission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("transferQaGrantCleanup") == "true")
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.transferqa.documents", "root")
        fun unrelated() = resolver.persistedUriPermissions.filterNot { it.uri == tree }.associate {
            it.uri to (it.isReadPermission to it.isWritePermission)
        }
        val before = unrelated()
        if (resolver.persistedUriPermissions.any { it.uri == tree }) {
            resolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        assertFalse("Only the owned synthetic provider grant is released", resolver.persistedUriPermissions.any { it.uri == tree })
        assertTrue("Unrelated saved folder permissions must stay unchanged", before == unrelated())
    }

    @Test
    fun auditPersistedPermissionsForSyntheticAcceptance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("storageQaAudit") == "true")
        syntheticRelativePath() // Reject arbitrary custom acceptance roots.
        val permissions = org.json.JSONObject()
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.persistedUriPermissions
            .sortedBy { it.uri.toString() }.forEach { permission ->
                permissions.put(permission.uri.toString(), org.json.JSONArray()
                    .put(permission.isReadPermission).put(permission.isWritePermission))
            }
        // The optimized checker keeps this output only in memory and publishes boolean comparisons.
        println("FINDROID_GRANT_AUDIT:" + android.util.Base64.encodeToString(
            permissions.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
    }
}
