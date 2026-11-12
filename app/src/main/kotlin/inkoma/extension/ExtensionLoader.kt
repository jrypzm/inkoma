package inkoma.extension

import com.googlecode.d2j.dex.Dex2jar
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import inkoma.domain.ExtensionStore
import net.dongliu.apk.parser.ApkFile
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Metadata extracted once from the APK manifest and cached next to the converted jar. */
@Serializable
data class ExtensionMetadata(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double?,
    val contentWarning: ContentWarning,
    val sourceClasses: String?,
    val sourceFactory: String?,
    val signatures: List<String>,
)

/**
 * Desktop counterpart of Mihon's ExtensionLoader. Android loads the APK's dex directly; here the dex is
 * translated once to JVM bytecode (dex2jar) at install time, and the jar is loaded with a URLClassLoader.
 */
object ExtensionLoader {
    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    private val logger = Logger.getLogger("ExtensionLoader")
    private val json = Json { ignoreUnknownKeys = true }

    fun apkFile(dir: File, pkg: String) = File(dir, "$pkg.apk")
    /**
     * Converted jar of one extension version. The name carries the extension's version code, so an update never has to
     * overwrite a jar that is still open (Windows locks jars held by a class loader), and [CONVERTER_VERSION], so jars
     * produced by an older, broken converter are regenerated from the stored APK instead of being reused.
     */
    fun jarFile(dir: File, pkg: String, versionCode: Long) = File(dir, "$pkg.v$versionCode.d2j$CONVERTER_VERSION.jar")

    private const val CONVERTER_VERSION = 8

    private fun jarPattern(pkg: String) = Regex("^" + Regex.escape(pkg) + "(\\.v\\d+)?(\\.d2j\\d+)?\\.jar$")

    /** Deletes this extension's other converted jars (older versions or converters). Jars still open stay until next time. */
    fun deleteStaleJars(dir: File, pkg: String, keep: File) {
        val pattern = jarPattern(pkg)
        dir.listFiles { f -> f.name != keep.name && pattern.matches(f.name) }?.forEach { it.delete() }
    }

    /** Every converted jar of [pkg] (used when uninstalling). */
    fun allJars(dir: File, pkg: String): List<File> = jarPattern(pkg).let { p -> dir.listFiles { f -> p.matches(f.name) }?.toList().orEmpty() }
    fun metaFile(dir: File, pkg: String) = File(dir, "$pkg.json")
    fun iconFile(dir: File, pkg: String) = File(dir, "$pkg.png")

