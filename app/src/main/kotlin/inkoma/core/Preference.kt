package inkoma.core

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Typed preference over [SharedPreferences], equivalent to Mihon's tachiyomi.core.preference.Preference. */
class Preference<T>(
    private val prefs: SharedPreferences,
    val key: String,
    val defaultValue: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    fun get(): T = prefs.read(key, defaultValue)

    fun set(value: T) {
        prefs.edit().apply { write(key, value) }.apply()
    }

    fun isSet(): Boolean = prefs.contains(key)

    fun delete() {
        prefs.edit().remove(key).apply()
    }

    fun changes(): Flow<T> = callbackFlow {
        val listener = object : SharedPreferences.OnSharedPreferenceChangeListener {
            override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
                if (key == this@Preference.key || key == null) trySend(Unit)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(Unit) }.map { get() }.conflate().distinctUntilChanged()
}

class PreferenceStore(val prefs: SharedPreferences) {
    fun getString(key: String, default: String = "") =
        Preference(prefs, key, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    fun getInt(key: String, default: Int = 0) =
        Preference(prefs, key, default, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    fun getLong(key: String, default: Long = 0) =
        Preference(prefs, key, default, { k, d -> getLong(k, d) }, { k, v -> putLong(k, v) })

    fun getFloat(key: String, default: Float = 0f) =
        Preference(prefs, key, default, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) })

    fun getBoolean(key: String, default: Boolean = false) =
        Preference(prefs, key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    fun getStringSet(key: String, default: Set<String> = emptySet()) =
        Preference(prefs, key, default, { k, d -> getStringSet(k, d) ?: d }, { k, v -> putStringSet(k, v) })

    inline fun <reified E : Enum<E>> getEnum(key: String, default: E): Preference<E> = Preference(
        prefs,
        key,
        default,
        { k, d -> getString(k, null)?.let { s -> runCatching { enumValueOf<E>(s) }.getOrNull() } ?: d },
        { k, v -> putString(k, v.name) },
    )
}
