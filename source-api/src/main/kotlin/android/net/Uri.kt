package android.net

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Hierarchical RFC 2396 URI compatible with the subset of android.net.Uri used by extensions.
 * Encoded components are stored; getters without "Encoded" decode.
 */
abstract class Uri : Comparable<Uri> {
    abstract fun getScheme(): String?
    abstract fun getEncodedAuthority(): String?
    abstract fun getEncodedPath(): String?
    abstract fun getEncodedQuery(): String?
    abstract fun getEncodedFragment(): String?

    fun getAuthority(): String? = getEncodedAuthority()?.let { decode(it) }
    fun getPath(): String? = getEncodedPath()?.let { decode(it) }
    fun getQuery(): String? = getEncodedQuery()?.let { decode(it) }
    fun getFragment(): String? = getEncodedFragment()?.let { decode(it) }
    fun getSchemeSpecificPart(): String = toString().substringAfter(':')
    fun isAbsolute(): Boolean = getScheme() != null
    fun isRelative(): Boolean = getScheme() == null
    fun isHierarchical(): Boolean = true
    fun isOpaque(): Boolean = false

    fun getUserInfo(): String? = getEncodedAuthority()?.takeIf { '@' in it }?.substringBefore('@')?.let { decode(it) }

    fun getHost(): String? {
        val auth = getEncodedAuthority() ?: return null
        val hostPort = auth.substringAfter('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return decode(host).ifEmpty { null }
    }

    fun getPort(): Int {
        val auth = getEncodedAuthority() ?: return -1
        val hostPort = auth.substringAfter('@')
        val afterHost = if (hostPort.startsWith("[")) hostPort.substringAfter(']', "") else hostPort
        val portStr = afterHost.substringAfter(':', "")
        return portStr.toIntOrNull() ?: -1
    }

    fun getPathSegments(): List<String> =
        getEncodedPath()?.split('/')?.filter { it.isNotEmpty() }?.map { decode(it) } ?: emptyList()

    fun getLastPathSegment(): String? = getPathSegments().lastOrNull()

    private fun queryPairs(): List<Pair<String, String?>> {
        val q = getEncodedQuery() ?: return emptyList()
        if (q.isEmpty()) return emptyList()
        return q.split('&').map { part ->
            val idx = part.indexOf('=')
            if (idx < 0) decode(part) to null else decode(part.substring(0, idx)) to decode(part.substring(idx + 1))
        }
    }

    fun getQueryParameterNames(): Set<String> = queryPairs().mapTo(LinkedHashSet()) { it.first }
    fun getQueryParameters(key: String): List<String> = queryPairs().filter { it.first == key }.map { it.second ?: "" }
    fun getQueryParameter(key: String): String? = queryPairs().firstOrNull { it.first == key }?.let { it.second ?: "" }

    fun getBooleanQueryParameter(key: String, defaultValue: Boolean): Boolean {
        val v = getQueryParameter(key) ?: return defaultValue
        val lower = v.lowercase()
        return !("false" == lower || "0" == lower)
    }

    fun normalizeScheme(): Uri = buildUpon().scheme(getScheme()?.lowercase()).build()

    fun buildUpon(): Builder = Builder()
        .scheme(getScheme())
        .encodedAuthority(getEncodedAuthority())
        .encodedPath(getEncodedPath())
        .encodedQuery(getEncodedQuery())
        .encodedFragment(getEncodedFragment())

    override fun toString(): String = buildString {
        getScheme()?.let { append(it).append(':') }
        getEncodedAuthority()?.let { append("//").append(it) }
        getEncodedPath()?.let { append(it) }
        getEncodedQuery()?.let { append('?').append(it) }
        getEncodedFragment()?.let { append('#').append(it) }
    }

    override fun equals(other: Any?): Boolean = other is Uri && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()
    override fun compareTo(other: Uri): Int = toString().compareTo(other.toString())

    class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private var path: String? = null
        private var query: String? = null
        private var fragment: String? = null

        fun scheme(scheme: String?): Builder = apply { this.scheme = scheme }
        fun authority(authority: String?): Builder = apply { this.authority = authority?.let { encode(it, "@:[]") } }
        fun encodedAuthority(authority: String?): Builder = apply { this.authority = authority }
        fun path(path: String?): Builder = apply { this.path = path?.let { encode(it, "/") } }
        fun encodedPath(path: String?): Builder = apply { this.path = path }

        fun appendPath(newSegment: String): Builder = appendEncodedPath(encode(newSegment))

        fun appendEncodedPath(newSegment: String): Builder = apply {
            val current = path ?: ""
            path = when {
                current.isEmpty() -> if (newSegment.startsWith("/")) newSegment else "/$newSegment"
                current.endsWith("/") -> current + newSegment.removePrefix("/")
                else -> current + "/" + newSegment.removePrefix("/")
            }
        }

        fun query(query: String?): Builder = apply { this.query = query?.let { encode(it, "=&") } }
        fun encodedQuery(query: String?): Builder = apply { this.query = query }

        fun appendQueryParameter(key: String, value: String?): Builder = apply {
            val pair = encode(key) + "=" + encode(value ?: "")
            query = if (query.isNullOrEmpty()) pair else "$query&$pair"
        }

        fun clearQuery(): Builder = apply { query = null }
        fun fragment(fragment: String?): Builder = apply { this.fragment = fragment?.let { encode(it) } }
        fun encodedFragment(fragment: String?): Builder = apply { this.fragment = fragment }

        fun build(): Uri = StringUri(scheme, authority, path, query, fragment)

        override fun toString(): String = build().toString()
    }

