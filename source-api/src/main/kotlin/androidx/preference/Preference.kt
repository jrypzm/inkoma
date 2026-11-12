package androidx.preference

import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText

/**
 * Headless model of the androidx.preference API that ConfigurableSource extensions build their
 * settings with. The desktop UI renders these models with Compose (see SourcePreferencesScreen).
 */
open class Preference(private val context: Context) {
    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }

    fun interface OnPreferenceClickListener {
        fun onPreferenceClick(preference: Preference): Boolean
    }

    private var keyValue: String? = null
    private var titleValue: CharSequence? = null
    private var summaryValue: CharSequence? = null
    private var visibleValue = true
    private var enabledValue = true
    private var changeListener: OnPreferenceChangeListener? = null
    private var clickListener: OnPreferenceClickListener? = null
    internal var defaultValueInternal: Any? = null
    private var prefs: SharedPreferences? = null

    /** Bumped on every mutation so the UI can recompose. */
    @Volatile
    var revision: Int = 0
        private set

    protected fun notifyChanged() {
        revision++
        parentScreen?.notifyChildChanged()
    }

    internal var parentScreen: PreferenceScreen? = null

    fun getContext(): Context = context
    fun getKey(): String? = keyValue
    fun setKey(key: String?) {
        keyValue = key
    }

    fun hasKey(): Boolean = !keyValue.isNullOrEmpty()

    open fun getTitle(): CharSequence? = titleValue
    open fun setTitle(title: CharSequence?) {
        titleValue = title
        notifyChanged()
    }

    open fun getSummary(): CharSequence? = summaryValue
    open fun setSummary(summary: CharSequence?) {
        summaryValue = summary
        notifyChanged()
    }

    fun isVisible(): Boolean = visibleValue
    fun setVisible(visible: Boolean) {
        visibleValue = visible
        notifyChanged()
    }

    fun isEnabled(): Boolean = enabledValue
    fun setEnabled(enabled: Boolean) {
        enabledValue = enabled
        notifyChanged()
    }

    fun setDefaultValue(defaultValue: Any?) {
        defaultValueInternal = defaultValue
    }

    fun getOnPreferenceChangeListener(): OnPreferenceChangeListener? = changeListener
    fun setOnPreferenceChangeListener(listener: OnPreferenceChangeListener?) {
        changeListener = listener
    }

    fun getOnPreferenceClickListener(): OnPreferenceClickListener? = clickListener
    fun setOnPreferenceClickListener(listener: OnPreferenceClickListener?) {
        clickListener = listener
    }

    fun getSharedPreferences(): SharedPreferences? = prefs

    internal open fun attach(sharedPreferences: SharedPreferences) {
        prefs = sharedPreferences
    }

    fun callChangeListener(newValue: Any?): Boolean = changeListener?.onPreferenceChange(this, newValue) ?: true

    /** Invoked by the UI when the preference row is clicked. */
    open fun performClick() {
        clickListener?.onPreferenceClick(this)
    }

    protected fun persistString(value: String?): Boolean {
        val p = prefs ?: return false
        val k = keyValue ?: return false
        p.edit().putString(k, value).apply()
        return true
    }

    protected fun persistBoolean(value: Boolean): Boolean {
        val p = prefs ?: return false
        val k = keyValue ?: return false
        p.edit().putBoolean(k, value).apply()
        return true
    }

    protected fun persistStringSet(values: Set<String>): Boolean {
        val p = prefs ?: return false
        val k = keyValue ?: return false
        p.edit().putStringSet(k, values).apply()
        return true
    }

    protected fun getPersistedString(defaultReturnValue: String?): String? {
        val k = keyValue ?: return defaultReturnValue
        return prefs?.getString(k, defaultReturnValue) ?: defaultReturnValue
    }

    protected fun getPersistedBoolean(defaultReturnValue: Boolean): Boolean {
        val k = keyValue ?: return defaultReturnValue
        return prefs?.getBoolean(k, defaultReturnValue) ?: defaultReturnValue
    }

    protected fun getPersistedStringSet(defaultReturnValue: Set<String>?): Set<String>? {
        val k = keyValue ?: return defaultReturnValue
        return prefs?.getStringSet(k, defaultReturnValue) ?: defaultReturnValue
    }
}

