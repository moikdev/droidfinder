package dev.findroid

import java.io.File

private fun insideSyncRoot(path:String,root:String)=path==root||path.startsWith(root.trimEnd('/')+"/")
private fun syncShapeUnchanged(before:UndoPathProof,after:UndoPathProof)=before.copy(entries=emptyMap())==after.copy(entries=emptyMap())

/**
 * A kernel rename can update the root ctime. Every other attribute, descendant, and content
 * hash must still match the old proof. This never substitutes freshly collected identities
 * for unreviewed files and cannot approve a recursive copy assembled after the review.
 */
internal fun checkFolderSyncRelocation(
    beforeRoot:String,
    before:Map<String,PartialFileStamp>,
    after:UndoPathProof,
    contents:Map<String,String>,
    digest:(String,PartialFileStamp)->String,
) {
    check(before[beforeRoot]!=null && before.keys.all {insideSyncRoot(it,beforeRoot)} &&
        contents.keys==before.filterValues {it.regular}.keys && contents.values.all { value ->
            value.length==64 && value.all {it in '0'..'9'||it in 'a'..'f'}
        }) {"The reviewed relocation proof is incomplete."}
    val expected=before.mapKeys { (path,_) -> after.path+path.removePrefix(beforeRoot) }
    check(after.entries.keys==expected.keys) {"The moved tree contains missing or unreviewed files. Original data was kept for review."}
    for((path,stamp) in expected) {
        val actual=after.entries.getValue(path)
        check(stamp.regular!=stamp.directory && (stamp.directory||stamp.links==1L) &&
            if(path==after.path)actual.copy(changedSeconds=stamp.changedSeconds,changedNanos=stamp.changedNanos)==stamp else actual==stamp) {
            "A moved item no longer has its reviewed identity. Original data was kept for review."
        }
        if(stamp.regular) {
            val original=beforeRoot+path.removePrefix(after.path)
            check(digest(path,actual)==contents.getValue(original)) {"Moved file contents differ from the reviewed copy. Keep the recovery data and compare again."}
        }
    }
}

/** Checks the declared tree shape. Relocated bytes also require checkFolderSyncRelocation. */
internal fun checkFolderSyncTreeChange(
    before:UndoPathProof,
    after:UndoPathProof,
    target:String,
    published:UndoPathProof?=null,
) {
    check(syncShapeUnchanged(before,after) && target!=before.path && insideSyncRoot(target,before.path) &&
        File(target).isAbsolute && File(target).toPath().normalize().toString()==target) {
        "The synchronization destination or its parent changed. Original files were kept."
    }
    val ancestors=generateSequence(File(target).parentFile) {it.parentFile}.map {it.path}.takeWhile {insideSyncRoot(it,before.path)}.toSet()
    check(before.path in ancestors && ancestors.all {before.entries[it]?.directory==true}) {"A destination parent is missing from the reviewed tree."}
    val removed=before.entries.filterKeys {insideSyncRoot(it,target)}
    check(if(published==null)removed.isNotEmpty() else removed.isEmpty()) {"A destination name no longer matches the reviewed operation."}
    val additions=published?.let { proof ->
        check(proof.entries[proof.path]!=null && proof.entries.keys.all {insideSyncRoot(it,proof.path)}) {"The prepared publication proof is incomplete."}
        val parent=before.entries.getValue(File(target).parentFile!!.path)
        check(proof.entries.values.all {it.device==parent.device && it.regular!=it.directory && (it.directory||it.links==1L)}) {"The prepared tree cannot be renamed on this storage."}
        proof.entries.mapKeys {(path,_) -> target+path.removePrefix(proof.path)}
    }?:emptyMap()
    val kept=before.entries-removed.keys
    check(after.entries.keys==kept.keys+additions.keys) {"Unreviewed files arrived or an unrelated item disappeared. Completed files were kept."}
    for((path,stamp) in kept) {
        val actual=after.entries.getValue(path)
        check(if(path in ancestors)actual.sameObject(stamp) else actual==stamp) {"An unrelated destination item changed. Completed files were kept."}
    }
    for((path,stamp) in additions) {
        val actual=after.entries.getValue(path)
        check(if(path==target)actual.copy(changedSeconds=stamp.changedSeconds,changedNanos=stamp.changedNanos)==stamp else actual==stamp) {
            "The published tree no longer matches its prepared identity. Completed files were kept."
        }
    }
}
