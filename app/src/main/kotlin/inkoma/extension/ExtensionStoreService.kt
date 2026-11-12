package inkoma.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import inkoma.domain.ExtensionStore
import okio.BufferedSource
import okio.buffer
import okio.gzip
import java.util.logging.Level
import java.util.logging.Logger

private sealed interface BaseNetworkExtensionStore {
    fun toExtensionStore(indexUrl: String): ExtensionStore
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) : BaseNetworkExtensionStore {
    @Serializable
    data class Contact(@ProtoNumber(1) val website: String, @ProtoNumber(2) val discord: String? = null)

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<NetExtension>)

    @Serializable
    data class NetExtension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: NetContentWarning = NetContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<NetSource>,
    )

    @Serializable
    data class Resources(@ProtoNumber(1) val apkUrl: String, @ProtoNumber(2) val iconUrl: String)

    @Serializable
    data class NetSource(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    @Serializable
    enum class NetContentWarning {
        @ProtoNumber(0) @JsonNames("CONTENT_WARNING_UNSPECIFIED") UNSPECIFIED,
        @ProtoNumber(1) @JsonNames("CONTENT_WARNING_SAFE") SAFE,
        @ProtoNumber(2) @JsonNames("CONTENT_WARNING_MIXED") MIXED,
        @ProtoNumber(3) @JsonNames("CONTENT_WARNING_NSFW") NSFW,
    }

    override fun toExtensionStore(indexUrl: String) = ExtensionStore(
        indexUrl = indexUrl,
        name = name,
        badgeLabel = badgeLabel,
        signingKey = signingKey,
        contactWebsite = contact.website,
        contactDiscord = contact.discord,
        isLegacy = false,
        extensionListUrl = extensionListUrl,
    )
}

@Serializable
private data class NetworkLegacyExtensionRepo(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta,
) : BaseNetworkExtensionStore {
    @Serializable
    data class Meta(val name: String, val shortName: String? = null, val website: String, val signingKeyFingerprint: String)

    override fun toExtensionStore(indexUrl: String) = ExtensionStore(
        indexUrl = indexUrl,
        name = meta.name,
        badgeLabel = meta.shortName ?: meta.name,
        signingKey = meta.signingKeyFingerprint,
        contactWebsite = meta.website,
        contactDiscord = null,
        isLegacy = true,
        extensionListUrl = null,
    )
}

@Serializable
private data class NetworkLegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<Source>? = null,
) {
    @Serializable
    data class Source(val id: Long, val lang: String, val name: String, val baseUrl: String)

    fun toAvailable(store: ExtensionStore, base: String) = Extension.Available(
        name = name.substringAfter("Tachiyomi: "),
        pkgName = pkg,
        apkUrl = "$base/apk/$apk",
        iconUrl = "$base/icon/$pkg.png",
        libVersion = version.substringBeforeLast('.').toDouble(),
        versionCode = code,
        versionName = version,
        lang = lang,
        contentWarning = if (nsfw == 1) ContentWarning.NSFW else ContentWarning.SAFE,
        sources = if (sources.isNullOrEmpty()) {
            listOf(Extension.AvailableSource(0, name, lang, ""))
        } else {
            sources.map { Extension.AvailableSource(it.id, it.name, it.lang, it.baseUrl) }
        },
        store = store,
    )
}

private fun NetworkExtensionStore.ExtensionList.toAvailable(store: ExtensionStore): List<Extension.Available> =
    extensions.map { ext ->
        val langs = ext.sources.map { it.language }.toSet()
        Extension.Available(
            name = ext.name,
            pkgName = ext.packageName,
            apkUrl = ext.resources.apkUrl,
            iconUrl = ext.resources.iconUrl,
            libVersion = ext.extensionLib.toDouble(),
            versionCode = ext.versionCode,
            versionName = ext.versionName,
            lang = if (langs.size == 1) langs.first() else "all",
            contentWarning = when (ext.contentWarning) {
                NetworkExtensionStore.NetContentWarning.MIXED -> ContentWarning.MIXED
                NetworkExtensionStore.NetContentWarning.NSFW -> ContentWarning.NSFW
                else -> ContentWarning.SAFE
            },
            sources = ext.sources.map { Extension.AvailableSource(it.id, it.name, it.language, it.homeUrl) },
            store = store,
        )
    }

/** Port of Mihon's ExtensionStoreService: supports v2 stores (JSON or protobuf, optionally gzipped) and legacy repos. */
@OptIn(ExperimentalSerializationApi::class)
class ExtensionStoreService(private val network: NetworkHelper) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val protoBuf = ProtoBuf

    suspend fun fetch(indexUrl: String): Result<ExtensionStore> = withContext(Dispatchers.IO) { fetchStore(indexUrl) }

    private suspend fun fetchStore(indexUrl: String): Result<ExtensionStore> {
        var updatedIndexUrl = indexUrl
        return try {
            val response = network.client.newCall(GET(updatedIndexUrl)).awaitSuccess()
            val store = response.body.source().decompressIfGzipped().use { source ->
                val networkStore: BaseNetworkExtensionStore = when (source.peek().readByte()) {
                    0x5B.toByte() -> {
                        require(indexUrl.endsWith("/index.min.json")) { "Provided legacy store url is not valid" }
                        updatedIndexUrl = indexUrl.replace("/index.min.json", "/repo.json")
                        network.client.newCall(GET(updatedIndexUrl)).awaitSuccess().body.source().use {
                            json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it)
                        }
                    }
                    0x7B.toByte() -> try {
                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source.peek())
                    } catch (_: IllegalArgumentException) {
                        json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                    }
                    else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                }
                if (networkStore is NetworkLegacyExtensionRepo && networkStore.indexV2 != null) {
                    return fetchStore(networkStore.indexV2)
                }
                networkStore.toExtensionStore(updatedIndexUrl)
            }
            Result.success(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.getLogger("ExtensionStore").log(Level.WARNING, "Failed to add extension store '$updatedIndexUrl'", e)
            Result.failure(e)
        }
    }

    suspend fun getExtensions(store: ExtensionStore): Result<List<Extension.Available>> = withContext(Dispatchers.IO) {
        try {
            val list = when {
                store.extensionListUrl != null -> {
                    network.client.newCall(GET(store.extensionListUrl)).awaitSuccess().body.source().decompressIfGzipped().use { source ->
                        when (source.peek().readByte()) {
                            0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore.ExtensionList>(source)
                            else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(source.readByteArray())
                        }.toAvailable(store)
                    }
                }
                !store.isLegacy -> {
                    network.client.newCall(GET(store.indexUrl)).awaitSuccess().body.source().decompressIfGzipped().use { source ->
                        when (source.peek().readByte()) {
                            0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                            else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                        }.extensionList!!.toAvailable(store)
                    }
                }
                else -> {
                    val base = store.indexUrl.removeSuffix("/repo.json")
                    network.client.newCall(GET("$base/index.min.json")).awaitSuccess().body.source().use { source ->
                        json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source).map { it.toAvailable(store, base) }
                    }
                }
            }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked -> runCatching { peeked.readShort().toInt() == 0x1f8b }.getOrDefault(false) }
        return if (isGzip) gzip().buffer() else this
    }
}
