package android.os

object SystemClock {
    private val startNanos = System.nanoTime()

    @JvmStatic fun elapsedRealtime(): Long = (System.nanoTime() - startNanos) / 1_000_000

    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - startNanos

    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()

    @JvmStatic fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @JvmStatic fun currentThreadTimeMillis(): Long = elapsedRealtime()
}
