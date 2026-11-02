package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class FileTypeTest {
    @Test
    fun commonFormatsHaveDistinctTypeFamilies() {
        val expected =
            mapOf(
                "pdf" to FileTypeGroup.PDF,
                "zip" to FileTypeGroup.ARCHIVE,
                "jpg" to FileTypeGroup.IMAGE,
                "mp4" to FileTypeGroup.VIDEO,
                "mp3" to FileTypeGroup.AUDIO,
                "xlsx" to FileTypeGroup.TABLE,
                "pptx" to FileTypeGroup.PRESENTATION,
                "kt" to FileTypeGroup.CODE,
                "apk" to FileTypeGroup.ANDROID,
                "finderlink" to FileTypeGroup.SHORTCUT,
                "md" to FileTypeGroup.TEXT,
            )
        expected.forEach { (extension, group) ->
            assertEquals(group, fileType(extension, "application/octet-stream").group)
        }
    }

    @Test
    fun providersWithoutFilenameExtensionsUseMimeMetadata() {
        assertEquals(FileType(FileTypeGroup.PDF, "PDF"), fileType("", "application/pdf"))
        assertEquals(FileTypeGroup.IMAGE, fileType("", "image/avif").group)
        assertEquals(
            FileTypeGroup.TABLE,
            fileType("", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").group,
        )
        assertEquals(FileTypeGroup.CODE, fileType("", "application/json; charset=UTF-8").group)
    }

    @Test
    fun labelsNormalizeWithoutMisclassifyingUnknownFiles() {
        assertEquals("PDF", fileType("PDF", "").label)
        assertEquals(FileTypeGroup.UNKNOWN, fileType("custom", "").group)
        assertEquals("CUSTO", fileType("custom", "").label)
        assertEquals(
            FileType(FileTypeGroup.UNKNOWN, "FILE"),
            fileType("", "application/octet-stream"),
        )
    }

    @Test
    fun windowsDragModifiersChooseCopyMoveAndShortcut() {
        assertEquals(FinderDragMode.MOVE, finderDragMode(false, false, false))
        assertEquals(FinderDragMode.MOVE, finderDragMode(false, true, false))
        assertEquals(FinderDragMode.COPY, finderDragMode(true, false, false))
        assertEquals(FinderDragMode.SHORTCUT, finderDragMode(false, false, true))
        assertEquals(FinderDragMode.SHORTCUT, finderDragMode(true, true, false))
    }
}
