package inkoma

import eu.kanade.tachiyomi.network.NetworkHelper
import inkoma.core.net.DnsSettings
import inkoma.core.net.DohResolver
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DohResolverTest {
    @Test
    fun resolverIsInstalledForTheWholeProcess() {
        DnsSettings.provider = NetworkHelper.PREF_DOH_AUTO
        val before = DohResolver.lookups.get()
        // A name that can't exist: DoH (if reachable) and then the system resolver both fail.
        assertFailsWith<UnknownHostException> { InetAddress.getAllByName("inkoma-resolver-check.invalid") }
        assertTrue(DohResolver.lookups.get() > before, "lookups must go through the app's resolver")
    }

    @Test
    fun localNamesStayWithTheSystemResolver() {
        DnsSettings.provider = NetworkHelper.PREF_DOH_AUTO
        assertTrue(DohResolver.usesDoh("api.mangadex.org"))
        listOf("localhost", "printer.local", "nas.lan", "192.168.1.10", "::1", "router").forEach {
            assertFalse(DohResolver.usesDoh(it), it)
        }
        assertTrue(InetAddress.getByName("localhost").isLoopbackAddress)
        DnsSettings.provider = NetworkHelper.PREF_DOH_DISABLED
        assertFalse(DohResolver.usesDoh("api.mangadex.org"))
        DnsSettings.provider = NetworkHelper.PREF_DOH_AUTO
    }
}