abstract class DialogPreference(context: Context) : Preference(context) {
    private var dialogTitleValue: CharSequence? = null
    private var dialogMessageValue: CharSequence? = null
    private var positiveText: CharSequence? = null
    private var negativeText: CharSequence? = null

    fun getDialogTitle(): CharSequence? = dialogTitleValue ?: getTitle()
    fun setDialogTitle(title: CharSequence?) {
        dialogTitleValue = title
    }

    fun getDialogMessage(): CharSequence? = dialogMessageValue
    fun setDialogMessage(message: CharSequence?) {
        dialogMessageValue = message
    }

    fun getPositiveButtonText(): CharSequence? = positiveText
    fun setPositiveButtonText(text: CharSequence?) {
        positiveText = text
    }

    fun getNegativeButtonText(): CharSequence? = negativeText
    fun setNegativeButtonText(text: CharSequence?) {
        negativeText = text
    }
}

open class EditTextPreference(context: Context) : DialogPreference(context) {
    fun interface OnBindEditTextListener {
        fun onBindEditText(editText: EditText)
    }

    private var textValue: String? = null
    private var bindListener: OnBindEditTextListener? = null

    fun getText(): String? = textValue ?: getPersistedString(defaultValueInternal as? String)
    fun setText(text: String?) {
        textValue = text
        persistString(text)
        notifyChanged()
    }

    fun getOnBindEditTextListener(): OnBindEditTextListener? = bindListener
    fun setOnBindEditTextListener(listener: OnBindEditTextListener?) {
        bindListener = listener
    }

    override fun attach(sharedPreferences: SharedPreferences) {
        super.attach(sharedPreferences)
        textValue = null
    }
}

open class ListPreference(context: Context) : DialogPreference(context) {
    private var entriesValue: Array<CharSequence> = emptyArray()
    private var entryValuesValue: Array<CharSequence> = emptyArray()
    private var valueValue: String? = null

    fun getEntries(): Array<CharSequence> = entriesValue
    fun setEntries(entries: Array<CharSequence>) {
        entriesValue = entries
        notifyChanged()
    }

    fun getEntryValues(): Array<CharSequence> = entryValuesValue
    fun setEntryValues(entryValues: Array<CharSequence>) {
        entryValuesValue = entryValues
        notifyChanged()
    }

    fun getValue(): String? = valueValue ?: getPersistedString(defaultValueInternal as? String)
    fun setValue(value: String?) {
        valueValue = value
        persistString(value)
        notifyChanged()
    }

    fun findIndexOfValue(value: String?): Int = entryValuesValue.indexOfFirst { it.toString() == value }

    fun setValueIndex(index: Int) {
        setValue(entryValuesValue[index].toString())
    }

    fun getEntry(): CharSequence? = findIndexOfValue(getValue()).takeIf { it >= 0 }?.let { entriesValue.getOrNull(it) }

    override fun getSummary(): CharSequence? {
        val summary = super.getSummary() ?: return null
        val entry = getEntry() ?: ""
        return summary.toString().replace("%s", entry.toString())
    }

    override fun attach(sharedPreferences: SharedPreferences) {
        super.attach(sharedPreferences)
        valueValue = null
    }
}

open class MultiSelectListPreference(context: Context) : DialogPreference(context) {
    private var entriesValue: Array<CharSequence> = emptyArray()
    private var entryValuesValue: Array<CharSequence> = emptyArray()
    private var valuesValue: Set<String>? = null

    fun getEntries(): Array<CharSequence> = entriesValue
    fun setEntries(entries: Array<CharSequence>) {
        entriesValue = entries
        notifyChanged()
    }

