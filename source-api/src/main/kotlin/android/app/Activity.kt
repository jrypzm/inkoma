package android.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import java.io.File

/** Activity stand-in so classes extending it link; desktop never launches extension activities. */
open class Activity : ContextWrapper(null) {
    private var intentValue: Intent = Intent()

    open fun getIntent(): Intent = intentValue
    open fun setIntent(intent: Intent) { intentValue = intent }
    protected open fun onCreate(savedInstanceState: Bundle?) {}
    open fun finish() {}
    open fun finishAffinity() {}
    open fun isFinishing(): Boolean = false
    override fun getSharedPreferences(name: String, mode: Int) = (uy.kohesive.injekt.Injekt.getInstance<Context>(Context::class.java)).getSharedPreferences(name, mode)
    override fun getFilesDir(): File = uy.kohesive.injekt.Injekt.getInstance<Context>(Context::class.java).getFilesDir()
    override fun getCacheDir(): File = uy.kohesive.injekt.Injekt.getInstance<Context>(Context::class.java).getCacheDir()
    override fun getPackageName(): String = "app.mihon"
}
