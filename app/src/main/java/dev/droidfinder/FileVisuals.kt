package dev.droidfinder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Blue = Color(0xFF2878E8)

fun tagColor(name: String?): Color =
    when (name) {
        "Red" -> Color(0xFFEA6465)
        "Orange" -> Color(0xFFED9B51)
        "Yellow" -> Color(0xFFE6C443)
        "Green" -> Color(0xFF65B68A)
        "Blue" -> Blue
        "Purple" -> Color(0xFFA580CE)
        else -> Color(0xFF7B8491)
    }

@Composable
fun FolderGlyph(size: Dp = 64.dp, color: Color? = null, symbol: String = "") {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val back =
                Path().apply {
                    moveTo(w * .08f, h * .26f)
                    quadraticTo(w * .08f, h * .18f, w * .16f, h * .18f)
                    lineTo(w * .39f, h * .18f)
                    lineTo(w * .48f, h * .28f)
                    lineTo(w * .85f, h * .28f)
                    quadraticTo(w * .93f, h * .28f, w * .93f, h * .36f)
                    lineTo(w * .93f, h * .8f)
                    lineTo(w * .08f, h * .8f)
                    close()
                }
            drawPath(back, color?.copy(alpha = .85f) ?: Color(0xFF389BD7))
            drawRoundRect(
                Color(0xFFBCEDFF),
                Offset(w * .14f, h * .33f),
                Size(w * .72f, h * .35f),
                CornerRadius(w * .025f),
            )
            drawRoundRect(
                Brush.verticalGradient(
                    if (color == null) listOf(Color(0xFF7BD4F4), Color(0xFF4CB1E6))
                    else listOf(color.copy(alpha = .65f), color),
                ),
                Offset(w * .06f, h * .4f),
                Size(w * .9f, h * .46f),
                CornerRadius(w * .045f),
            )
            drawLine(
                Color(0xFFA4E5FF),
                Offset(w * .1f, h * .41f),
                Offset(w * .91f, h * .41f),
                w * .012f,
            )
        }
        if (symbol.isNotEmpty() && size >= 35.dp)
            Text(symbol, Modifier.offset(y = size * .12f), fontSize = (size.value * .29f).sp)
    }
}

@Composable
fun FileGlyph(entry: Entry, size: Dp = 44.dp, thumbnail: Boolean = true) {
    if (entry.folder) {
        FolderGlyph(size, entry.style?.let { tagColor(it.color) }, entry.style?.symbol.orEmpty())
        return
    }
    val context = LocalContext.current
    val fileFamily = fileType(entry.extension, entry.mime).group
    val bitmap =
        if (thumbnail && fileFamily == FileTypeGroup.IMAGE) {
            val request =
                ThumbnailKey(entry.path, entry.name, entry.modified, entry.size, entry.mime)
            key(request) {
                val value by
                    produceState<Bitmap?>(null, request) {
                        value =
                            (context.applicationContext as FinderApplication)
                                .thumbnails
                                .get(request)
                    }
                value
            }
        } else null
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            entry.name,
            Modifier.size(size).padding(3.dp).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        DocumentGlyph(entry, size)
    }
}

