package dev.findroid

internal enum class CommandRecovery(val label: String, val command: String) {
    STORAGE("Storage locations…", "storage"),
    TRANSFERS("View transfers", "transfers"),
    FOLDER("Choose a folder…", "quickJump"),
    COPY("Copy to another folder…", "copyTo"),
    OTHER("Show the other pane", "otherPane"),
    EXTRACT("Choose extraction folder…", "extractTo"),
}

internal data class CommandProblem(val reason: String, val recovery: CommandRecovery? = null)
internal data class CommandExplanation(val title: String, val problem: CommandProblem, val scope: String)

/** Facts already known by the browser; asking about a command never starts provider I/O. */
internal data class CommandContext(
    val selected: Int = 0,
    val selectedImages: Int = 0,
    val renameAllowed: Boolean = true,
    val removeAllowed: Boolean = true,
    val createAllowed: Boolean = true,
    val virtual: Boolean = false,
    val disconnected: Boolean = false,
    val loading: Boolean = false,
    val busy: String? = null,
    val clipboard: Boolean = false,
    val trash: Boolean = false,
    val undo: Boolean = false,
    val redo: Boolean = false,
    val archive: Boolean = false,
    val selectedFolder: Boolean = false,
    val selectedFolderWritable: Boolean = false,
    val searchActive: Boolean = false,
    val otherWritable: Boolean = false,
    val renameProblem: CommandProblem? = null,
    val removeProblem: CommandProblem? = null,
    val createProblem: CommandProblem? = null,
    val selectedFolderProblem: CommandProblem? = null,
)

private val selectedCommands = setOf("rename", "batchRename", "copy", "cut", "copyTo", "moveTo",
    "copyOtherPane", "moveOtherPane", "trash", "restore", "delete", "duplicate", "compress", "extract", "extractHere",
    "extractTo", "groupSelection", "shortcut", "share", "info", "copyPath", "pasteInto", "compareFiles")
private val serialCommands = setOf("new", "newText", "rename", "batchRename", "trash", "restore", "delete",
    "empty", "duplicate", "compress", "extract", "extractHere", "extractTo", "groupSelection", "shortcut", "undo", "redo", "tags", "collections", "appearance")
private val createCommands = setOf("new", "newText", "paste", "groupSelection")
private val removeCommands = setOf("cut", "moveTo", "moveOtherPane", "trash", "delete", "groupSelection")

internal fun commandProblem(command: String, context: CommandContext): CommandProblem? {
    val original = when (command) {
        "pasteVerified" -> "paste"
        "pasteIntoVerified" -> "pasteInto"
        "duplicateVerified" -> "duplicate"
        else -> command
    }
    if (original != command) return commandProblem(original, context)
    fun problem(reason: String, recovery: CommandRecovery? = null) = CommandProblem(reason, recovery)
    if (command == "compareFiles" && context.selected != 2) return problem("Select exactly two text files or two images to compare.")
    if (command == "imageReview" && (context.selected !in 1..ImageReviewSelection.LIMIT || context.selectedImages != context.selected))
        return problem("Select 1–200 images, without folders or other file types, to start a review.")
    if (command == "imageReview" && context.trash) return problem("Restore the images from Trash before reviewing candidates and tags.")
    if (command == "undo" && !context.undo) return problem("There is no action to undo in this session.")
    if (command == "redo" && !context.redo) return problem("There is no action to redo in this session.")
    if (command in selectedCommands && context.selected == 0) return problem("Select a file or folder first.")
    if (command in setOf("paste", "pasteInto") && !context.clipboard) return problem("Copy or cut files first, then choose where to paste them.")
    if (command in serialCommands && context.busy != null)
        return problem("Wait for the current operation to finish: ${context.busy}", CommandRecovery.TRANSFERS)
    if (context.disconnected && command in (selectedCommands - setOf("copy", "copyPath", "info") + createCommands + "imageReview"))
        return problem("This storage location is unavailable. Reconnect it before continuing.", CommandRecovery.STORAGE)
    if (context.trash && command in setOf("rename", "batchRename", "cut", "moveTo", "moveOtherPane", "trash", "duplicate", "compress", "groupSelection", "shortcut"))
        return problem("Restore the selected items from Trash before changing them.")
    if (command in createCommands && !context.createAllowed) return when {
        context.virtual -> problem("This is a library or search view. Choose a real folder for new files.", CommandRecovery.FOLDER)
        context.loading -> problem("Wait for this folder to finish loading, then try again.")
        else -> context.createProblem ?: problem("This folder does not allow creating files. Choose a writable folder or check its storage access.", CommandRecovery.STORAGE)
    }
    if (command in setOf("rename", "batchRename") && !context.renameAllowed)
        return context.renameProblem ?: problem("The selected storage does not allow renaming one or more of these items. You can copy them to a writable folder instead.", CommandRecovery.COPY)
    if (command in removeCommands && !context.removeAllowed)
        return context.removeProblem ?: problem("The selected storage does not allow removing one or more originals. You can copy them instead.", CommandRecovery.COPY)
    if (command == "pasteInto" && (context.selected != 1 || !context.selectedFolder))
        return problem("Select one destination folder first.")
    if (command == "pasteInto" && !context.selectedFolderWritable)
        return context.selectedFolderProblem ?: problem("The selected folder does not allow creating files. Choose another destination or check its storage access.", CommandRecovery.STORAGE)
    if (command in setOf("copyOtherPane", "moveOtherPane") && !context.otherWritable)
        return problem("The other pane needs an available, writable folder before it can receive files.", CommandRecovery.OTHER)
    if (command in setOf("extract", "extractHere", "extractTo") && !context.archive)
        return problem("Select one ZIP archive first.")
    if (command == "extractHere" && (!context.createAllowed || context.searchActive))
        return problem("Choose an available, writable destination for the extracted files.", CommandRecovery.EXTRACT)
    if (command in setOf("duplicate", "shortcut") && !context.virtual && !context.createAllowed)
        return problem("This folder does not allow creating the new item. Check storage access or copy to a different folder.", CommandRecovery.COPY)
    return null
}

