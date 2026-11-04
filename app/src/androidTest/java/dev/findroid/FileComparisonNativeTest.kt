package dev.findroid

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import dev.findroid.testing.FixtureDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class FileComparisonNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    @Before fun setup() { folder = File(context.cacheDir,"compare-native-${System.nanoTime()}").apply { check(mkdirs()) } }
    @After fun cleanup() { check(folder.deleteTreeWithoutLinks()) }
    private fun jpeg(name: String, w: Int, h: Int): File {
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        return File(folder,name).also { it.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG,90,out) };bitmap.recycle() }
    }
    @Test fun imageDecoderSamplesAndAppliesExifOrientationWithoutWritingSource() {
        val file=jpeg("oriented.jpg",3072,1024)
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes() }
        val original=file.readBytes()
        val image=readComparisonImage(file) {}
        try {
            assertEquals(1024,image.width);assertEquals(3072,image.height);assertTrue(image.sampled)
            assertEquals(512,image.bitmap.width);assertEquals(1536,image.bitmap.height)
            assertTrue(image.bitmap.width.toLong()*image.bitmap.height<=FileComparisonPolicy.imagePixels)
            assertArrayEquals(original,file.readBytes())
        } finally { image.bitmap.recycle() }
    }
    @Test fun corruptAndOversizedImagesRefuseWithoutChangingBytes() {
        val bad=File(folder,"bad.png").apply { writeText("not an image") };val before=bad.readBytes()
        assertThrows(IllegalArgumentException::class.java) { readComparisonImage(bad) {} }
        assertArrayEquals(before,bad.readBytes())
        val large=File(folder,"large.png")
        java.io.RandomAccessFile(large,"rw").use { it.setLength(FileComparisonPolicy.imageBytes+1) }
        assertThrows(IllegalArgumentException::class.java) { readComparisonImage(large) {} }
    }
    @Test fun localLoadReopensTextAndRefusesMixedFormats() = runBlocking {
        val a=File(folder,"before.txt").apply { writeText("first\n") }
        val b=File(folder,"after.txt").apply { writeText("other\n") }
        val gateway=DocumentAccess(context)
        val targets=listOf(a,b).map { FileComparisonTarget(it.path,it.name) }
        assertEquals(1,loadFileComparison(targets,gateway,{true},ListingControl()).text!!.changes.size)
        b.writeText("first\n")
        assertTrue(loadFileComparison(targets,gateway,{true},ListingControl()).text!!.changes.isEmpty())
        val image=jpeg("photo.jpg",100,80)
        try { loadFileComparison(listOf(targets[0],FileComparisonTarget(image.path,image.name)),gateway,{true},ListingControl());fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("two plain text")) }
    }
    @Test fun missingUnauthorizedAndRepeatedFilesAreRefused() = runBlocking {
        val a=File(folder,"a.txt").apply { writeText("same") };val b=File(folder,"b.txt").apply { writeText("same") }
        val targets=listOf(a,b).map { FileComparisonTarget(it.path,it.name) };val gateway=DocumentAccess(context)
        try { loadFileComparison(targets,gateway,{false},ListingControl());fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("Reconnect")) }
        try { loadFileComparison(listOf(targets[0],targets[0]),gateway,{true},ListingControl());fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("different")) }
        assertTrue(b.delete())
        try { loadFileComparison(targets,gateway,{true},ListingControl());fail() } catch (_: Exception) { }
    }
    @Test fun mimeOnlyUnknownSizeProviderImagesBypassStaleCacheAndRemoveTemporaryCopies() = runBlocking {
        val provider=File(context.filesDir,"ProviderFixtures/CompareNative-${System.nanoTime()}").apply { check(mkdirs()) }
        val a=jpeg("source.jpg",120,80).copyTo(File(provider,"First"));val b=a.copyTo(File(provider,"Second"))
        FixtureDocumentsProvider.mimeOverrides=listOf(a,b).associate { it.canonicalPath to "image/jpeg" }
        FixtureDocumentsProvider.unknownSizePaths=setOf(a.canonicalPath,b.canonicalPath)
        try {
            val gateway=DocumentAccess(context)
            val uri=DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures","root/${provider.name}")
            val entries=gateway.list(uri.toString()).sortedBy { it.name }
            assertTrue(entries.all { !it.sizeKnown&&it.extension.isEmpty()&&it.comparisonType=="image" })
            entries.forEach { it.file.parentFile!!.mkdirs();it.file.writeText("deliberately stale cached preview") }
            val targets=entries.map { FileComparisonTarget(it.path,it.name) }
            val first=loadFileComparison(targets,gateway,{true},ListingControl())
            try { assertTrue(Color.blue(first.images[0].bitmap.getPixel(20,20))>240) } finally { first.images.forEach { it.bitmap.recycle() } }
            val oldModified=a.lastModified()
            val replacement=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            a.outputStream().use { replacement.compress(Bitmap.CompressFormat.JPEG,90,it) };replacement.recycle();check(a.setLastModified(oldModified))
            val next=loadFileComparison(targets,gateway,{true},ListingControl())
            try { assertTrue(Color.red(next.images[0].bitmap.getPixel(20,20))>240);assertTrue(Color.blue(next.images[0].bitmap.getPixel(20,20))<20) }
            finally { next.images.forEach { it.bitmap.recycle() } }
            entries.forEach { entry -> assertFalse(entry.file.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("comparison-") }) }
        } finally {
            FixtureDocumentsProvider.mimeOverrides=emptyMap();FixtureDocumentsProvider.unknownSizePaths=emptySet()
            check(provider.deleteTreeWithoutLinks())
        }
    }
}
