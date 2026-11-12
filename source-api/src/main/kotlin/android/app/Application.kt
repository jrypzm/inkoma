package android.app

import android.content.Context
import android.content.ContextWrapper
import android.content.FileSharedPreferences
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop stand-in for the Android Application object handed to extensions through Injekt.
 * `getSharedPreferences(name)` maps to `<dataDir>/shared_prefs/<name>.json`.
 */
open class Application(private val dataDir: File) : ContextWrapper(null) {
    private val prefs = ConcurrentHashMap<String, SharedPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FileSharedPreferences(File(dataDir, "shared_prefs/${name.replace('/', '_')}.json")) }

    override fun getFilesDir(): File = File(dataDir, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(dataDir, "cache").apply { mkdirs() }
    override fun getPackageName(): String = "app.mihon"
    override fun getApplicationContext(): Context = this
}
