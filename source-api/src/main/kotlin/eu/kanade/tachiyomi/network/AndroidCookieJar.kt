package eu.kanade.tachiyomi.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File

/** Persistent cookie store (name kept for binary compatibility with extensions using `network.cookieJar`). */
class AndroidCookieJar(private val file: File) : CookieJar {

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
        val persistent: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = LinkedHashMap<String, Cookie>()

    init {
        if (file.exists()) {
            runCatching {
                json.decodeFromString<List<StoredCookie>>(file.readText()).forEach { s ->
                    val b = Cookie.Builder().name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt)
                    if (s.hostOnly) b.hostOnlyDomain(s.domain) else b.domain(s.domain)
                    if (s.secure) b.secure()
                    if (s.httpOnly) b.httpOnly()
                    val c = b.build()
                    cookies[keyOf(c)] = c
                }
            }
        }
    }

    private fun keyOf(c: Cookie) = "${c.domain}|${c.path}|${c.name}"

    @Synchronized
    private fun persist() {
        val now = System.currentTimeMillis()
        val list = cookies.values.filter { it.persistent && it.expiresAt > now }.map {
            StoredCookie(it.name, it.value, it.expiresAt, it.domain, it.path, it.secure, it.httpOnly, it.hostOnly, it.persistent)
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(list))
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies[keyOf(it)] = it }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = get(url)

    @Synchronized
    fun get(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.values.removeAll { it.expiresAt < now }
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun addAll(url: HttpUrl, cookies: List<Cookie>) = saveFromResponse(url, cookies)

    @Synchronized
    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val matching = cookies.values.filter { it.matches(url) && (cookieNames == null || it.name in cookieNames) }
        matching.forEach { cookies.remove(keyOf(it)) }
        persist()
        return matching.size
    }

    @Synchronized
    fun removeAll() {
        cookies.clear()
        persist()
    }
}
