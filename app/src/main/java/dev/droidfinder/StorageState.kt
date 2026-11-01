package dev.droidfinder

import java.nio.file.Paths

internal enum class StorageKind {
    WORKSPACE,
    TABLET,
    VOLUME,
    PROVIDER,
}

internal enum class StorageHealth(val label: String) {
    CHECKING("Checking…"),
    AVAILABLE("Available"),
    READ_ONLY("Read only"),
    LIMITED("Limited access"),
    NEEDS_ACCESS("Access needed"),
    GRANT_REQUIRED("Reconnect needed"),
    UNAVAILABLE("Unavailable"),
    PROVIDER_MISSING("Provider unavailable"),
    STOPPED("Check stopped"),
}

internal data class StorageTarget(
    val id: String,
    val path: String,
    val name: String,
    val kind: StorageKind,
    val state: String? = null,
    val grant: String? = null,
)

internal data class StorageStatus(
    val target: StorageTarget,
    val health: StorageHealth,
    val detail: String = "",
    val available: Long? = null,
    val total: Long? = null,
    val create: Boolean = false,
    val rename: Boolean = false,
    val delete: Boolean = false,
    val provider: String? = null,
    val grantNote: String? = null,
) {
    val canOpen
        get() =
            health in setOf(StorageHealth.AVAILABLE, StorageHealth.READ_ONLY, StorageHealth.LIMITED)
}

internal data class StorageIssue(
    val title: String,
    val detail: String,
    val root: ConnectedLocation? = null,
    val needsAccess: Boolean = false,
    val protected: Boolean = false,
)

internal data class ReconnectChoice(val original: ConnectedLocation, val selected: String)

/** Shared-root checks use normalized/canonical paths, never opaque third-party document IDs. */
internal fun protectedStoragePath(
    path: String,
    sharedRoots: List<String>,
    packageName: String,
): Boolean {
    val p = Paths.get(path).normalize().toString()
    val root =
        sharedRoots
            .map { Paths.get(it).normalize().toString().trimEnd('/') }
            .filter { p == it || p.startsWith("$it/") }
            .maxByOrNull { it.length } ?: return false
    val parts = p.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
    if (
        parts.size < 2 ||
            !parts[0].equals("Android", true) ||
            parts[1].lowercase() !in setOf("data", "obb")
    )
        return false
    return parts.getOrNull(2) != packageName
}

internal fun storageFailure(
    kind: StorageKind,
    permission: Boolean,
    providerExists: Boolean = true,
): StorageHealth =
    when {
        kind == StorageKind.PROVIDER && !providerExists -> StorageHealth.PROVIDER_MISSING
        kind == StorageKind.PROVIDER && permission -> StorageHealth.GRANT_REQUIRED
        permission -> StorageHealth.NEEDS_ACCESS
        else -> StorageHealth.UNAVAILABLE
    }

internal fun documentTreeKey(path: String): Pair<String, String>? = runCatching {
    val uri = java.net.URI(path)
    require(uri.scheme.equals("content", true) && !uri.authority.isNullOrBlank())
    val parts = uri.rawPath.split('/')
    val i = parts.indexOf("tree")
    require(i >= 0 && i + 1 < parts.size && parts[i + 1].isNotEmpty())
    uri.authority.lowercase(java.util.Locale.ROOT) to
        java.net.URLDecoder.decode(parts[i + 1].replace("+", "%2B"), "UTF-8")
}
    .getOrNull()
