package dev.findroid

import java.net.URI

internal const val SEARCH_LOCATION_LIMIT = 10

/** Validate stored references without probing storage or dropping disconnected folders. */
internal fun checkedSearchLocations(values: List<String>): List<String> {
    val copy = values.toList()
    require(copy.size <= SEARCH_LOCATION_LIMIT) { "Choose up to $SEARCH_LOCATION_LIMIT search locations." }
    require(copy.distinct().size == copy.size) { "This search location is already chosen." }
    require(copy.all { path ->
        path.length in 1..8192 && path.none(Char::isISOControl) && Charsets.UTF_8.newEncoder().canEncode(path) &&
            (path.startsWith("/") || path.startsWith("content://") &&
                runCatching { !URI(path).authority.isNullOrBlank() }.getOrDefault(false))
    }) { "Choose a folder on this tablet or a connected storage provider." }
    return copy
}
