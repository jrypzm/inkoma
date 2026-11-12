package inkoma.core.net

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.net.spi.InetAddressResolverProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import java.util.stream.Stream

/** DNS mode for the whole app, from Settings › Advanced › DNS over HTTPS. Read on every lookup, so changes apply at once. */
object DnsSettings {
    @Volatile
    var provider: Int = NetworkHelper.PREF_DOH_AUTO
        set(value) {
            if (field != value) DohResolver.clearCache()
            field = value
        }
}

/**
 * Process-wide name resolver (JEP 418), registered in META-INF/services. Every lookup in the app goes through it:
 * extensions (OkHttp), image loading and the embedded browser. Names are resolved with DNS over HTTPS so that DNS-based
 * blocking by a network does not break sources, falling back to the operating system's resolver if DoH is unreachable.
 */
class DohResolverProvider : InetAddressResolverProvider() {
    override fun get(configuration: Configuration): InetAddressResolver = DohResolver(configuration.builtinResolver())

    override fun name(): String = "inkoma-doh"
}

class DohResolver(private val system: InetAddressResolver) : InetAddressResolver {
    override fun lookupByName(host: String, policy: LookupPolicy): Stream<InetAddress> {
        lookups.incrementAndGet()
        if (!usesDoh(host) || inLookup.get()) return system.lookupByName(host, policy)
        val addresses = cached(host) ?: resolveWithDoh(host)?.also { cache[host.lowercase()] = Entry(it, System.nanoTime()) }
        val ordered = addresses?.let { order(it, policy) }
        return if (ordered.isNullOrEmpty()) system.lookupByName(host, policy) else ordered.stream()
    }

    override fun lookupByAddress(addr: ByteArray): String = system.lookupByAddress(addr)

    private fun resolveWithDoh(host: String): List<InetAddress>? {
        inLookup.set(true)
        try {
            for (provider in providers()) {
                if (provider.downUntil > System.nanoTime()) continue
                try {
                    val result = provider.dns.lookup(host)
                    if (result.isNotEmpty()) return result
                } catch (e: UnknownHostException) {
                    // The provider answered but has no record (or the query failed): let the next one / the OS try.
                    if (e.cause is IOException && e.cause !is UnknownHostException) provider.markDown()
                } catch (e: Exception) {
                    provider.markDown()
                    logger.log(Level.FINE, "DoH lookup of $host via ${provider.name} failed", e)
                }
            }
            return null
        } finally {
            inLookup.set(false)
        }
    }

    private fun providers(): List<Provider> = when (val p = DnsSettings.provider) {
        NetworkHelper.PREF_DOH_DISABLED -> emptyList()
        NetworkHelper.PREF_DOH_AUTO -> AUTO.mapNotNull(::provider)
        else -> listOfNotNull(provider(p))
    }

    private class Provider(val name: String, val dns: DnsOverHttps) {
        @Volatile var downUntil = 0L

        fun markDown() {
            downUntil = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
        }
    }

    private class Entry(val addresses: List<InetAddress>, val at: Long)

    companion object {
        private val logger = Logger.getLogger("DohResolver")
        private val AUTO = listOf(NetworkHelper.PREF_DOH_CLOUDFLARE, NetworkHelper.PREF_DOH_GOOGLE, NetworkHelper.PREF_DOH_QUAD9)
        private val CACHE_TTL = TimeUnit.MINUTES.toNanos(10)
        private val inLookup = ThreadLocal.withInitial { false }

        /** Number of name lookups that reached this resolver (lets tests check it is installed process-wide). */
        internal val lookups = java.util.concurrent.atomic.AtomicInteger()
        private val cache = ConcurrentHashMap<String, Entry>()
        private val instances = ConcurrentHashMap<Int, Provider>()

        /** Small, fast client used only to talk to the DoH servers (by IP address, so it never needs DNS itself). */
        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS)
                .build()
        }

        fun clearCache() = cache.clear()

        private fun provider(id: Int): Provider? = instances[id] ?: NetworkHelper.dohUrl(id)?.let { (url, bootstrap) ->
            val dns = DnsOverHttps.Builder()
                .client(client)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(bootstrap.map { InetAddress.getByAddress(it, parseIp(it)) })
                .includeIPv6(true)
                .build()
            instances.getOrPut(id) { Provider(url, dns) }
        }

        private fun parseIp(ip: String): ByteArray = ip.split('.').map { it.toInt().toByte() }.toByteArray()

        private fun cached(host: String): List<InetAddress>? {
            val e = cache[host.lowercase()] ?: return null
            if (System.nanoTime() - e.at > CACHE_TTL) {
                cache.remove(host.lowercase())
                return null
            }
            return e.addresses
        }

        /** Local names, IP literals and single-label hosts are left to the operating system. */
        internal fun usesDoh(host: String): Boolean {
            if (DnsSettings.provider == NetworkHelper.PREF_DOH_DISABLED) return false
            val h = host.lowercase().trimEnd('.')
            if (!h.contains('.') || h.contains(':')) return false
            if (h.all { it.isDigit() || it == '.' }) return false
            return listOf(".local", ".localhost", ".lan", ".home", ".internal", ".arpa").none { h.endsWith(it) }
        }

        private fun order(addresses: List<InetAddress>, policy: LookupPolicy): List<InetAddress> {
            val c = policy.characteristics()
            val v4 = c and InetAddressResolver.LookupPolicy.IPV4 != 0
            val v6 = c and InetAddressResolver.LookupPolicy.IPV6 != 0
            val filtered = addresses.filter { (it is Inet4Address && v4) || (it is Inet6Address && v6) }
            return when {
                c and InetAddressResolver.LookupPolicy.IPV6_FIRST != 0 -> filtered.sortedBy { if (it is Inet6Address) 0 else 1 }
                else -> filtered.sortedBy { if (it is Inet4Address) 0 else 1 }
            }
        }
    }
}
