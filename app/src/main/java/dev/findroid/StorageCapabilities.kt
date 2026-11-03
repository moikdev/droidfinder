package dev.findroid

import android.provider.DocumentsContract.Document

/** Evidence belongs to this document alone, never all children of a connected root. */
data class ProviderAccessEvidence(
    val path: String,
    val flags: Int?,
    val savedReadGrant: Boolean?,
    val savedWriteGrant: Boolean?,
)

internal enum class StorageAction(val label: String) {
    READ("Read"),
    CREATE("Create items"),
    WRITE("Change contents"),
    RENAME("Rename"),
    DELETE("Delete"),
    TRASH("Move to Trash"),
}

internal enum class ActionSupport(val label: String) {
    AVAILABLE("Reported available"),
    UNAVAILABLE("Unavailable"),
    UNKNOWN("Not reported"),
    NOT_APPLICABLE("Does not apply"),
}

/** Only facts captured by a background load. Presentation never probes a path or provider. */
internal data class StorageAccessFacts(
    val path: String,
    val name: String,
    val folder: Boolean,
    val provider: Boolean,
    val loaded: Boolean,
    val readable: Boolean? = null,
    val writable: Boolean? = null,
    val rename: Boolean? = null,
    val delete: Boolean? = null,
    val document: ProviderAccessEvidence? = null,
    val unavailable: String? = null,
    val inTrash: Boolean = false,
    val restrictions: Map<StorageAction, String> = emptyMap(),
)

internal data class StorageActionDetail(
    val action: StorageAction,
    val support: ActionSupport,
    val reason: String,
    val recovery: CommandRecovery? = null,
)

internal fun storageActionDetail(facts: StorageAccessFacts, action: StorageAction): StorageActionDetail {
    fun answer(support: ActionSupport, reason: String, recovery: CommandRecovery? = null) =
        StorageActionDetail(action, support, reason, recovery)
    if (action == StorageAction.CREATE && !facts.folder)
        return answer(ActionSupport.NOT_APPLICABLE, "Choose a destination folder to create files or folders.")
    if (action == StorageAction.WRITE && facts.folder)
        return answer(ActionSupport.NOT_APPLICABLE, "Create items describes adding children to this folder. Change contents applies to files.")
    if (action == StorageAction.TRASH && facts.inTrash)
        return answer(ActionSupport.NOT_APPLICABLE, "This item is already in Findroid's Trash. Restore it or review permanent deletion.")
    if (facts.inTrash && (action == StorageAction.CREATE || action == StorageAction.WRITE || action == StorageAction.RENAME))
        return answer(ActionSupport.UNAVAILABLE, "Restore this item from Trash before changing it.")
    val restriction = facts.restrictions[action]
        ?: if (action == StorageAction.TRASH) facts.restrictions[StorageAction.DELETE] else null
    restriction?.let { return answer(ActionSupport.UNAVAILABLE, it, CommandRecovery.STORAGE) }
    facts.unavailable?.let { return answer(ActionSupport.UNAVAILABLE, it, CommandRecovery.STORAGE) }
    if (!facts.loaded)
        return answer(ActionSupport.UNKNOWN, "Folder information has not finished loading. Refresh to check this item.", CommandRecovery.STORAGE)
    val evidence = facts.document?.takeIf { it.path == facts.path }
    if (facts.provider && evidence == null)
        return answer(ActionSupport.UNKNOWN, "No capabilities were loaded for this document. A connected folder's capabilities do not describe its children.", CommandRecovery.STORAGE)
    if (action == StorageAction.READ) {
        if (facts.provider) {
            return if (evidence?.savedReadGrant == true)
                answer(ActionSupport.AVAILABLE, "Android reports saved read permission. The provider checks document access when it is opened.")
            else answer(ActionSupport.UNKNOWN, "No saved read permission is reported for this document. Temporary access may work; the provider checks access when opened.", CommandRecovery.STORAGE)
        }
        return when (facts.readable) {
            true -> answer(ActionSupport.AVAILABLE, "The local filesystem reported read access when this item was loaded.")
            false -> answer(ActionSupport.UNAVAILABLE, "The local filesystem did not report read access. Check storage access or reconnect the drive.", CommandRecovery.STORAGE)
            null -> answer(ActionSupport.UNKNOWN, "Read access has not been checked. Refresh this folder.", CommandRecovery.STORAGE)
        }
    }
    val effective = if (action == StorageAction.TRASH) StorageAction.DELETE else action
    val allowed = when (effective) {
        StorageAction.CREATE, StorageAction.WRITE -> facts.writable
        StorageAction.RENAME -> facts.rename
        StorageAction.DELETE -> facts.delete
        else -> null
    }
    val copyRecovery = if (facts.readable == false || facts.inTrash) CommandRecovery.STORAGE else CommandRecovery.COPY
    val recovery = if ((effective == StorageAction.RENAME || effective == StorageAction.DELETE)) copyRecovery else CommandRecovery.STORAGE
    val verb = when (effective) {
        StorageAction.CREATE -> "creating children in this folder"
        StorageAction.WRITE -> "changing this file's contents"
        StorageAction.RENAME -> "renaming this item"
        StorageAction.DELETE -> "deleting this item"
        else -> error("Unexpected storage action")
    }
    if (facts.provider) {
        if (evidence?.savedWriteGrant == false)
            return answer(ActionSupport.UNAVAILABLE, "Android's saved folder permission is read only. It does not allow $verb.", recovery)
        val flags = evidence?.flags
            ?: return answer(ActionSupport.UNKNOWN, "The provider did not report document capability flags. Support for $verb is unknown.", recovery)
        val flag = when (effective) {
            StorageAction.CREATE -> Document.FLAG_DIR_SUPPORTS_CREATE
            StorageAction.WRITE -> Document.FLAG_SUPPORTS_WRITE
            StorageAction.RENAME -> Document.FLAG_SUPPORTS_RENAME
            StorageAction.DELETE -> Document.FLAG_SUPPORTS_DELETE
            else -> error("Unexpected storage action")
        }
        if (flags and flag == 0)
            return answer(ActionSupport.UNAVAILABLE, "The provider does not advertise support for $verb on this document.", recovery)
    }
    if (allowed != true) {
        val reason = if (allowed == null) "Current access for $verb has not been loaded. Refresh this folder."
            else if (facts.provider) "The loaded access information does not allow $verb. Reconnect or refresh storage access."
            else "The local filesystem did not report the write access used for $verb. Check storage permissions or choose another folder."
        return answer(if (allowed == null) ActionSupport.UNKNOWN else ActionSupport.UNAVAILABLE, reason, recovery)
    }
    if (action == StorageAction.TRASH) {
        val reason = if (facts.provider)
            "Findroid first saves a local recovery copy, then deletes the provider original. Read access and local space are checked before removal. Restoring copies back and may create new document addresses; the provider's own Trash is not used."
        else "Findroid moves this item to its Trash. The operation checks the source and recovery destination before changing files."
        return answer(ActionSupport.AVAILABLE, reason)
    }
    return answer(ActionSupport.AVAILABLE, if (facts.provider)
        "The provider reports support for $verb and the loaded access information allows it. The operation checks again before changes."
    else "The local filesystem reported the access used for $verb when this item was loaded. The operation checks again before changes.")
}

