package dev.droidfinder

enum class FileTypeGroup {
    PDF,
    ARCHIVE,
    IMAGE,
    VIDEO,
    AUDIO,
    TABLE,
    PRESENTATION,
    CODE,
    ANDROID,
    SHORTCUT,
    TEXT,
    UNKNOWN,
}

data class FileType(val group: FileTypeGroup, val label: String)

/** Provider MIME metadata covers documents whose displayed names have no extension. */
fun fileType(extension: String, mime: String): FileType {
    val ext = extension.lowercase()
    val type = mime.lowercase().substringBefore(';').trim()
    val group =
        when {
            ext == "pdf" || type == "application/pdf" -> FileTypeGroup.PDF
            ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz") ||
                type in
                    setOf(
                        "application/zip",
                        "application/x-7z-compressed",
                        "application/vnd.rar",
                        "application/x-tar",
                        "application/gzip",
                    ) -> FileTypeGroup.ARCHIVE
            ext in
                setOf(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp",
                    "gif",
                    "heic",
                    "heif",
                    "avif",
                    "svg",
                    "bmp",
                    "tiff",
                    "tif",
                ) || type.startsWith("image/") -> FileTypeGroup.IMAGE
            ext in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v") || type.startsWith("video/") ->
                FileTypeGroup.VIDEO
            ext in setOf("mp3", "wav", "m4a", "flac", "ogg", "aac", "opus") ||
                type.startsWith("audio/") -> FileTypeGroup.AUDIO
            ext in setOf("csv", "xls", "xlsx", "ods") ||
                "spreadsheet" in type ||
                type == "text/csv" ||
                type == "application/vnd.ms-excel" -> FileTypeGroup.TABLE
            ext in setOf("ppt", "pptx", "odp") ||
                "presentation" in type ||
                type == "application/vnd.ms-powerpoint" -> FileTypeGroup.PRESENTATION
            ext in
                setOf(
                    "kt",
                    "java",
                    "py",
                    "js",
                    "ts",
                    "tsx",
                    "jsx",
                    "html",
                    "css",
                    "xml",
                    "json",
                    "yaml",
                    "yml",
                    "toml",
                    "sh",
                    "c",
                    "cpp",
                    "rs",
                    "go",
                ) ||
                type in
                    setOf(
                        "application/json",
                        "application/xml",
                        "application/javascript",
                        "text/html",
                    ) -> FileTypeGroup.CODE
            ext == "apk" || type == "application/vnd.android.package-archive" ->
                FileTypeGroup.ANDROID
            ext == "finderlink" -> FileTypeGroup.SHORTCUT
            ext in setOf("doc", "docx", "odt", "rtf", "txt", "md", "log") ||
                type.startsWith("text/") ||
                "wordprocessing" in type ||
                type == "application/msword" -> FileTypeGroup.TEXT
            else -> FileTypeGroup.UNKNOWN
        }
    val label =
        if (group == FileTypeGroup.SHORTCUT) "LINK"
        else
            ext.uppercase().take(5).ifBlank {
                when (group) {
                    FileTypeGroup.PDF -> "PDF"
                    FileTypeGroup.ARCHIVE -> "ZIP"
                    FileTypeGroup.IMAGE -> "IMG"
                    FileTypeGroup.VIDEO -> "VIDEO"
                    FileTypeGroup.AUDIO -> "AUDIO"
                    FileTypeGroup.TABLE -> "SHEET"
                    FileTypeGroup.PRESENTATION -> "SLIDE"
                    FileTypeGroup.CODE -> "CODE"
                    FileTypeGroup.ANDROID -> "APK"
                    FileTypeGroup.SHORTCUT -> "LINK"
                    FileTypeGroup.TEXT -> "TXT"
                    else -> "FILE"
                }
            }
    return FileType(group, label)
}
