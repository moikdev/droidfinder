package dev.findroid

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

internal class FolderSyncNioStorage:PartialCopyStorage {
        var afterWrite:(String)->Unit={}
        var overrideStamp:(String,PartialFileStamp)->PartialFileStamp={_,stamp->stamp}
        override fun canonical(path:String)=File(path).canonicalPath
        override fun stat(path:String):PartialFileStamp? {
            if(!Files.exists(File(path).toPath(),LinkOption.NOFOLLOW_LINKS))return null
            val values=Files.readAttributes(File(path).toPath(),"unix:*",LinkOption.NOFOLLOW_LINKS)
            fun time(key:String)=(values.getValue(key) as FileTime).to(TimeUnit.NANOSECONDS)
            val modified=time("lastModifiedTime");val changed=time("ctime")
            return overrideStamp(path,PartialFileStamp((values.getValue("dev") as Number).toLong(),(values.getValue("ino") as Number).toLong(),
                (values.getValue("size") as Number).toLong(),modified/1_000_000_000,modified%1_000_000_000,
                changed/1_000_000_000,changed%1_000_000_000,values.getValue("isRegularFile") as Boolean,
                values.getValue("isDirectory") as Boolean,(values.getValue("nlink") as Number).toLong()))
        }
        override fun open(path:String,writable:Boolean,createNew:Boolean,directory:Boolean):PartialCopyHandle {
            check(stat(path)?.let {it.regular||it.directory}!=false)
            val channel=if(directory)null else FileChannel.open(File(path).toPath(),buildSet {
                add(StandardOpenOption.READ);if(writable)add(StandardOpenOption.WRITE)
                if(createNew)add(StandardOpenOption.CREATE_NEW)
            })
            return object:PartialCopyHandle {
                override fun stamp()=stat(path)?:error("Removed fixture descriptor")
                override fun read(buffer:ByteArray,offset:Int,count:Int)=channel!!.read(ByteBuffer.wrap(buffer,offset,count))
                override fun write(buffer:ByteArray,offset:Int,count:Int):Int {
                    val written=channel!!.write(ByteBuffer.wrap(buffer,offset,count));afterWrite(path);return written
                }
                override fun seek(offset:Long) {channel!!.position(offset)}
                override fun truncate(size:Long) {channel!!.truncate(size)}
                override fun sync() {channel?.force(true)}
                override fun close() {channel?.close()}
            }
        }
        override fun createDirectory(path:String) {Files.createDirectory(File(path).toPath())}
        override fun moveNoReplace(existing:String,target:String) {fail("Preparation must never publish any destination")}
        override fun remove(path:String) {fail("Preparation must preserve originals and working data")}
    }
internal class FolderSyncNioProofs(private val storage:FolderSyncNioStorage):UndoHistoryFiles {
        var beforeRename:(File,File)->Unit={_,_->}
        var failFinalSync=false
        override fun observe(path:String):UndoPathProof {
            val parent=File(path).parentFile!!;val identity=storage.stat(parent.path)!!
            val entries=Files.walk(File(path).toPath()).use {nodes->nodes.sorted().toList().associate {it.toString() to storage.stat(it.toString())!!}}
            return UndoPathProof(path,storage.canonical(path),parent.path,parent.canonicalPath,identity.device,identity.inode,entries)
        }
        override fun sync(paths:Set<String>) {check(!failFinalSync){"Synthetic directory-sync fault after rename"}}
        override fun syncDirectory(directory:File) {}
        override fun moveNoReplace(from:File,to:File) {
            beforeRename(from,to)
            if(Files.exists(to.toPath(),LinkOption.NOFOLLOW_LINKS))throw java.nio.file.FileAlreadyExistsException(to.path)
            Files.move(from.toPath(),to.toPath())
        }
    }
