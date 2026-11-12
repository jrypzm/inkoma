package android.view

/** Headless view model: enough for extension code that tweaks preference dialogs (e.g. disabling the OK button). */
interface ViewParent {
    fun getParent(): ViewParent?
}

open class View(private val contextValue: android.content.Context?) {
    private var enabledValue = true
    private var visibilityValue = VISIBLE
    private var parentView: View? = null
    private val children = HashMap<Int, View>()
    private var idValue = NO_ID

    open fun isEnabled(): Boolean = enabledValue
    open fun setEnabled(enabled: Boolean) { enabledValue = enabled }
    fun getVisibility(): Int = visibilityValue
    fun setVisibility(visibility: Int) { visibilityValue = visibility }
    fun getId(): Int = idValue
    fun setId(id: Int) { idValue = id }
    fun getRootView(): View {
        var v: View = this
        while (true) v = v.parentView ?: return v
    }
    internal fun attachTo(parent: View) { parentView = parent }

    /** Returns the child with [id], creating a button so dialog-button lookups (android.R.id.button1) work. */
    open fun <T : View> findViewById(id: Int): T? {
        @Suppress("UNCHECKED_CAST")
        return children.getOrPut(id) { android.widget.Button(null).also { it.setId(id); it.attachTo(this) } } as T
    }

    fun post(action: Runnable): Boolean { action.run(); return true }
    fun getContext(): android.content.Context? = contextValue
    fun getResources(): android.content.res.Resources = android.content.res.Resources.getSystem()
    open fun getParent(): ViewParent? = parentView as? ViewParent
    fun measure(widthMeasureSpec: Int, heightMeasureSpec: Int) {}
    fun layout(l: Int, t: Int, r: Int, b: Int) {}
    fun getWidth(): Int = 0
    fun getHeight(): Int = 0
    fun requestFocus(): Boolean = true

    class MeasureSpec {
        companion object {
            const val UNSPECIFIED = 0
            const val EXACTLY = 1 shl 30
            const val AT_MOST = 2 shl 30
            @JvmStatic fun makeMeasureSpec(size: Int, mode: Int): Int = (size and 0x3fffffff) or mode
            @JvmStatic fun getMode(measureSpec: Int): Int = measureSpec and (3 shl 30)
            @JvmStatic fun getSize(measureSpec: Int): Int = measureSpec and 0x3fffffff
        }
    }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val NO_ID = -1
    }
}

open class ViewGroup(context: android.content.Context?) : View(context), ViewParent {
    override fun getParent(): ViewParent? = super.getParent()
    open fun addView(child: View) { child.attachTo(this) }
    open fun addView(child: View, params: LayoutParams) { child.attachTo(this) }
    open fun removeView(child: View) {}
    open fun removeAllViews() {}

    open class LayoutParams(@JvmField var width: Int, @JvmField var height: Int) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
}
