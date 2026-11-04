package dev.findroid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class FileComparisonTarget(val path: String, val name: String)
internal data class ComparisonImage(val bitmap: Bitmap, val width: Int, val height: Int, val sampled: Boolean)
internal data class FileComparisonResult(val text: TextComparison? = null, val images: List<ComparisonImage> = emptyList())

internal val Entry.comparisonType: String?
    get() = when {
        folder -> null
        fileType(extension, mime).group == FileTypeGroup.IMAGE -> "image"
        officeFormat != null -> null
        structuredKind != null -> "text"
        else -> null
    }

internal fun readComparisonImage(file: File, check: () -> Unit): ComparisonImage {
    check()
    require(file.length() <= FileComparisonPolicy.imageBytes) { "Image comparison supports files up to 64 MiB each." }
    val stamp = file.length() to file.lastModified()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Couldn't decode this image. Its format may be unsupported or damaged." }
    val geometry = ImageGeometry(bounds.outWidth, bounds.outHeight)
    check()
    val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    val sample = imageSample(geometry.width, geometry.height, pixels = FileComparisonPolicy.imagePixels, maxEdge = FileComparisonPolicy.imageEdge)
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inScaled = false; inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: error("Couldn't decode this image. Its format may be unsupported or damaged.")
    var oriented: Bitmap? = null
    var complete = false
    try {
        check()
        require(bitmap.width.toLong() * bitmap.height <= FileComparisonPolicy.imagePixels &&
            maxOf(bitmap.width, bitmap.height) <= FileComparisonPolicy.imageEdge) { "The image decoder exceeded the comparison preview budget." }
        val t = ImageGeometry(bitmap.width, bitmap.height, orientation).transform
        val matrix = Matrix().apply { setValues(floatArrayOf(t.a.toFloat(), t.b.toFloat(), t.x.toFloat(), t.c.toFloat(), t.d.toFloat(), t.y.toFloat(), 0f, 0f, 1f)) }
        oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        val dimensions = ImageGeometry(geometry.width, geometry.height, orientation)
        check()
        require(stamp == (file.length() to file.lastModified())) { "An image changed while it was read. Compare again." }
        return ComparisonImage(oriented, dimensions.width, dimensions.height, sample > 1).also { complete = true }
    } catch (error: Throwable) {
        if (oriented !== bitmap) oriented?.recycle()
        throw error
    } finally {
        if (!complete || oriented !== bitmap) bitmap.recycle()
    }
}

/** Each request reopens both documents. Text bypasses the preview cache entirely. */
internal suspend fun loadFileComparison(
    targets: List<FileComparisonTarget>, gateway: DocumentAccess, allowed: (String) -> Boolean, control: ListingControl,
): FileComparisonResult = withContext(Dispatchers.IO) {
    val job = currentCoroutineContext()
    fun check() { control.check(); job.ensureActive() }
    require(targets.size == 2 && targets.map { it.path }.distinct().size == 2) { "Select exactly two different text files or two images to compare." }
    require(targets.all { allowed(it.path) }) { "Reconnect both file locations before comparing." }
    val entries = targets.map { gateway.read(it.path).also { check() } }
    val type = entries.first().comparisonType
    require(type != null && entries.all { it.comparisonType == type }) { "Choose two plain text/code files, or two images. Office documents, PDFs, folders and mixed formats need another comparison tool." }
    fun unchanged() {
        check()
        entries.forEach { entry ->
            val now = gateway.read(entry.path)
            require(now.name == entry.name && now.folder == entry.folder && now.size == entry.size &&
                now.sizeKnown == entry.sizeKnown && now.modified == entry.modified && now.mime == entry.mime) {
                "A file changed while it was read. Compare again."
            }
        }
        check()
    }
    if (type == "text") {
        val texts = entries.map { entry -> gateway.input(entry.path, control).use { readComparisonText(it, ::check) } }
        val result = compareText(texts[0], texts[1], ::check)
        unchanged()
        FileComparisonResult(text = result)
    } else {
        val images = ArrayList<ComparisonImage>()
        try {
            entries.forEach { entry ->
                if (entry.documentUri == null) images += readComparisonImage(entry.file, ::check)
                else CacheLeases.pin(entry.file.parentFile!!).use {
                    // Use a fresh, owned temporary copy: provider timestamps and sizes can be stale.
                    val directory = entry.file.parentFile!!.apply { check(mkdirs() || isDirectory) }
                    val file = File.createTempFile("comparison-", ".image", directory)
                    try {
                        gateway.input(entry.path, control).use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                check()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= FileComparisonPolicy.imageBytes) { "Image comparison supports files up to 64 MiB each." }
                                output.write(buffer, 0, n)
                            }
                        } }
                        images += readComparisonImage(file, ::check)
                    } finally { file.delete() }
                }
            }
            unchanged()
            FileComparisonResult(images = images)
        } catch (error: Throwable) { images.forEach { it.bitmap.recycle() }; throw error }
    }
}
