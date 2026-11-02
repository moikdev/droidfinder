package dev.findroid

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PhotoDetails(
    val fields: List<Pair<String, String>>,
    val coordinates: Pair<Double, Double>?,
) {
    fun text(includeLocation: Boolean = false) =
        (fields +
                if (includeLocation && coordinates != null)
                    listOf(
                        "Location" to
                            String.format(
                                Locale.ROOT,
                                "%.6f, %.6f",
                                coordinates.first,
                                coordinates.second,
                            ),
                    )
                else emptyList())
            .joinToString("\n") { "${it.first}: ${it.second}" }
}

internal data class ImagePatch(val region: ImagePixels, val sample: Int, val bitmap: Bitmap)

internal data class ImageRequest(val region: ImagePixels, val sample: Int)

private val imageDecodeGate = Mutex()
private val imageCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Owns one native region decoder. Closing is non-blocking on the UI thread and serialized with
 * reads.
 */
internal class ImageSource
private constructor(
    val file: File,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val overview: Bitmap,
    val details: PhotoDetails,
    private var decoder: BitmapRegionDecoder?,
) {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val stamp = file.length() to file.lastModified()
    val regionSupported = decoder != null

    fun close() {
        if (closed.compareAndSet(false, true))
            imageCleanup.launch {
                synchronized(lock) {
                    decoder?.recycle()
                    decoder = null
                }
            }
    }

    suspend fun patch(request: ImageRequest): ImagePatch? = imageDecodeGate.withLock {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                check(!closed.get()) { "Image preview closed." }
                check(stamp == (file.length() to file.lastModified())) {
                    "The image changed. Reload this preview."
                }
                val d = decoder ?: return@synchronized null
                val r = request.region
                require(
                    r.left >= 0 &&
                        r.top >= 0 &&
                        r.right <= width &&
                        r.bottom <= height &&
                        r.width > 0 &&
                        r.height > 0 &&
                        request.sample > 0 &&
                        imageSample(r.width, r.height, request.sample) == request.sample,
                ) {
                    "Invalid or oversized image region."
                }
                val bitmap =
                    d.decodeRegion(Rect(r.left, r.top, r.right, r.bottom), options(request.sample))
                        ?: error("Couldn't decode this image region.")
                check(!closed.get()) { "Image preview closed." }
                check(
                    bitmap.width.toLong() * bitmap.height <= 4_194_304L &&
                        bitmap.width <= 4096 &&
                        bitmap.height <= 4096,
                ) {
                    "Image decoder exceeded the detail budget."
                }
                ImagePatch(r, request.sample, bitmap)
            }
        }
    }

    companion object {
        suspend fun open(file: File, control: ListingControl): ImageSource {
            var prepared: ImageSource? = null
            try {
                return imageDecodeGate.withLock {
                    withContext(Dispatchers.IO) {
                        control.check()
                        require(file.length() <= 512L * 1024 * 1024) {
                            "This image exceeds the 512 MiB local preview limit. Open it in another app."
                        }
                        val bounds =
                            BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                                inScaled = false
                            }
                        BitmapFactory.decodeFile(file.path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                            "Couldn't read the image header."
                        }
                        val geometry = ImageGeometry(bounds.outWidth, bounds.outHeight)
                        control.check()
                        val exif = runCatching { ExifInterface(file) }.getOrNull()
                        val orientation =
                            exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)?.takeIf {
                                it in 1..8
                            } ?: 1
                        val sample =
                            imageSample(
                                geometry.rawWidth,
                                geometry.rawHeight,
                                pixels = 1_048_576,
                                maxEdge = 2048,
                            )
                        val bitmap =
                            BitmapFactory.decodeFile(file.path, options(sample))
                                ?: error("Couldn't decode this image.")
                        check(
                            bitmap.width.toLong() * bitmap.height <= 1_048_576 &&
                                bitmap.width <= 2048 &&
                                bitmap.height <= 2048,
                        ) {
                            "Image decoder exceeded the overview budget."
                        }
                        control.check()
                        var region: BitmapRegionDecoder? = null
                        try {
                            region =
                                try {
                                    @Suppress("DEPRECATION")
                                    BitmapRegionDecoder.newInstance(file.path, false)
                                } catch (_: java.io.IOException) {
                                    null
                                }
                            control.check()
                            ImageSource(
                                    file,
                                    geometry.rawWidth,
                                    geometry.rawHeight,
                                    orientation,
                                    bitmap,
                                    photoDetails(file, bounds, exif, orientation),
                                    region,
                                )
                                .also { prepared = it }
                        } catch (error: Throwable) {
                            region?.recycle()
                            throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                prepared?.close()
                throw error
            }
        }

        private fun options(sample: Int) =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
    }
}

private fun photoDetails(
    file: File,
    bounds: BitmapFactory.Options,
    exif: ExifInterface?,
    orientation: Int,
): PhotoDetails {
    val fields = mutableListOf<Pair<String, String>>()
    fun add(label: String, value: String?) {
        value
            ?.filter { !it.isISOControl() }
            ?.trim()
            ?.take(256)
            ?.takeIf { it.isNotEmpty() }
            ?.let { fields += label to it }
    }
    fun attr(tag: String) = exif?.getAttribute(tag)
    fun number(tag: String) =
        exif?.getAttributeDouble(tag, Double.NaN)?.takeIf { it.isFinite() && it > 0 }
    val oriented = ImageGeometry(bounds.outWidth, bounds.outHeight, orientation)
    add("Dimensions", "${oriented.width} × ${oriented.height} pixels")
    if (orientation in 5..8) add("Stored pixels", "${bounds.outWidth} × ${bounds.outHeight}")
    add("File size", formatSize(file.length()))
    add("Format", bounds.outMimeType)
    add("Color space", bounds.outColorSpace?.name)
    add(
        "Orientation",
        when (orientation) {
            2 -> "Mirrored horizontally"
            3 -> "180°"
            4 -> "Mirrored vertically"
            5 -> "Transposed"
            6 -> "90° clockwise"
            7 -> "Transverse"
            8 -> "90° counterclockwise"
            else -> "Normal"
        },
    )
    val make = attr(ExifInterface.TAG_MAKE).orEmpty().trim()
    val model = attr(ExifInterface.TAG_MODEL).orEmpty().trim()
    add(
        "Camera",
        if (model.startsWith(make, true)) model
        else listOf(make, model).filter { it.isNotBlank() }.joinToString(" "),
    )
    add("Lens", attr(ExifInterface.TAG_LENS_MODEL))
    attr(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
        add(
            "Captured",
            it +
                attr(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                    ?.let { offset -> " $offset" }
                    .orEmpty(),
        )
    }
    number(ExifInterface.TAG_F_NUMBER)?.let {
        add("Aperture", String.format(Locale.ROOT, "ƒ/%.1f", it))
    }
    number(ExifInterface.TAG_EXPOSURE_TIME)?.let {
        add(
            "Exposure",
            if (it < 1) "1/${kotlin.math.round(1/it).toLong()} s"
            else String.format(Locale.ROOT, "%.2f s", it),
        )
    }
    add("ISO", attr(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
    number(ExifInterface.TAG_FOCAL_LENGTH)?.let {
        add("Focal length", String.format(Locale.ROOT, "%.1f mm", it))
    }
    add("Copyright", attr(ExifInterface.TAG_COPYRIGHT))
    add("Artist", attr(ExifInterface.TAG_ARTIST))
    val coordinates = runCatching { exif?.latLong?.let { it[0] to it[1] } }.getOrNull()
    return PhotoDetails(fields, coordinates)
}
