package eu.kanade.tachiyomi.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory

/**
 * Desktop replacement for Mihon's QuickJS-backed engine (same public API, `evaluate(script)`),
 * implemented with the standalone Nashorn engine (ES5.1 + selected ES6 features).
 */
@Suppress("UNUSED", "UNCHECKED_CAST")
class JavaScriptEngine(context: Context) {
    suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.Default) {
        val engine = NashornScriptEngineFactory().getScriptEngine("--language=es6", "--no-java", "--no-syntax-extensions")
        val result = engine.eval(script)
        normalize(result) as T
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Double -> if (value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) value.toInt() else value
        is org.openjdk.nashorn.api.scripting.ScriptObjectMirror -> when {
            value.isArray -> value.values.map { normalize(it) }.toTypedArray()
            else -> value.toString()
        }
        else -> value
    }
}
