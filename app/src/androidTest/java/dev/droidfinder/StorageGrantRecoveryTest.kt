package dev.droidfinder

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in bridge for the documented real DocumentsUI grant/revoke/cold-start/reconnect check. */
class StorageGrantRecoveryTest {
    @Test
    fun restrictExplicitSyntheticTreeToReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("storageQaReadOnly") == "true")
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents/DroidFinder-Storage-QA",
            )
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
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents/DroidFinder-Storage-QA",
            )
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
}
