package android.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JSON-file backed [SharedPreferences]. Values keep their Android types by tagging them:
 * `{"k": {"t": "s", "v": "x"}}` where t ∈ s,i,l,f,b,set.
 */
class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val lock = Any()
    private val values: MutableMap<String, Any> = load()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    private fun load(): MutableMap<String, Any> {
        val map = LinkedHashMap<String, Any>()
        if (!file.exists()) return map
        runCatching {
            val obj = Json.parseToJsonElement(file.readText()) as JsonObject
            for ((key, entry) in obj) {
                val tagged = entry as? JsonObject ?: continue
                val type = (tagged["t"] as? JsonPrimitive)?.content ?: continue
                val v = tagged["v"] ?: continue
                val decoded: Any? = when (type) {
                    "s" -> (v as? JsonPrimitive)?.content
                    "i" -> (v as? JsonPrimitive)?.longOrNull?.toInt()
                    "l" -> (v as? JsonPrimitive)?.longOrNull
                    "f" -> (v as? JsonPrimitive)?.doubleOrNull?.toFloat()
                    "b" -> (v as? JsonPrimitive)?.booleanOrNull
                    "set" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
                    else -> null
                }
                if (decoded != null) map[key] = decoded
            }
        }
        return map
    }

    private fun persist() {
        val obj = JsonObject(
            values.mapValues { (_, value) ->
                val (t, v: JsonElement) = when (value) {
                    is String -> "s" to JsonPrimitive(value)
                    is Int -> "i" to JsonPrimitive(value)
                    is Long -> "l" to JsonPrimitive(value)
                    is Float -> "f" to JsonPrimitive(value)
                    is Boolean -> "b" to JsonPrimitive(value)
                    is Set<*> -> "set" to JsonArray(value.map { JsonPrimitive(it.toString()) })
                    else -> "s" to JsonNull
                }
                JsonObject(mapOf("t" to JsonPrimitive(t), "v" to v))
            },
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override fun getAll(): Map<String, *> = synchronized(lock) { HashMap(values) }
    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { values[key] as? String ?: defValue }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        synchronized(lock) { (values[key] as? Set<String>)?.toSet() ?: defValues }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { (values[key] as? Number)?.toInt() ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { (values[key] as? Number)?.toLong() ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { (values[key] as? Number)?.toFloat() ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = synchronized(lock) { key in values }
    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.addIfAbsent(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            val changed = ArrayList<String>()
            synchronized(lock) {
                if (clearAll) {
                    changed.addAll(values.keys)
                    values.clear()
                }
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                    changed.add(k)
                }
                runCatching { persist() }.onFailure { return false }
            }
            changed.distinct().forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@FileSharedPreferences, key) }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
