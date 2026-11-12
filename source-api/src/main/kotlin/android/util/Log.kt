package android.util

import java.util.logging.Level
import java.util.logging.Logger

object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private fun log(level: Level, tag: String?, msg: String?, tr: Throwable? = null): Int {
        Logger.getLogger(tag ?: "Extension").log(level, msg ?: "", tr)
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String?): Int = log(Level.FINEST, tag, msg)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.FINEST, tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = log(Level.FINE, tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.FINE, tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = log(Level.INFO, tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = log(Level.WARNING, tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.WARNING, tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = log(Level.WARNING, tag, tr?.message, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = log(Level.SEVERE, tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.SEVERE, tag, msg, tr)
    const val ASSERT = 7
    @JvmStatic fun println(priority: Int, tag: String?, msg: String?): Int = log(
        when (priority) { VERBOSE -> Level.FINEST; DEBUG -> Level.FINE; INFO -> Level.INFO; WARN -> Level.WARNING; else -> Level.SEVERE }, tag, msg,
    )
    @JvmStatic fun wtf(tag: String?, msg: String?): Int = log(Level.SEVERE, tag, msg)
    @JvmStatic fun wtf(tag: String?, tr: Throwable?): Int = log(Level.SEVERE, tag, tr?.message, tr)
    @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.SEVERE, tag, msg, tr)
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = level >= INFO
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""
}
