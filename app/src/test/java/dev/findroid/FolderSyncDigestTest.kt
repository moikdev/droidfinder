package dev.findroid

import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FolderSyncDigestTest {
    private fun stamp(size:Long)=PartialFileStamp(1,2,size,1,0,1,0,true,false,1)
    private class Storage(val bytes:ByteArray, initial:PartialFileStamp):PartialCopyStorage {
        var pathStamp=initial
        var descriptorStamp=initial
        var opened=0
        var reads=0
        var closed=false
        var onOpen:()->Unit={}
        var onRead:()->Unit={}
        override fun canonical(path:String)=path
        override fun stat(path:String)=pathStamp
        override fun open(path:String,writable:Boolean,createNew:Boolean,directory:Boolean):PartialCopyHandle {
            check(!writable&&!createNew&&!directory);opened++;onOpen()
            val stream=ByteArrayInputStream(bytes)
            return object:PartialCopyHandle {
                override fun stamp()=descriptorStamp
                override fun read(buffer:ByteArray,offset:Int,count:Int):Int {
                    check(!closed);reads++;val read=stream.read(buffer,offset,count);onRead();return read
                }
                override fun seek(offset:Long) { fail("Digest must not seek") }
                override fun write(buffer:ByteArray,offset:Int,count:Int):Int { fail("Digest must not write"); return 0 }
                override fun truncate(size:Long) { fail("Digest must not truncate") }
                override fun sync() { fail("Digest must not sync") }
                override fun close() {closed=true;stream.close()}
            }
        }
        override fun createDirectory(path:String) { fail("Digest must not create folders") }
        override fun moveNoReplace(existing:String,target:String) { fail("Digest must not move files") }
        override fun remove(path:String) { fail("Digest must not remove files") }
    }
    private fun rejected(work:()->Unit) {
        try {work();fail("Expected a file review refusal")} catch(_:IllegalStateException) { }
    }
    @Test fun aPinnedRegularFileProducesItsStandardContentChecksum() {
        val original="abc".toByteArray();val expected=stamp(3);val storage=Storage(original.copyOf(),expected)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",folderSyncDigest("/source/Item",expected,TransferControl(),storage))
        assertArrayEquals(original,storage.bytes);assertTrue(storage.closed)
    }
    @Test fun anEmptyFileStillChecksItsDescriptorAndEndOfFile() {
        val expected=stamp(0);val storage=Storage(byteArrayOf(),expected)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",folderSyncDigest("/source/Empty",expected,TransferControl(),storage))
        assertEquals(1,storage.reads);assertTrue(storage.closed)
    }
    @Test fun aReplacementBeforeOpenIsRefusedWithoutReadingIt() {
        val expected=stamp(3);val storage=Storage("abc".toByteArray(),expected.copy(inode=99))
        rejected {folderSyncDigest("/source/Item",expected,TransferControl(),storage)}
        assertEquals(0,storage.opened);assertEquals(0,storage.reads)
    }
    @Test fun descriptorIdentityIsCheckedBeforeTheFirstByte() {
        val expected=stamp(3);val storage=Storage("abc".toByteArray(),expected)
        storage.onOpen={storage.descriptorStamp=expected.copy(inode=99)}
        rejected {folderSyncDigest("/source/Item",expected,TransferControl(),storage)}
        assertEquals(0,storage.reads);assertTrue(storage.closed)
    }
    @Test fun shorterDataAndAdditionalTailBytesAreBothRefused() {
        for (payload in listOf("ab","abcd")) {
            val expected=stamp(3);val storage=Storage(payload.toByteArray(),expected)
            rejected {folderSyncDigest("/source/Item",expected,TransferControl(),storage)}
            assertTrue(storage.closed)
        }
    }
    @Test fun aSameSizeRewriteWithRestoredMtimeIsStillRefused() {
        val expected=stamp(3);val storage=Storage("abc".toByteArray(),expected)
        storage.onRead={storage.pathStamp=expected.copy(changedNanos=1);storage.descriptorStamp=storage.pathStamp}
        rejected {folderSyncDigest("/source/Item",expected,TransferControl(),storage)}
        assertTrue(storage.closed)
    }
    @Test fun replacingOnlyThePathWhileItsOldDescriptorIsOpenIsRefused() {
        val expected=stamp(3);val storage=Storage("abc".toByteArray(),expected)
        storage.onRead={storage.pathStamp=expected.copy(inode=99)}
        rejected {folderSyncDigest("/source/Item",expected,TransferControl(),storage)}
        assertTrue(storage.closed)
    }
    @Test fun specialLinkedOrInvalidFileProofsNeverOpenADescriptor() {
        val good=stamp(3)
        for (expected in listOf(good.copy(regular=false),good.copy(directory=true),good.copy(links=2),good.copy(size=-1))) {
            val storage=Storage("abc".toByteArray(),expected)
            rejected {folderSyncDigest("/source/Item",expected,TransferControl(),storage)}
            assertEquals(0,storage.opened)
        }
    }
    @Test fun cancellationBeforeOpenReadsNothing() {
        val expected=stamp(3);val storage=Storage("abc".toByteArray(),expected);val control=TransferControl().apply {cancel()}
        try {folderSyncDigest("/source/Item",expected,control,storage);fail("Expected cancellation")} catch(_:CancellationException) { }
        assertEquals(0,storage.opened)
    }
    @Test fun cancellationDuringChunkedReadsClosesThePinnedDescriptor() {
        val expected=stamp(150_000);val storage=Storage(ByteArray(150_000),expected);val control=TransferControl()
        storage.onRead={control.cancel()}
        try {folderSyncDigest("/source/Item",expected,control,storage);fail("Expected cancellation")} catch(_:CancellationException) { }
        assertEquals(1,storage.reads);assertTrue(storage.closed)
    }
}
