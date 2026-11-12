package inkoma.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns network failures into a message that says what is wrong and what to try, like Mihon's error screens. */
object ErrorMessages {
    private const val BLOCKED_HINT =
        "The site may be blocked on your network. Try Settings › Advanced › DNS over HTTPS (e.g. Cloudflare, then restart), " +
            "or a VPN."

    fun describe(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.firstNotNullOfOrNull { it.message }?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val reset = chain.any { it is SocketException && it.message?.contains("reset", ignoreCase = true) == true } ||
            chain.any { it is IOException && it.message?.contains("unexpected end of stream", ignoreCase = true) == true }
        return when {
            chain.any { it is UnknownHostException } -> "Can't find the server ($text). Check your internet connection. $BLOCKED_HINT"
            reset -> "Connection reset: the connection was cut before the site answered. $BLOCKED_HINT"
            chain.any { it is SSLException } -> "Secure connection failed ($text). $BLOCKED_HINT"
            chain.any { it is SocketTimeoutException || (it is InterruptedIOException && it.message == "timeout") } ->
                "The site took too long to answer. Try again later. $BLOCKED_HINT"
            chain.any { it is ConnectException || it is NoRouteToHostException } -> "Can't connect to the site ($text). $BLOCKED_HINT"
            else -> text
        }
    }
}