    /** Reads the manifest metadata and signing-certificate fingerprints of an APK. */
    fun readApkMetadata(apk: File): ExtensionMetadata {
        ApkFile(apk).use { parser ->
            val meta = parser.apkMeta
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder().parse(parser.manifestXml.byteInputStream())
            val features = doc.getElementsByTagName("uses-feature")
            val isExtension = (0 until features.length).any { (features.item(it) as Element).getAttribute("android:name") == EXTENSION_FEATURE }
            require(isExtension) { "${meta.packageName} isn't an extension" }

            val metaData = HashMap<String, String>()
            val nodes = doc.getElementsByTagName("meta-data")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                metaData[el.getAttribute("android:name")] = el.getAttribute("android:value")
            }
            val versionName = meta.versionName.orEmpty()
            val libVersion = metaData[METADATA_EXTENSION_LIB]?.toDoubleOrNull()?.takeUnless { it == 0.0 }
                ?: versionName.substringBeforeLast('.').toDoubleOrNull()
            val contentWarning = when {
                metaData.containsKey(METADATA_CONTENT_WARNING) -> when (metaData[METADATA_CONTENT_WARNING]?.toIntOrNull()) {
                    1 -> ContentWarning.MIXED
                    2 -> ContentWarning.NSFW
                    else -> ContentWarning.SAFE
                }
                metaData[METADATA_NSFW] == "1" -> ContentWarning.NSFW
                else -> ContentWarning.SAFE
            }
            // v2/v3 (what store extensions use) is verified cryptographically; v1 (JAR signing) is the fallback.
            val v2Certs = ApkSignatureVerifier.verify(apk)
            val signatures = if (v2Certs != null) {
                v2Certs.map { sha256(it.encoded) }
            } else {
                runCatching { parser.apkSingers.flatMap { it.certificateMetas } }.getOrDefault(emptyList()).map { sha256(it.data) }
            }.distinct()
            return ExtensionMetadata(
                pkgName = meta.packageName,
                name = metaData[METADATA_NAME] ?: meta.label?.substringAfter("Tachiyomi: ") ?: meta.packageName,
                versionName = versionName,
                versionCode = meta.versionCode ?: 0L,
                libVersion = libVersion,
                contentWarning = contentWarning,
                sourceClasses = metaData[METADATA_SOURCE_CLASS],
                sourceFactory = metaData[METADATA_SOURCE_FACTORY],
                signatures = signatures,
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Translates every classes*.dex of [apk] into a single jar at [jar], and copies the APK's
     * non-code entries (assets, resources) so `getResourceAsStream` behaves as on Android.
     */
    fun convertApkToJar(apk: File, jar: File) {
        // Reflective (not inlined) check that the patched NewTransformer is the class actually loaded.
        check(runCatching { com.googlecode.dex2jar.ir.ts.NewTransformer::class.java.getField("INKOMA_PATCHED") }.isSuccess) {
            "Unpatched dex2jar NewTransformer on the classpath"
        }
        val tmpDir = Files.createTempDirectory(jar.parentFile.toPath(), "d2j")
        try {
            val partJars = ArrayList<File>()
            ZipFile(apk).use { zip ->
                val dexEntries = zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.toList()
                require(dexEntries.isNotEmpty()) { "APK contains no dex" }
                for (entry in dexEntries) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val part = File(tmpDir.toFile(), entry.name.removeSuffix(".dex") + ".jar")
                    Dex2jar.from(bytes)
                        .reUseReg(false)
                        .topoLogicalSort()
                        .skipDebug(true)
                        .optimizeSynchronized(false)
                        .printIR(false)
                        .noCode(false)
                        .skipExceptions(false)
                        // Keep names verbatim: Kotlin mangles inline-class members with '-' (e.g. "constructor-impl"),
                        // and the default sanitizer would rename them, breaking every call into kotlin/okhttp/coroutines.
                        .dontSanitizeNames(true)
                        .to(part.toPath())
                    partJars.add(part)
                }
            }
            val out = File(tmpDir.toFile(), "out.jar")
            FileSystems.newFileSystem(URI.create("jar:" + out.toURI()), mapOf("create" to "true")).use { fs ->
                for (part in partJars) {
                    FileSystems.newFileSystem(part.toPath()).use { partFs ->
                        Files.walk(partFs.getPath("/")).use { paths ->
                            paths.filter { Files.isRegularFile(it) }.forEach { src ->
                                val dst = fs.getPath(src.toString())
                                dst.parent?.let { Files.createDirectories(it) }
                                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && !it.name.endsWith(".dex") && !it.name.startsWith("META-INF/") && it.name != "AndroidManifest.xml" }
                        .forEach { entry ->
                            val dst = fs.getPath("/" + entry.name)
                            dst.parent?.let { Files.createDirectories(it) }
                            zip.getInputStream(entry).use { Files.copy(it, dst, StandardCopyOption.REPLACE_EXISTING) }
                        }
                }
            }
            // dex2jar's frames are wrong for types outside the dex; recompute them against the real hierarchy.
            val fixed = File(tmpDir.toFile(), "fixed.jar")
            FrameRecomputer.recompute(out, fixed, ExtensionLoader::class.java.classLoader)
            Files.move(fixed.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    fun readMetadata(dir: File, pkg: String): ExtensionMetadata? =
        metaFile(dir, pkg).takeIf { it.exists() }?.let { runCatching { json.decodeFromString<ExtensionMetadata>(it.readText()) }.getOrNull() }

    fun writeMetadata(dir: File, metadata: ExtensionMetadata) {
        metaFile(dir, metadata.pkgName).writeText(json.encodeToString(ExtensionMetadata.serializer(), metadata))
    }

    fun isTrusted(meta: ExtensionMetadata, stores: List<ExtensionStore>, trusted: Set<String>): Boolean {
        if (meta.signatures.isEmpty()) return false
        val storeKeys = stores.mapTo(HashSet()) { it.signingKey }
        val key = "${meta.pkgName}:${meta.versionCode}:${meta.signatures.last()}"
        return storeKeys.any { it in meta.signatures } || key in trusted
    }

    /** Loads one installed extension from [dir]. */
    fun load(
        dir: File,
        pkg: String,
        stores: List<ExtensionStore>,
        trusted: Set<String>,
        allowedContentWarnings: Set<ContentWarning>,
        parent: ClassLoader,
    ): Extension.Installed? {
        val meta = readMetadata(dir, pkg) ?: return null
        val store = stores.firstOrNull { it.signingKey in meta.signatures }
        val icon = iconFile(dir, pkg).takeIf { it.exists() }?.absolutePath

        fun notLoaded(reason: Extension.NotLoaded.Reason) = Extension.NotLoaded(
            name = meta.name, pkgName = meta.pkgName, versionName = meta.versionName, versionCode = meta.versionCode,
            libVersion = meta.libVersion, contentWarning = meta.contentWarning, signatures = meta.signatures,
            store = store, iconPath = icon, reason = reason,
        )

        if (meta.versionName.isEmpty()) return notLoaded(Extension.NotLoaded.Reason.Malformed)
        val libVersion = meta.libVersion
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) return notLoaded(Extension.NotLoaded.Reason.UnsupportedLibVersion)
        if (meta.signatures.isEmpty()) return notLoaded(Extension.NotLoaded.Reason.Unsigned)
        if (!isTrusted(meta, stores, trusted)) return notLoaded(Extension.NotLoaded.Reason.Untrusted(meta.signatures.last()))
        if (meta.contentWarning !in allowedContentWarnings) return notLoaded(Extension.NotLoaded.Reason.Filtered)
        val classes = meta.sourceClasses
        if (classes.isNullOrBlank()) return notLoaded(Extension.NotLoaded.Reason.Malformed)

        val jar = jarFile(dir, pkg, meta.versionCode)
        deleteStaleJars(dir, pkg, keep = jar)
        if (!jar.exists()) {
            try {
                convertApkToJar(apkFile(dir, pkg), jar)
            } catch (e: Throwable) {
                return notLoaded(Extension.NotLoaded.Reason.Failed(e.message ?: e.javaClass.name, e.stackTraceToString()))
            }
        }
        val loader = URLClassLoader("ext:$pkg", arrayOf(jar.toURI().toURL()), parent)
        val sources = try {
            classes.split(";").map { it.trim() }.map { if (it.startsWith(".")) meta.pkgName + it else it }.flatMap { cls ->
                when (val obj = Class.forName(cls, false, loader).getDeclaredConstructor().newInstance()) {
                    is Source -> listOf(obj)
                    is SourceFactory -> obj.createSources()
                    else -> throw IllegalStateException("Unknown source class type: ${obj.javaClass}")
                }
            }
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Extension load error: ${meta.name} ($pkg)", e)
            runCatching { loader.close() }
            val root = generateSequence(e) { it.cause }.last()
            return notLoaded(Extension.NotLoaded.Reason.Failed(root.message ?: root.javaClass.name, e.stackTraceToString()))
        }
        val langs = sources.map { it.lang }.toSet()
        return Extension.Loaded(
            name = meta.name, pkgName = meta.pkgName, versionName = meta.versionName, versionCode = meta.versionCode,
            libVersion = libVersion, contentWarning = meta.contentWarning, signatures = meta.signatures, store = store,
            iconPath = icon, lang = when (langs.size) { 0 -> ""; 1 -> langs.first(); else -> "all" },
            sources = sources, classLoader = loader,
        )
    }
}
