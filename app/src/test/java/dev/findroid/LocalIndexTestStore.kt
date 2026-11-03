package dev.findroid

/** A deliberately small store for scanner/admission tests; SQLite guarantees are tested natively. */
internal class LocalIndexTestStore : LocalIndexStore {
    val catalog = linkedMapOf<String, LocalIndexRoot>()
    val generations = mutableMapOf<String, MutableList<IndexRecord>>()
    val batches = mutableListOf<Int>()
    override fun roots() = catalog.values.toList()
    override fun add(path: String) = LocalIndexRoot(newIndexId(), path).also { catalog[it.id] = it }
    override fun begin(rootId: String): IndexBuildToken {
        val root = catalog.getValue(rootId)
        val token = IndexBuildToken(root.id, root.path, newIndexId(), 1)
        generations[token.generation] = mutableListOf()
        catalog[root.id] = root.copy(state = IndexRefreshState.BUILDING, attempt = token.generation, attemptOwner = root.id, attemptStarted = 1)
        return token
    }
    override fun append(token: IndexBuildToken, records: List<IndexRecord>) { batches += records.size; generations.getValue(token.generation).addAll(records) }
    override fun complete(token: IndexBuildToken, finished: Long, entries: Int, checkActive: () -> Unit) {
        checkActive()
        catalog[token.rootId] = catalog.getValue(token.rootId).copy(generation = token.generation, entries = entries,
            scannedFrom = token.started, refreshedAt = finished, state = IndexRefreshState.READY, attempt = null, attemptOwner = null)
    }
    override fun abandon(token: IndexBuildToken, state: IndexRefreshState, entries: Int, reason: String?) {
        catalog[token.rootId] = catalog.getValue(token.rootId).copy(state = state, attempt = null, attemptOwner = null, attemptedEntries = entries, reason = reason)
    }
    override fun acquire(anchor: String): LocalIndexLease? {
        val root = roots().filter { it.hasSnapshot && indexWithin(anchor, it.path) }.maxByOrNull { it.path.length } ?: return null
        if (anchor != root.path && generations[root.generation].orEmpty().none { it.path == anchor && it.folder }) return null
        return object : LocalIndexLease {
            override val root = root
            override suspend fun visit(anchor: String, recursive: Boolean, control: ListingControl, each: suspend (IndexRecord) -> Boolean) {
                for (row in generations[root.generation].orEmpty()) {
                    check(); control.check()
                    if ((if (recursive) indexWithin(row.path, anchor) && row.path != anchor else row.parent == anchor) && !each(row)) break
                }
            }
            override fun check() { check(catalog.containsKey(root.id)) }
            override fun close() {}
        }
    }
    override fun markChanged(paths: List<String>, now: Long) {}
    override fun remove(rootId: String) { catalog.remove(rootId) }
    override fun deleteCache() { catalog.clear(); generations.clear() }
    override fun storageBytes() = 0L
    override fun close() {}
}
