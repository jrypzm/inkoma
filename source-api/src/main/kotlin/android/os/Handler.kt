package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Looper/Handler stand-ins: the "main" looper is a single background thread (there is no Android UI thread). */
class Looper private constructor(internal val executor: ScheduledExecutorService, private val thread: () -> Thread?) {
    fun getThread(): Thread? = thread()
    fun isCurrentThread(): Boolean = Thread.currentThread() == thread()
    fun quit() = executor.shutdown()
    fun quitSafely() = executor.shutdown()

    companion object {
        @Volatile private var mainThread: Thread? = null
        private val main = Looper(
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "android-main-looper").also { it.isDaemon = true; mainThread = it } },
        ) { mainThread }
        private val threadLooper = ThreadLocal<Looper?>()

        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper? = if (Thread.currentThread() == mainThread) main else threadLooper.get()
        @JvmStatic fun prepare() {
            if (threadLooper.get() == null) {
                val t = Thread.currentThread()
                threadLooper.set(Looper(Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "android-looper").also { it.isDaemon = true } }) { t })
            }
        }
        @JvmStatic fun loop() {}
    }
}

open class Handler @JvmOverloads constructor(private val looper: Looper = Looper.getMainLooper()) {
    constructor(looper: Looper, callback: Callback?) : this(looper)

    fun interface Callback {
        fun handleMessage(msg: Message): Boolean
    }

    fun getLooper(): Looper = looper
    fun post(r: Runnable): Boolean = postDelayed(r, 0)
    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        looper.executor.schedule(r, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return true
    }
    fun postAtFrontOfQueue(r: Runnable): Boolean = post(r)
    fun removeCallbacks(r: Runnable) {}
    fun removeCallbacksAndMessages(token: Any?) {}
    open fun handleMessage(msg: Message) {}
}

class Message {
    @JvmField var what: Int = 0
    @JvmField var arg1: Int = 0
    @JvmField var arg2: Int = 0
    @JvmField var obj: Any? = null

    companion object {
        @JvmStatic fun obtain(): Message = Message()
    }
}
