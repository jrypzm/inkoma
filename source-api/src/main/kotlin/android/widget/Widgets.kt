package android.widget

import android.content.Context
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import java.util.concurrent.CopyOnWriteArrayList

/** Receives toasts raised by extensions; the desktop UI installs a handler that shows a snackbar. */
object ToastBridge {
    @Volatile
    var handler: (String) -> Unit = { println(it) }
}

class Toast private constructor(private val text: CharSequence) {
    fun show() {
        ToastBridge.handler(text.toString())
    }

    fun cancel() {}

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast = Toast(text ?: "")

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast = Toast(context?.getString(resId) ?: "")
    }
}

open class TextView(context: Context?) : android.view.View(context) {
    private var textValue: Editable = SpannableStringBuilder()
    private var inputTypeValue = 1
    private var hintValue: CharSequence? = null
    private var errorValue: CharSequence? = null
    val watchers = CopyOnWriteArrayList<TextWatcher>()

    open fun getText(): Editable = textValue

    open fun setText(text: CharSequence?) {
        val old = textValue.toString()
        val new = text?.toString() ?: ""
        watchers.forEach { it.beforeTextChanged(old, 0, old.length, new.length) }
        textValue = SpannableStringBuilder(new)
        watchers.forEach { it.onTextChanged(new, 0, old.length, new.length) }
        watchers.forEach { it.afterTextChanged(textValue) }
    }

    fun getInputType(): Int = inputTypeValue
    fun setInputType(type: Int) {
        inputTypeValue = type
    }

    fun getHint(): CharSequence? = hintValue
    fun setHint(hint: CharSequence?) {
        hintValue = hint
    }

    fun getError(): CharSequence? = errorValue
    fun setError(error: CharSequence?) {
        errorValue = error
    }

    fun setSingleLine() {}
    fun setSingleLine(singleLine: Boolean) {}
    fun setMaxLines(maxLines: Int) {}
    fun setSelection(index: Int) {}
    fun selectAll() {}
    fun setHorizontallyScrolling(whether: Boolean) {}
    fun setTextIsSelectable(selectable: Boolean) {}

    fun addTextChangedListener(watcher: TextWatcher) {
        watchers.add(watcher)
    }

    fun removeTextChangedListener(watcher: TextWatcher) {
        watchers.remove(watcher)
    }
}

open class EditText(context: Context?) : TextView(context)

open class Button(context: Context?) : TextView(context)
