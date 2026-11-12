package android.content

import java.io.File

abstract class Context {
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    abstract fun getFilesDir(): File
    abstract fun getCacheDir(): File
    abstract fun getPackageName(): String
    open fun getApplicationContext(): Context = this
    open fun getString(resId: Int): String = ""
    open fun getResources(): android.content.res.Resources = android.content.res.Resources.getSystem()

    /** No activities can run on desktop; URLs are opened in the system browser instead. */
    open fun startActivity(intent: Intent) {
        val uri = intent.getData()?.toString() ?: throw ActivityNotFoundException("No activity for $intent")
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(uri)) }.getOrElse { throw ActivityNotFoundException(it.message) }
    }

    companion object {
        const val MODE_PRIVATE = 0x0000
        const val MODE_APPEND = 0x8000
    }
}

open class ContextWrapper(private val base: Context?) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base!!.getSharedPreferences(name, mode)
    override fun getFilesDir(): File = base!!.getFilesDir()
    override fun getCacheDir(): File = base!!.getCacheDir()
    override fun getPackageName(): String = base!!.getPackageName()
}