    private class StringUri(
        private val scheme: String?,
        private val authority: String?,
        private val path: String?,
        private val query: String?,
        private val fragment: String?,
    ) : Uri() {
        override fun getScheme() = scheme
        override fun getEncodedAuthority() = authority
        override fun getEncodedPath() = path
        override fun getEncodedQuery() = query
        override fun getEncodedFragment() = fragment
    }

    companion object {
        @JvmField
        val EMPTY: Uri = StringUri(null, null, "", null, null)

        private const val UNRESERVED = "_-!.~'()*"

        @JvmStatic
        fun parse(uriString: String): Uri {
            var rest = uriString
            var fragment: String? = null
            rest.indexOf('#').takeIf { it >= 0 }?.let {
                fragment = rest.substring(it + 1)
                rest = rest.substring(0, it)
            }
            var query: String? = null
            rest.indexOf('?').takeIf { it >= 0 }?.let {
                query = rest.substring(it + 1)
                rest = rest.substring(0, it)
            }
            var scheme: String? = null
            val colon = rest.indexOf(':')
            val slash = rest.indexOf('/')
            if (colon > 0 && (slash < 0 || colon < slash)) {
                scheme = rest.substring(0, colon)
                rest = rest.substring(colon + 1)
            }
            var authority: String? = null
            if (rest.startsWith("//")) {
                val end = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
                authority = rest.substring(2, end)
                rest = rest.substring(end)
            }
            return StringUri(scheme, authority, rest, query, fragment)
        }

        @JvmStatic
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            StringUri(scheme, null, encode(ssp, "/"), null, fragment?.let { encode(it) })

        @JvmStatic
        fun encode(s: String?): String = encode(s, null)

        @JvmStatic
        fun encode(s: String?, allow: String?): String {
            if (s == null) return ""
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val ch = s[i]
                if (cp < 128 && (ch.isLetterOrDigit() || UNRESERVED.indexOf(ch) >= 0 || (allow != null && allow.indexOf(ch) >= 0))) {
                    sb.append(ch)
                } else {
                    sb.append(URLEncoder.encode(String(Character.toChars(cp)), Charsets.UTF_8).replace("+", "%20"))
                }
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        @JvmStatic
        fun decode(s: String?): String {
            if (s == null) return ""
            return try {
                URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                s
            }
        }

        @JvmStatic
        fun withAppendedPath(baseUri: Uri, pathSegment: String): Uri = baseUri.buildUpon().appendEncodedPath(pathSegment).build()
    }
}