@Composable
private fun DocumentGlyph(entry: Entry, size: Dp) {
    val type = fileType(entry.extension, entry.mime)
    val (color, symbol) =
        when (type.group) {
            FileTypeGroup.PDF ->
                Color(0xFFD64F4F) to
                    if (size < 40.dp) Icons.Outlined.PictureAsPdf
                    else Icons.AutoMirrored.Outlined.Subject
            FileTypeGroup.ARCHIVE -> Color(0xFFB58035) to Icons.Outlined.FolderZip
            FileTypeGroup.IMAGE -> Color(0xFF427FAD) to Icons.Outlined.Image
            FileTypeGroup.VIDEO -> Color(0xFF8760BD) to Icons.Outlined.PlayArrow
            FileTypeGroup.AUDIO -> Color(0xFFCC5E89) to Icons.Outlined.MusicNote
            FileTypeGroup.TABLE -> Color(0xFF388A68) to Icons.Outlined.TableChart
            FileTypeGroup.PRESENTATION -> Color(0xFFCC7547) to Icons.Outlined.Slideshow
            FileTypeGroup.CODE -> Color(0xFF5975AC) to Icons.Outlined.Code
            FileTypeGroup.ANDROID -> Color(0xFF528E67) to Icons.Outlined.Android
            FileTypeGroup.SHORTCUT -> Color(0xFF687EAA) to Icons.Outlined.Link
            FileTypeGroup.TEXT -> Color(0xFF527FB7) to Icons.AutoMirrored.Outlined.Subject
            FileTypeGroup.UNKNOWN ->
                Color(0xFF7C8799) to Icons.AutoMirrored.Outlined.InsertDriveFile
        }
    val small = size < 40.dp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val left = w * .17f
            val right = w * .83f
            val top = h * .06f
            val bottom = h * .94f
            val fold = w * .20f
            val radius = w * .045f
            val page =
                Path().apply {
                    moveTo(left + radius, top)
                    lineTo(right - fold, top)
                    lineTo(right, top + fold)
                    lineTo(right, bottom - radius)
                    quadraticTo(right, bottom, right - radius, bottom)
                    lineTo(left + radius, bottom)
                    quadraticTo(left, bottom, left, bottom - radius)
                    lineTo(left, top + radius)
                    quadraticTo(left, top, left + radius, top)
                    close()
                }
            drawPath(page, Color.White)
            drawPath(
                page,
                color.copy(alpha = .42f),
                style =
                    androidx.compose.ui.graphics.drawscope.Stroke(
                        width = (w * .018f).coerceAtLeast(1f),
                    ),
            )
            val corner =
                Path().apply {
                    moveTo(right - fold, top)
                    lineTo(right - fold, top + fold - radius)
                    quadraticTo(right - fold, top + fold, right - fold + radius, top + fold)
                    lineTo(right, top + fold)
                    close()
                }
            drawPath(corner, color.copy(alpha = .16f))
            drawLine(
                color.copy(alpha = .35f),
                Offset(right - fold, top),
                Offset(right - fold, top + fold),
                (w * .014f).coerceAtLeast(1f),
            )
        }
        Icon(
            symbol,
            null,
            Modifier.size(size * if (small) .45f else .34f)
                .offset(y = size * if (small) .12f else -.02f),
            tint = color,
        )
        if (!small)
            Text(
                type.label,
                Modifier.align(Alignment.BottomCenter)
                    .offset(y = -size * .07f)
                    .background(color, RoundedCornerShape(size * .04f))
                    .padding(horizontal = size * .08f, vertical = size * .025f),
                color = Color.White,
                fontSize = (size.value * .16f).coerceIn(8f, 17f).sp,
                lineHeight = (size.value * .18f).coerceIn(10f, 19f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
    }
}

internal fun loadBitmap(file: File, max: Int): Bitmap? = runCatching {
    decodeBitmap(file, max)
}
    .getOrNull()

