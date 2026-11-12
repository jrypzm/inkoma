package android.os

class Bundle {
    private val map = HashMap<String, Any?>()
    fun getString(key: String?): String? = map[key] as? String
    fun putString(key: String?, value: String?) { map[key ?: return] = value }
    fun containsKey(key: String?): Boolean = map.containsKey(key)
}