    fun getEntryValues(): Array<CharSequence> = entryValuesValue
    fun setEntryValues(entryValues: Array<CharSequence>) {
        entryValuesValue = entryValues
        notifyChanged()
    }

    @Suppress("UNCHECKED_CAST")
    fun getValues(): Set<String> =
        valuesValue ?: getPersistedStringSet(defaultValueInternal as? Set<String>) ?: emptySet()

    fun setValues(values: Set<String>) {
        valuesValue = values.toSet()
        persistStringSet(values)
        notifyChanged()
    }

    fun findIndexOfValue(value: String?): Int = entryValuesValue.indexOfFirst { it.toString() == value }

    override fun attach(sharedPreferences: SharedPreferences) {
        super.attach(sharedPreferences)
        valuesValue = null
    }
}

abstract class TwoStatePreference(context: Context) : Preference(context) {
    private var checkedValue: Boolean? = null
    private var summaryOnValue: CharSequence? = null
    private var summaryOffValue: CharSequence? = null

    fun isChecked(): Boolean = checkedValue ?: getPersistedBoolean(defaultValueInternal as? Boolean ?: false)
    fun setChecked(checked: Boolean) {
        checkedValue = checked
        persistBoolean(checked)
        notifyChanged()
    }

    fun getSummaryOn(): CharSequence? = summaryOnValue
    fun setSummaryOn(summary: CharSequence?) {
        summaryOnValue = summary
    }

    fun getSummaryOff(): CharSequence? = summaryOffValue
    fun setSummaryOff(summary: CharSequence?) {
        summaryOffValue = summary
    }

    override fun getSummary(): CharSequence? {
        val on = summaryOnValue
        val off = summaryOffValue
        return when {
            isChecked() && on != null -> on
            !isChecked() && off != null -> off
            else -> super.getSummary()
        }
    }

    override fun attach(sharedPreferences: SharedPreferences) {
        super.attach(sharedPreferences)
        checkedValue = null
    }
}

open class SwitchPreferenceCompat(context: Context) : TwoStatePreference(context)

open class SwitchPreference(context: Context) : TwoStatePreference(context)

open class CheckBoxPreference(context: Context) : TwoStatePreference(context)

open class PreferenceCategory(context: Context) : PreferenceGroup(context)

abstract class PreferenceGroup(context: Context) : Preference(context) {
    private val children = ArrayList<Preference>()

    fun addPreference(preference: Preference): Boolean {
        if (children.contains(preference)) return true
        children.add(preference)
        preference.parentScreen = this as? PreferenceScreen ?: parentScreen
        getSharedPreferences()?.let { preference.attach(it) }
        notifyChanged()
        return true
    }

    fun removePreference(preference: Preference): Boolean = children.remove(preference).also { notifyChanged() }
    fun removeAll() {
        children.clear()
        notifyChanged()
    }

    fun getPreferenceCount(): Int = children.size
    fun getPreference(index: Int): Preference = children[index]

    fun findPreference(key: CharSequence): Preference? {
        for (child in children) {
            if (child.getKey() == key.toString()) return child
            if (child is PreferenceGroup) child.findPreference(key)?.let { return it }
        }
        return null
    }

    /** Flattened list for rendering (categories followed by their children). */
    fun flatten(): List<Preference> = children.flatMap { if (it is PreferenceCategory) listOf(it) + it.flatten() else listOf(it) }

    override fun attach(sharedPreferences: SharedPreferences) {
        super.attach(sharedPreferences)
        children.forEach { it.attach(sharedPreferences) }
    }
}

class PreferenceScreen(context: Context, sharedPreferences: SharedPreferences) : PreferenceGroup(context) {
    @Volatile
    var childRevision: Int = 0
        private set

    init {
        attach(sharedPreferences)
    }

    internal fun notifyChildChanged() {
        childRevision++
    }
}
