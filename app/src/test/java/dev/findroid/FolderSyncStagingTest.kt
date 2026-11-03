package dev.findroid

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FolderSyncStagingTest {
    private fun stamp(inode:Long, size:Long, folder:Boolean=false) = PartialFileStamp(1,inode,size,1,0,1,0,!folder,folder,if(folder)2 else 1)
    private fun sha(bytes:ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private data class Entry(var stamp:PartialFileStamp,var bytes:ByteArray)
    private inner class Storage(payload:String="reviewed bytes"):PartialCopyStorage {
        val entries=mutableMapOf("/source/File" to Entry(stamp(1,payload.toByteArray().size.toLong()),payload.toByteArray()),
            "/stage" to Entry(stamp(2,0,true),byteArrayOf()))
        var onRead:(String)->Unit={}
        var onOpen:(String)->Unit={}
        var onWrite:(String)->Unit={}
        var onSync:(String)->Unit={}
        var maximumWrite=Int.MAX_VALUE
        var corruptReadback=false
        var resolved="/stage"
        var created=0
        var reads=0
        var writes=0
        var closed=0
        var opened=0
        var synced=0
        var inode=10L
        override fun canonical(path:String)=if(path=="/stage")resolved else path
        override fun stat(path:String)=entries[path]?.stamp
        override fun open(path:String,writable:Boolean,createNew:Boolean,directory:Boolean):PartialCopyHandle {
            if(createNew) {
                check(writable&&!directory&&path !in entries)
                entries[path]=Entry(stamp(++inode,0),byteArrayOf());created++
            }
            val entry=entries[path]?:error("Missing fixture")
            check(entry.stamp.directory==directory)
            var position=0;var done=false;opened++;onOpen(path)
            return object:PartialCopyHandle {
                override fun stamp()=entry.stamp
                override fun read(buffer:ByteArray,offset:Int,count:Int):Int {
                    check(!done&&!directory);reads++
                    val amount=minOf(count,entry.bytes.size-position)
                    if(amount>0) {
                        entry.bytes.copyInto(buffer,offset,position,position+amount)
                        if(corruptReadback&&path=="/stage/Copy")buffer[offset]=(buffer[offset].toInt() xor 1).toByte()
                        position+=amount
                    }
                    onRead(path)
                    return if(amount==0)-1 else amount
                }
                override fun write(buffer:ByteArray,offset:Int,count:Int):Int {
                    check(!done&&writable&&!directory);writes++
                    val amount=minOf(count,maximumWrite)
                    val size=maxOf(entry.bytes.size,position+amount)
                    entry.bytes=entry.bytes.copyOf(size)
                    buffer.copyInto(entry.bytes,position,offset,offset+amount);position+=amount
                    entry.stamp=entry.stamp.copy(size=size.toLong(),changedNanos=entry.stamp.changedNanos+1)
                    onWrite(path);return amount
                }
                override fun seek(offset:Long) {fail("Preparation must not seek")}
                override fun truncate(size:Long) {fail("Preparation must not truncate")}
                override fun sync() {check(!done);synced++;onSync(path)}
                override fun close() {if(!done){done=true;closed++}}
            }
        }
        override fun createDirectory(path:String) {fail("File staging uses an already owned directory")}
        override fun moveNoReplace(existing:String,target:String) {fail("Preparation must not publish or move originals")}
        override fun remove(path:String) {fail("Preparation must preserve working data on failure")}
        fun spec()=FolderSyncCopySpec("/source/File",entries.getValue("/source/File").stamp,sha(entries.getValue("/source/File").bytes))
        fun stage(spec:FolderSyncCopySpec=spec(),control:TransferControl=TransferControl()) =
            stageFolderSyncFile(spec,"/stage/Copy",entries.getValue("/stage").stamp,"/stage",control,this)
    }
    private fun rejected(work:()->Unit) {
        try {work();fail("Expected preparation refusal")} catch(_:IllegalStateException) {}
    }
    @Test fun verifiedWorkingCopyPreservesTheSourceAndReadsBackEveryByte() {
        val storage=Storage();val source=storage.entries.getValue("/source/File").bytes.copyOf()
        val ready=storage.stage()
        assertArrayEquals(source,storage.entries.getValue("/source/File").bytes)
        assertArrayEquals(source,storage.entries.getValue("/stage/Copy").bytes)
        assertEquals(ready,storage.stat("/stage/Copy"));assertEquals(2,storage.synced)
        assertEquals(storage.opened,storage.closed)
    }
    @Test fun partialWritesAreCompletedBeforeVerification() {
        val storage=Storage();storage.maximumWrite=2
        storage.stage();assertTrue(storage.writes>1)
        assertArrayEquals(storage.entries.getValue("/source/File").bytes,storage.entries.getValue("/stage/Copy").bytes)
    }
    @Test fun emptyFilesStillGetSyncedAndVerified() {
        val storage=Storage("");assertEquals(0L,storage.stage().size)
        assertEquals(0,storage.writes);assertTrue(storage.reads>=2);assertEquals(2,storage.synced)
    }
    @Test fun anExistingWorkingNameIsNeverOpenedOrOverwritten() {
        val storage=Storage();val original=Entry(stamp(20,4),"keep".toByteArray());storage.entries["/stage/Copy"]=original
        rejected {storage.stage()};assertSame(original,storage.entries["/stage/Copy"]);assertEquals(0,storage.opened)
    }
    @Test fun staleSourceProofsFailBeforeCreatingAnyWorkingFile() {
        val storage=Storage();val old=storage.spec()
        storage.entries.getValue("/source/File").stamp=old.expected.copy(inode=99)
        rejected {storage.stage(old)};assertEquals(0,storage.created);assertEquals(0,storage.reads)
    }
    @Test fun aChangedPinnedSourceDescriptorFailsBeforeDestinationCreation() {
        val storage=Storage();storage.onOpen={if(it=="/source/File")storage.entries.getValue(it).stamp=storage.entries.getValue(it).stamp.copy(inode=99)}
        rejected {storage.stage()};assertEquals(0,storage.created);assertEquals(storage.opened,storage.closed)
    }
    @Test fun redirectedWorkingParentsFailBeforeSourceReads() {
        val storage=Storage();storage.resolved="/elsewhere"
        rejected {storage.stage()};assertEquals(0,storage.opened)
    }
    @Test fun aRewriteWithTheSameSizeAndMtimeCannotBecomeAWorkingCandidate() {
        val storage=Storage();storage.onRead={if(it=="/source/File")storage.entries.getValue(it).stamp=storage.entries.getValue(it).stamp.copy(changedNanos=7)}
        rejected {storage.stage()};assertTrue("The owned working data must remain available",storage.entries.containsKey("/stage/Copy"))
        assertEquals(storage.opened,storage.closed)
    }
    @Test fun reviewedContentHashesAreCheckedEvenWhenMetadataLooksUnchanged() {
        val storage=Storage();val spec=storage.spec().copy(sha256="0".repeat(64))
        rejected {storage.stage(spec)};assertEquals(0,storage.synced)
    }
    @Test fun aReplacedWorkingPathIsNeverAcceptedUsingItsOldOpenDescriptor() {
        val storage=Storage();storage.onWrite={storage.entries[it]=Entry(stamp(99,4),"keep".toByteArray())}
        rejected {storage.stage()};assertArrayEquals("keep".toByteArray(),storage.entries.getValue("/stage/Copy").bytes)
        assertEquals(storage.opened,storage.closed)
    }
    @Test fun readbackMismatchKeepsTheWorkingCopyUnpublished() {
        val storage=Storage();storage.corruptReadback=true
        rejected {storage.stage()};assertTrue(storage.entries.containsKey("/stage/Copy"));assertEquals(1,storage.synced)
    }
    @Test fun aSourceChangeDuringReadbackInvalidatesTheCandidate() {
        val storage=Storage();storage.onRead={if(it=="/stage/Copy")storage.entries.getValue("/source/File").let {e->e.stamp=e.stamp.copy(changedNanos=9)}}
        rejected {storage.stage()};assertEquals(1,storage.synced)
    }
    @Test fun cancellationBeforeAnyReadCreatesNoWorkingFile() {
        val storage=Storage();val control=TransferControl().apply {cancel()}
        try{storage.stage(control=control);fail("Expected cancellation")}catch(_:CancellationException){}
        assertEquals(0,storage.opened);assertEquals(0,storage.created)
    }
    @Test fun cancellationDuringCopyClosesBothDescriptorsAndKeepsWorkingData() {
        val storage=Storage();val control=TransferControl();storage.onWrite={control.cancel()}
        try{storage.stage(control=control);fail("Expected cancellation")}catch(_:CancellationException){}
        assertEquals(storage.opened,storage.closed);assertTrue(storage.entries.containsKey("/stage/Copy"))
    }
    @Test fun invalidOrLinkedSourceProofsNeverOpenFiles() {
        for(change in listOf<(PartialFileStamp)->PartialFileStamp>({it.copy(links=2)},{it.copy(regular=false)},{it.copy(size=-1)})) {
            val storage=Storage();rejected{storage.stage(storage.spec().let {it.copy(expected=change(it.expected))})};assertEquals(0,storage.opened)
        }
    }
    @Test fun aFolderReplacementDuringCopyPreventsVerificationAndDirectorySync() {
        val storage=Storage();storage.onWrite={storage.entries.getValue("/stage").let {e->e.stamp=e.stamp.copy(inode=99)}}
        rejected {storage.stage()};assertEquals(1,storage.synced);assertEquals(storage.opened,storage.closed)
    }
}
