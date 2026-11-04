package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class FolderSyncAccessTest {
    private val workspace = "/private/app/files/Workspace"
    private val working = "/private/app/files/.findroid-sync-11111111-1111-1111-1111-111111111111"
    private fun allowed(path: String) = path == workspace || path.startsWith("$workspace/") || path == "/shared" || path.startsWith("/shared/")
    private fun access(destination: String = workspace, run: String = working, canonical: (String) -> String = { it }) =
        folderSyncRunAccess(destination, run, workspace, ::allowed, canonical)
    @Test fun workspaceRootAllowsOnlyItsExactRunAndParentAlongsideOrdinaryAuthorizedPaths() {
        val permit = access()
        assertTrue(permit(workspace)); assertTrue(permit("$workspace/child")); assertTrue(permit("/shared/Document"))
        assertTrue(permit("/private/app/files")); assertTrue(permit(working)); assertTrue(permit("$working/copy-00000/file"))
        assertFalse(permit("/private/app")); assertFalse(permit("/private/app/files/secrets.json"))
        assertFalse(permit(working + "-unrelated")); assertFalse(permit(working.replace("11111111-", "22222222-")))
        assertFalse(permit("/private/app/files/Trash")); assertFalse(permit("/private/app/files/.undo-history"))
    }
    @Test fun scopedWorkingAccessRejectsTraversalAndRedirectedWorkingChildren() {
        assertFalse(access()("$working/../secrets.json"))
        assertFalse(access(canonical = { if (it.endsWith("/redirected")) "/private/app/files/secret" else it })("$working/redirected"))
    }
    @Test fun otherDestinationsDoNotAuthorizeAnyPrivateSibling() {
        val permit = access("/shared/Destination", "/shared/.findroid-sync-11111111-1111-1111-1111-111111111111")
        assertFalse(permit("/private/app/files")); assertFalse(permit(working))
        assertTrue(permit("/shared/Destination"))
    }
    @Test fun nestedWorkspaceTargetNeedsNoBroaderPrivateParentGrant() {
        val nested = "$workspace/.findroid-sync-11111111-1111-1111-1111-111111111111"
        val permit = access("$workspace/Folder", nested)
        assertTrue(permit(nested)); assertFalse(permit("/private/app/files")); assertFalse(permit(working))
    }
    @Test fun laterWorkspacePermissionLossRevokesItsSpecialSiblingGrant() {
        var connected = true
        val permit = folderSyncRunAccess(workspace, working, workspace, { connected && allowed(it) }, { it })
        assertTrue(permit(working)); connected = false
        assertFalse(permit(working)); assertFalse(permit("/private/app/files"))
    }
}
