package dev.droidfinder

import org.junit.Assert.*
import org.junit.Test

class StorageStateTest {
    private val root = "/storage/emulated/0"

    private fun blocked(path: String) =
        protectedStoragePath(path, listOf(root, "/storage/ABCD-1234"), "dev.droidfinder")

    @Test
    fun protectedDirectoriesRespectRootAndPackageBoundaries() {
        for (base in listOf(root, "/storage/ABCD-1234")) {
            for (name in listOf("data", "obb", "DATA")) {
                assertTrue(blocked("$base/Android/$name"))
                assertTrue(blocked("$base/Android/$name/another.app/cache"))
                assertTrue(blocked("$base/Android/$name/dev.droidfinder.extra/file"))
                assertFalse(blocked("$base/Android/$name/dev.droidfinder/files"))
            }
            assertFalse(blocked("$base/Android/media/another.app/photo.jpg"))
            assertFalse(blocked("$base/Android/database"))
            assertFalse(blocked("$base/Documents/Android/data"))
        }
        assertFalse(blocked("${root}1/Android/data/another.app"))
        assertFalse(blocked("/data/user/0/dev.droidfinder/files/Workspace"))
        assertTrue(blocked("$root/Documents/../Android/data/another.app"))
        assertFalse(blocked("$root/Android/data/../../Documents"))
    }

    @Test
    fun treeIdentityPreservesOpaqueIdsAndLiteralPlus() {
        val plain = "content://Provider.EXAMPLE/tree/primary%3ADocuments%2FA%2BB"
        val child =
            "content://provider.example/tree/primary%3aDocuments%2fA+B/document/opaque%2Fchild"
        assertEquals("provider.example" to "primary:Documents/A+B", documentTreeKey(plain))
        assertEquals(documentTreeKey(plain), documentTreeKey(child))
        assertNotEquals(
            documentTreeKey(plain),
            documentTreeKey(plain.replace("Documents", "documents")),
        )
        assertNotEquals(
            documentTreeKey(plain),
            documentTreeKey(plain.replace("Provider.EXAMPLE", "other")),
        )
        assertEquals(
            "provider" to "目录/é",
            documentTreeKey("content://provider/tree/%E7%9B%AE%E5%BD%95%2F%C3%A9"),
        )
        for (bad in
            listOf(
                "/tree/id",
                "file://provider/tree/id",
                "content://provider/document/tree",
                "content://provider/tree/",
                "content://provider/tree/%ZZ",
            )) assertNull(bad, documentTreeKey(bad))
    }

    @Test
    fun recoveryDoesNotConfuseOfflineWithMissingPermission() {
        assertEquals(StorageHealth.GRANT_REQUIRED, storageFailure(StorageKind.PROVIDER, true))
        assertEquals(
            StorageHealth.PROVIDER_MISSING,
            storageFailure(StorageKind.PROVIDER, true, false),
        )
        assertEquals(StorageHealth.UNAVAILABLE, storageFailure(StorageKind.PROVIDER, false))
        assertEquals(StorageHealth.NEEDS_ACCESS, storageFailure(StorageKind.TABLET, true))
        val target = StorageTarget("id", "/path", "Folder", StorageKind.TABLET)
        for (health in StorageHealth.entries) {
            assertEquals(
                health.name,
                health in
                    setOf(StorageHealth.AVAILABLE, StorageHealth.READ_ONLY, StorageHealth.LIMITED),
                StorageStatus(target, health).canOpen,
            )
        }
    }
}