/** The shared thumbnail owner handles allocation failures and evicts its own cache. */
internal fun decodeBitmap(file: File, max: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2
    val bitmap =
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
    val exif = runCatching { ExifInterface(file) }.getOrNull()
    return if (exif != null && (exif.rotationDegrees != 0 || exif.isFlipped)) {
        val matrix =
            Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else bitmap
}

private sealed interface Preview {
    data class Picture(val bitmap: Bitmap) : Preview

    data class Text(val text: String, val clipped: Boolean) : Preview

    data class Folder(val count: Int?) : Preview

    data object Unsupported : Preview

    data class Error(val message: String) : Preview
}

@Composable
fun PreviewContent(entry: Entry, modifier: Modifier = Modifier, large: Boolean = false) {
    if (
        large && !entry.folder && fileType(entry.extension, entry.mime).group == FileTypeGroup.IMAGE
    ) {
        ImageQuickLook(entry, modifier)
        return
    }
    if (large && entry.structuredKind != null) {
        StructuredQuickLook(entry, modifier)
        return
    }
    if (large && entry.mediaKind != null) {
        MediaQuickLook(entry, modifier)
        return
    }
    if (entry.documentUri != null) {
        val text =
            entry.kind == "Text document" ||
                entry.extension in listOf("yaml", "yml", "css", "js", "ts", "py", "sh", "toml")
        if (entry.folder || entry.kind == "Image" || entry.isPdfDocument || text)
            ProviderPreview(entry, modifier, large)
        else PreviewContent(entry.copy(documentUri = null), modifier, large)
        return
    }
    if (large && entry.isPdfDocument) {
        PdfQuickLook(entry, modifier)
        return
    }
    var loading by remember(entry.path, entry.modified) { mutableStateOf(true) }
    val preview by
        produceState<Preview>(Preview.Unsupported, entry.path, entry.modified) {
            value =
                withContext(Dispatchers.IO) {
                    try {
                        when {
                            entry.folder ->
                                Preview.Folder(
                                    entry.file.listFiles()?.count { !it.name.startsWith('.') },
                                )
                            entry.kind == "Image" && !entry.isPdfDocument ->
                                loadBitmap(entry.file, if (large) 1600 else 640)?.let {
                                    Preview.Picture(it)
                                } ?: Preview.Error("Couldn't decode this image")
                            entry.isPdfDocument ->
                                ParcelFileDescriptor.open(
                                        entry.file,
                                        ParcelFileDescriptor.MODE_READ_ONLY,
                                    )
                                    .use { descriptor ->
                                        PdfRenderer(descriptor).use { renderer ->
                                            if (renderer.pageCount == 0) Preview.Unsupported
                                            else
                                                renderer.openPage(0).use { page ->
                                                    val scale =
                                                        (if (large) 1400f else 600f) /
                                                            page.width.coerceAtLeast(1)
                                                    val bitmap =
                                                        Bitmap.createBitmap(
                                                            (page.width * scale)
                                                                .toInt()
                                                                .coerceAtLeast(1),
                                                            (page.height * scale)
                                                                .toInt()
                                                                .coerceIn(1, 2500),
                                                            Bitmap.Config.ARGB_8888,
                                                        )
                                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                                    page.render(
                                                        bitmap,
                                                        null,
                                                        null,
                                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                                    )
                                                    Preview.Picture(bitmap)
                                                }
                                        }
                                    }
                            entry.kind == "Text document" ||
                                entry.extension in
                                    listOf(
                                        "yaml",
                                        "yml",
                                        "css",
                                        "js",
                                        "ts",
                                        "py",
                                        "sh",
                                        "toml",
                                    ) -> {
                                val bytes =
                                    entry.file.inputStream().use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        var count = 0
                                        while (count < buffer.size) {
                                            val n = input.read(buffer, count, buffer.size - count)
                                            if (n < 0) break
                                            count += n
                                        }
                                        buffer.copyOf(count)
                                    }
                                if (bytes.any { it == 0.toByte() }) Preview.Unsupported
                                else
                                    Preview.Text(
                                        bytes.toString(Charsets.UTF_8),
                                        entry.size > bytes.size,
                                    )
                            }
                            else -> Preview.Unsupported
                        }
                    } catch (error: Exception) {
                        Preview.Error(error.message ?: "Preview unavailable")
                    }
                }
            loading = false
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else
            when (val content = preview) {
                is Preview.Picture ->
                    if (large)
                        ZoomableBitmap(
                            content.bitmap,
                            "Preview of ${entry.name}",
                            Modifier.fillMaxSize().padding(12.dp),
                        )
                    else
                        Image(
                            content.bitmap.asImageBitmap(),
                            "Preview of ${entry.name}",
                            Modifier.fillMaxSize().padding(12.dp),
                            contentScale = ContentScale.Fit,
                        )
                is Preview.Text ->
                    Column(
                        Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CanvasBackground)
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            content.text,
                            color = Ink,
                            fontSize = if (large) 14.sp else 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (content.clipped)
                            Text(
                                "Preview limited to the first 64 KB",
                                color = Muted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                    }
                is Preview.Folder ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FolderGlyph(
                            if (large) 140.dp else 110.dp,
                            entry.style?.let { tagColor(it.color) },
                            entry.style?.symbol.orEmpty(),
                        )
                        Text(
                            content.count?.let { "$it items" } ?: "Folder unavailable",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                is Preview.Error ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(Icons.Outlined.Info, null, tint = Muted)
                        Text(
                            content.message,
                            color = Muted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                Preview.Unsupported ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FileGlyph(entry, if (large) 130.dp else 100.dp, false)
                        Text(
                            entry.kind,
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text("Open in another app to view", color = Muted, fontSize = 11.sp)
                    }
            }
    }
}

@Composable
fun Metadata(entry: Entry, model: FinderModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            entry.name,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = Ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.kind + if (!entry.folder) " · ${entry.displaySize}" else "",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 5.dp, bottom = 14.dp),
        )
        MetaLine("Modified", formatDate(entry.modified))
        MetaLine(
            "Location",
            if (model.isTrash) model.engine.originalPath(entry.file) ?: "Trash"
            else entry.parentPath?.let(model::title).orEmpty(),
        )
        MetaLine("Access", if (entry.writable) "Read & write" else "Read only")
        if (entry.folder && entry.documentUri == null) {
            val stats by
                produceState<FolderStats?>(null, entry.path, entry.modified) {
                    value =
                        withContext(Dispatchers.IO) {
                            runCatching { folderStats(entry.file) }.getOrNull()
                        }
                }
            stats?.let {
                MetaLine(
                    "Contents",
                    "${it.files} files · ${it.folders} folders${if(it.limited)" (limited)"else ""}",
                )
                MetaLine("Size", formatSize(it.bytes))
            }
        }
        val itemTags = model.tagAssignments[entry.path].orEmpty()
        itemTags.forEach { tag ->
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp)
                        .background(tagColor(model.tagColorName(tag)), RoundedCornerShape(8.dp)),
                )
                Text(
                    tag,
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderPreview(entry: Entry, modifier: Modifier, large: Boolean) {
    val context = LocalContext.current
    val gateway = remember(context) { DocumentAccess(context.applicationContext) }
    val identity = listOf(entry.path, entry.modified, entry.size, entry.name, entry.mimeType)
    val control = remember(identity) { ListingControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    val local by
        produceState<Entry?>(null, identity) {
            value = null
            value =
                withContext(Dispatchers.IO) {
                    try {
                        if (entry.folder) entry
                        else
                            entry.copy(
                                file = gateway.materialize(entry, control),
                                documentUri = null,
                            )
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        error = failure.message ?: "The provider couldn't prepare a preview."
                        null
                    }
                }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (entry.folder) {
            val count by
                produceState<Int?>(null, entry.path) {
                    value =
                        withContext(Dispatchers.IO) {
                            runCatching { gateway.list(entry.path).size }.getOrNull()
                        }
                }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FolderGlyph(
                    if (large) 140.dp else 100.dp,
                    entry.style?.let { tagColor(it.color) },
                    entry.style?.symbol.orEmpty(),
                )
                Text(
                    count?.let { "$it items" } ?: "Provider folder",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        } else if (local != null) {
            if (large && entry.isPdfDocument)
                PdfQuickLook(local!!, Modifier.fillMaxSize(), entry.path)
            else PreviewContent(local!!, Modifier.fillMaxSize(), large)
        } else if (error != null)
            Text(error!!, Modifier.padding(16.dp), color = Muted, fontSize = 12.sp)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(66.dp), fontSize = 10.sp, color = Muted)
        Text(value, Modifier.weight(1f), fontSize = 11.sp, color = Ink)
    }
}