internal data class StorageAccessExample(val name: String, val path: String, val detail: StorageActionDetail)

internal data class StorageActionSummary(
    val action: StorageAction,
    val total: Int,
    val available: Int,
    val unavailable: Int,
    val unknown: Int,
    val notApplicable: Int,
    val examples: List<StorageAccessExample>,
)

/** Keep displayed examples bounded even for a large selection. */
internal fun storageActionSummary(facts: List<StorageAccessFacts>, action: StorageAction): StorageActionSummary {
    var available = 0
    var unavailable = 0
    var unknown = 0
    var notApplicable = 0
    val examples = mutableListOf<StorageAccessExample>()
    facts.forEach { item ->
        val detail = storageActionDetail(item, action)
        when (detail.support) {
            ActionSupport.AVAILABLE -> available++
            ActionSupport.UNAVAILABLE -> unavailable++
            ActionSupport.UNKNOWN -> unknown++
            ActionSupport.NOT_APPLICABLE -> notApplicable++
        }
        fun priority(support: ActionSupport) = when (support) {
            ActionSupport.UNAVAILABLE -> 0
            ActionSupport.UNKNOWN -> 1
            ActionSupport.NOT_APPLICABLE -> 2
            ActionSupport.AVAILABLE -> 3
        }
        if (detail.support != ActionSupport.AVAILABLE &&
            (examples.size < 3 || priority(detail.support) < priority(examples.last().detail.support))) {
            val insertion = examples.indexOfFirst { priority(it.detail.support) > priority(detail.support) }
                .takeIf { it >= 0 } ?: examples.size
            examples.add(insertion, StorageAccessExample(item.name, item.path, detail))
            if (examples.size > 3) examples.removeAt(examples.lastIndex)
        }
    }
    return StorageActionSummary(action, facts.size, available, unavailable, unknown, notApplicable, examples)
}
