package dev.droidfinder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Blue = Color(0xFF2878E8)
val Ink = Color(0xFF273341)
val Muted = Color(0xFF7B8491)
val Border = Color(0xFFE1E5EA)
val Sidebar = Color(0xFFF0F3F7)
fun tagColor(name: String?): Color = when (name) { "Red" -> Color(0xFFEA6465); "Orange" -> Color(0xFFED9B51); "Yellow" -> Color(0xFFE6C443); "Green" -> Color(0xFF65B68A); "Blue" -> Blue; "Purple" -> Color(0xFFA580CE); else -> Muted }

@Composable fun FolderGlyph(size: Dp = 64.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val back = Path().apply { moveTo(w * .08f, h * .26f); quadraticTo(w * .08f, h * .18f, w * .16f, h * .18f); lineTo(w * .39f, h * .18f); lineTo(w * .48f, h * .28f); lineTo(w * .85f, h * .28f); quadraticTo(w * .93f, h * .28f, w * .93f, h * .36f); lineTo(w * .93f, h * .8f); lineTo(w * .08f, h * .8f); close() }
        drawPath(back, Color(0xFF389BD7))
        drawRoundRect(Color(0xFFBCEDFF), Offset(w * .14f, h * .33f), Size(w * .72f, h * .35f), CornerRadius(w * .025f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF7BD4F4), Color(0xFF4CB1E6))), Offset(w * .06f, h * .4f), Size(w * .9f, h * .46f), CornerRadius(w * .045f))
        drawLine(Color(0xFFA4E5FF), Offset(w * .1f, h * .41f), Offset(w * .91f, h * .41f), w * .012f)
    }
}

@Composable fun FileGlyph(entry: Entry, size: Dp = 44.dp, thumbnail: Boolean = true) {
    if (entry.folder) { FolderGlyph(size); return }
    val bitmap by produceState<Bitmap?>(null, entry.path, entry.modified, thumbnail) {
        if (thumbnail && entry.kind == "Image") value = withContext(Dispatchers.IO) { loadBitmap(entry.file, 256) }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), entry.name, Modifier.size(size).padding(3.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
    } else {
        val color = when (entry.kind) { "PDF document" -> Color(0xFFDA6866); "Image" -> Color(0xFF8B81C7); "Video" -> Color(0xFF9C7ABF); "Audio" -> Color(0xFFE18CA3); "ZIP archive" -> Color(0xFFB59C68); else -> Color(0xFF8AABC8) }
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = size * .16f, vertical = size * .09f)) {
                val w = this.size.width; val h = this.size.height
                val page = Path().apply { moveTo(0f, h * .04f); lineTo(w * .7f, h * .04f); lineTo(w, h * .3f); lineTo(w, h * .96f); lineTo(0f, h * .96f); close() }
                drawPath(page, Color(0xFFE8EDF2))
                drawRect(Color.White, Offset(w * .03f, h * .07f), Size(w * .91f, h * .86f))
                drawPath(Path().apply { moveTo(w * .7f, h * .04f); lineTo(w * .7f, h * .3f); lineTo(w, h * .3f); close() }, Color(0xFFD4DDE6))
                if (size < 40.dp) { for (i in 0..2) drawLine(color.copy(alpha = .7f), Offset(w * .2f, h * (.48f + .13f * i)), Offset(w * .78f, h * (.48f + .13f * i)), w * .06f) }
            }
            if (size >= 40.dp) Text(entry.extension.uppercase().take(5).ifEmpty { "FILE" }, Modifier.offset(y = size * .16f).background(color, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 2.dp), color = Color.White, fontSize = if (size > 80.dp) 14.sp else 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun loadBitmap(file: File, max: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private sealed interface Preview { data class Picture(val bitmap: Bitmap) : Preview; data class Text(val text: String, val clipped: Boolean) : Preview; data class Folder(val count: Int?) : Preview; data object Unsupported : Preview; data class Error(val message: String) : Preview }

@Composable fun PreviewContent(entry: Entry, modifier: Modifier = Modifier, large: Boolean = false) {
    var loading by remember(entry.path, entry.modified) { mutableStateOf(true) }
    val preview by produceState<Preview>(Preview.Unsupported, entry.path, entry.modified) {
        value = withContext(Dispatchers.IO) {
            try {
                when {
                    entry.folder -> Preview.Folder(entry.file.listFiles()?.count { !it.name.startsWith('.') })
                    entry.kind == "Image" -> loadBitmap(entry.file, if (large) 1600 else 640)?.let { Preview.Picture(it) } ?: Preview.Error("Couldn't decode this image")
                    entry.extension == "pdf" -> ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            if (renderer.pageCount == 0) Preview.Unsupported else renderer.openPage(0).use { page ->
                                val scale = (if (large) 1400f else 600f) / page.width.coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceIn(1, 2500), Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); Preview.Picture(bitmap)
                            }
                        }
                    }
                    entry.kind == "Text document" || entry.extension in listOf("yaml", "yml", "css", "js", "ts", "py", "sh", "toml") -> {
                        val bytes = entry.file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024); var count = 0
                            while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                            buffer.copyOf(count)
                        }
                        if (bytes.any { it == 0.toByte() }) Preview.Unsupported else Preview.Text(bytes.toString(Charsets.UTF_8), entry.size > bytes.size)
                    }
                    else -> Preview.Unsupported
                }
            } catch (error: Exception) { Preview.Error(error.message ?: "Preview unavailable") }
        }
        loading = false
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else when (val content = preview) {
            is Preview.Picture -> Image(content.bitmap.asImageBitmap(), "Preview of ${entry.name}", Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            is Preview.Text -> Column(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.White).padding(14.dp).verticalScroll(rememberScrollState())) {
                Text(content.text, color = Ink, fontSize = if (large) 14.sp else 11.sp, fontFamily = FontFamily.Monospace)
                if (content.clipped) Text("Preview limited to the first 64 KB", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
            }
            is Preview.Folder -> Column(horizontalAlignment = Alignment.CenterHorizontally) { FolderGlyph(if (large) 140.dp else 110.dp); Text(content.count?.let { "$it items" } ?: "Folder unavailable", color = Muted, fontSize = 12.sp) }
            is Preview.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) { Icon(Icons.Outlined.Info, null, tint = Muted); Text(content.message, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center) }
            Preview.Unsupported -> Column(horizontalAlignment = Alignment.CenterHorizontally) { FileGlyph(entry, if (large) 130.dp else 100.dp, false); Text(entry.kind, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)); Text("Open in another app to view", color = Muted, fontSize = 11.sp) }
        }
    }
}

@Composable fun Metadata(entry: Entry, model: FinderModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(entry.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(entry.kind + if (!entry.folder) " · ${formatSize(entry.size)}" else "", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 22.dp))
        MetaLine("Modified", formatDate(entry.modified))
        MetaLine("Location", if (model.isTrash) model.engine.originalPath(entry.file) ?: "Trash" else entry.file.parent.orEmpty())
        MetaLine("Access", if (entry.file.canWrite()) "Read & write" else "Read only")
        val tag = model.tags[entry.path]
        if (tag != null) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(tagColor(tag), RoundedCornerShape(8.dp))); Text(tag, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)) }
    }
}
@Composable private fun MetaLine(label: String, value: String) { Column(Modifier.padding(bottom = 12.dp)) { Text(label, fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Medium); Text(value, fontSize = 11.sp, color = Ink, modifier = Modifier.padding(top = 3.dp)) } }
