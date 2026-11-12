package android.content

import android.net.Uri

/** Minimal Intent: extensions use it in URL-handler activities, which only Android itself ever starts. */
open class Intent {
    private var action: String? = null
    private var data: Uri? = null
    private val extras = HashMap<String, Any?>()
    private var flags = 0

    constructor()
    constructor(action: String?) { this.action = action }
    constructor(action: String?, uri: Uri?) { this.action = action; this.data = uri }

    fun getAction(): String? = action
    fun setAction(action: String?): Intent = apply { this.action = action }
    fun getData(): Uri? = data
    fun setData(data: Uri?): Intent = apply { this.data = data }
    fun getDataString(): String? = data?.toString()
    fun putExtra(name: String, value: String?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Int): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Long): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Boolean): Intent = apply { extras[name] = value }
    fun getStringExtra(name: String): String? = extras[name] as? String
    fun getIntExtra(name: String, def: Int): Int = extras[name] as? Int ?: def
    fun getBooleanExtra(name: String, def: Boolean): Boolean = extras[name] as? Boolean ?: def
    fun hasExtra(name: String): Boolean = extras.containsKey(name)
    fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }
    fun setFlags(flags: Int): Intent = apply { this.flags = flags }
    fun getFlags(): Int = flags

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TASK = 0x00008000
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
    }
}

class ActivityNotFoundException : RuntimeException {
    constructor() : super()
    constructor(message: String?) : super(message)
}
