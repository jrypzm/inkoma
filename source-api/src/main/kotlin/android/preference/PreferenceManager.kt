package android.preference

import android.content.Context
import android.content.SharedPreferences

/** Deprecated Android API still used by some extensions to read their default preferences. */
object PreferenceManager {
    @JvmStatic
    fun getDefaultSharedPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE)

    @JvmStatic
    fun getDefaultSharedPreferencesName(context: Context): String = context.getPackageName() + "_preferences"
}
