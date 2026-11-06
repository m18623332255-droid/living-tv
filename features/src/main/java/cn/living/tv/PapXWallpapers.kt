package cn.living.tv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.FileNotFoundException
import java.io.InputStream

/** Offline wallpapers prepared from the owner's local Pap X downloads. */
internal object PapXWallpapers {
    private const val PREFIX = "file:///android_asset/papx/"

    data class Entry(val name: String, val address: String)

    fun entries(context: Context): List<Entry> {
        val catalog = try {
            context.assets.open("papx/catalog.json").bufferedReader().use { JSONArray(it.readText()) }
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        return List(catalog.length()) { index ->
            val item = catalog.getJSONObject(index)
            Entry(item.getString("name"), PREFIX + item.getString("file"))
        }
    }

    fun rotation(context: Context, selected: String, imported: List<String>): List<String> {
        return if (selected.startsWith(PREFIX)) entries(context).map { it.address } else imported
    }

    fun openImage(context: Context, uri: Uri): InputStream? {
        val address = uri.toString()
        return if (address.startsWith(PREFIX)) {
            context.assets.open("papx/" + address.removePrefix(PREFIX))
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }
}
