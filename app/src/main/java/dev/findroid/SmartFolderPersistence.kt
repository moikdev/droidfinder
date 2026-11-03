package dev.findroid

import android.content.SharedPreferences
import org.json.JSONArray

internal const val SMART_FOLDER_READ_NOTICE =
    "Saved Smart Folders could not be fully read. Their original data has been kept. Use a compatible Findroid version or restore a reviewed setup backup before editing them."

/** Refuse edits when any saved rule is unsupported; never replace an unreadable catalog with an empty one. */
internal class PreferencesSmartFolderPersistence(private val prefs: SharedPreferences) {
    fun load(): List<SmartFolder> {
        val text = prefs.getString("smartFolders", "[]") ?: error(SMART_FOLDER_READ_NOTICE)
        SetupJsonTree.validate(text)
        val array = JSONArray(text)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            SmartFolder(item.getString("id"), item.getString("name"), item.getString("root"),
                NavigationJson.rules(item.getJSONObject("rules")))
        }
    }

    @android.annotation.SuppressLint("UseKtx") // Publish in-memory changes only after a durable write succeeds.
    fun save(values: List<SmartFolder>) {
        // Re-read before every write, including after a setup restore or another window's edit.
        check(runCatching { load() }.isSuccess) { SMART_FOLDER_READ_NOTICE }
        check(prefs.edit().putString("smartFolders", SetupJson.searches(values).toString()).commit()) {
            "Couldn't save Smart Folders. Try again."
        }
    }
}
