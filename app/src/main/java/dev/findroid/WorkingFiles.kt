package dev.findroid

/** Older interrupted operations may have left staging files in shared/provider folders. */
internal fun isWorkingFileName(name: String): Boolean =
    name.startsWith(".findroid-") || name.startsWith(".droidfinder-")