internal fun FinderModel.commandProblem(command: String): CommandProblem? {
    val command = when (command) {
        "pasteVerified" -> "paste"
        "pasteIntoVerified" -> "pasteInto"
        "duplicateVerified" -> "duplicate"
        else -> command
    }
    val entries = actionEntries
    val roots = if (command in setOf("rename", "batchRename") || command in removeCommands)
        connectedLocations.map { it.uri }.toSet() else emptySet()
    val renameDenied = if (command in setOf("rename", "batchRename"))
        entries.firstOrNull { !it.canRename || (it.path in roots && it.parentPath == null) } else null
    val removeDenied = if (command in removeCommands)
        entries.firstOrNull { !it.canDelete || it.path in roots } else null
    val createProblem = if (!canCreate && command in createCommands)
        folderInfo?.let { storageCommandProblem(it, StorageAction.CREATE) } else null
    val folderProblem = if (command == "pasteInto") entries.singleOrNull()?.takeIf { it.folder && !it.writable }
        ?.let { storageCommandProblem(it, StorageAction.CREATE) } else null
    return commandProblem(command, CommandContext(
        selected = entries.size,
        selectedImages = if (command == "imageReview") entries.count { it.reviewableImage } else 0,
        renameAllowed = renameDenied == null, removeAllowed = removeDenied == null,
        createAllowed = canCreate, virtual = folderPath == null, disconnected = folderStorageIssue != null || (listingError != null && !loading),
        loading = loading, busy = busy, clipboard = clipboard != null, trash = isTrash,
        undo = undoLabel != null, redo = redoLabel != null,
        archive = entries.singleOrNull()?.let { !it.folder && it.extension == "zip" } == true,
        selectedFolder = entries.singleOrNull()?.folder == true,
        searchActive = query.isNotBlank() || effectiveSearchRules.active,
        selectedFolderWritable = entries.singleOrNull()?.let { it.folder && it.writable } == true,
        otherWritable = command in setOf("copyOtherPane", "moveOtherPane") && this in windowState.panes && otherPane.canCreate,
        renameProblem = renameDenied?.let { storageCommandProblem(it, StorageAction.RENAME) },
        removeProblem = removeDenied?.let { storageCommandProblem(it, StorageAction.DELETE) },
        createProblem = createProblem, selectedFolderProblem = folderProblem,
    ))
}

internal fun commandTitle(command: String): String = when (command) {
    "new" -> "New folder"; "newText" -> "New text file"; "rename", "batchRename" -> "Rename"
    "copy", "copyTo", "copyOtherPane" -> "Copy"; "cut" -> "Cut"; "paste", "pasteInto" -> "Paste"
    "pasteVerified", "pasteIntoVerified" -> "Paste and verify"; "duplicateVerified" -> "Duplicate and verify"
    "moveTo", "moveOtherPane" -> "Move"; "trash" -> "Move to Trash"; "restore" -> "Restore"
    "delete" -> "Delete permanently"; "empty" -> "Empty Trash"; "duplicate" -> "Duplicate"
    "compress" -> "Compress"; "extract", "extractHere", "extractTo" -> "Extract"; "groupSelection" -> "New folder with selection"
    "tags" -> "Tags"; "collections" -> "Collections"; "appearance" -> "Customize folder"
    "compareFiles" -> "Compare files"
    "imageReview" -> "Review images"
    "availableActions" -> "Available actions"; "shortcut" -> "Make shortcut"; "undo" -> "Undo"; "redo" -> "Redo"; "share" -> "Share"
    else -> "This command"
}
