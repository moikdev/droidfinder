package dev.findroid

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView

/** Foreground clipboard observation for optimized PDF acceptance; absent from app APKs. */
class PdfClipboardProbeActivity : Activity() {
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        result = TextView(this).apply {
            contentDescription = "PDF copied text"
            text = "Waiting for clipboard focus"
            textSize = 20f
            setPadding(24, 24, 24, 24)
        }
        setContentView(result)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        result.text = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(this) else "No clipboard text"
    }
}
