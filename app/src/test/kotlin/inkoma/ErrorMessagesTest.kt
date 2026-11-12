package inkoma

import inkoma.core.ErrorMessages
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMessagesTest {
    @Test
    fun networkFailuresExplainWhatToTry() {
        val reset = ErrorMessages.describe(IOException("wrapped", SocketException("Connection reset")))
        assertTrue(reset.startsWith("Connection reset") && "DNS over HTTPS" in reset, reset)
        val dns = ErrorMessages.describe(UnknownHostException("api.mangadex.org"))
        assertTrue("Can't find the server" in dns && "DNS over HTTPS" in dns, dns)
        assertEquals("HTTP error 404", ErrorMessages.describe(Exception("HTTP error 404")))
    }
}
