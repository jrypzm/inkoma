package inkoma.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import inkoma.core.AppDirs
import inkoma.core.AppPreferences
import inkoma.data.ExtensionStoreRepository
import inkoma.domain.ExtensionStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Desktop port of Mihon's ExtensionManager (install/update/remove, trust, store index refresh). */
class ExtensionManager(
    private val network: NetworkHelper,
    private val preferences: AppPreferences,
    private val storeRepository: ExtensionStoreRepository,
    private val storeService: ExtensionStoreService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File get() = AppDirs.extensions
    private val loadMutex = Mutex()

    private val _installed = MutableStateFlow<List<Extension.Loaded>>(emptyList())
    val installed: StateFlow<List<Extension.Loaded>> = _installed.asStateFlow()

    private val _notLoaded = MutableStateFlow<List<Extension.NotLoaded>>(emptyList())
    val notLoaded: StateFlow<List<Extension.NotLoaded>> = _notLoaded.asStateFlow()

    private val _available = MutableStateFlow<List<Extension.Available>>(emptyList())
    val available: StateFlow<List<Extension.Available>> = _available.asStateFlow()

    private val _installSteps = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    val installSteps: StateFlow<Map<String, InstallStep>> = _installSteps.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Called whenever the set of loaded sources changes. */
    var onSourcesChanged: (List<Extension.Loaded>) -> Unit = {}

    private fun allowedContentWarnings(): Set<ContentWarning> =
        if (preferences.showNsfwSource.get()) ContentWarning.entries.toSet() else setOf(ContentWarning.SAFE)

    /** Loads all installed extensions from disk. */
    suspend fun loadInstalled() = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val stores = storeRepository.getAll()
            val trusted = preferences.trustedExtensions.get()
            val parent = ExtensionManager::class.java.classLoader
            val pkgs = dir.listFiles { f -> f.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") }.orEmpty()
            val results = pkgs.map { pkg -> async { ExtensionLoader.load(dir, pkg, stores, trusted, allowedContentWarnings(), parent) } }.awaitAll().filterNotNull()
            _installed.value = results.filterIsInstance<Extension.Loaded>().sortedBy { it.name.lowercase() }
            _notLoaded.value = results.filterIsInstance<Extension.NotLoaded>().sortedBy { it.name.lowercase() }
            markUpdates()
            onSourcesChanged(_installed.value)
        }
    }

    private fun markUpdates() {
        val availableByPkg = _available.value.associateBy { it.pkgName }
        _installed.update { list ->
            list.map { ext ->
                val avail = availableByPkg[ext.pkgName]
                ext.copy(
                    hasUpdate = avail != null && avail.versionCode > ext.versionCode && avail.libVersion in ExtensionLoader.SUPPORTED_LIB_VERSIONS,
                    isObsolete = _available.value.isNotEmpty() && avail == null && ext.store != null,
                )
            }
        }
        preferences.extensionUpdatesCount.set(_installed.value.count { it.hasUpdate })
    }

    /** Fetches every store's index (Mihon: "Refresh" in Extensions tab). */
    fun refreshAvailable() {
        scope.launch {
            _refreshing.value = true
            try {
                val stores = storeRepository.getAll()
                val all = stores.map { store -> async { storeService.getExtensions(store).getOrDefault(emptyList()) } }.awaitAll().flatten()
                _available.value = all
                    .filter { it.libVersion in ExtensionLoader.SUPPORTED_LIB_VERSIONS }
                    .groupBy { it.pkgName }
                    .map { (_, exts) -> exts.maxBy { it.versionCode } }
                    .sortedBy { it.name.lowercase() }
                markUpdates()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun setStep(pkg: String, step: InstallStep) {
        _installSteps.update { if (step == InstallStep.Idle) it - pkg else it + (pkg to step) }
    }

    fun install(extension: Extension.Available) {
        scope.launch { installInternal(extension) }
    }

    fun update(pkgName: String) {
        val avail = _available.value.firstOrNull { it.pkgName == pkgName } ?: return
        install(avail)
    }

    fun updateAll() {
        _installed.value.filter { it.hasUpdate }.forEach { update(it.pkgName) }
    }

    fun cancelInstall(pkgName: String) {
        setStep(pkgName, InstallStep.Idle)
    }

    private suspend fun installInternal(ext: Extension.Available) {
        val pkg = ext.pkgName
        setStep(pkg, InstallStep.Downloading)
        val tmp = File.createTempFile("ext", ".apk", dir)
        try {
            network.client.newCall(GET(ext.apkUrl)).awaitSuccess().use { resp ->
                resp.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            if (_installSteps.value[pkg] == null) return
            setStep(pkg, InstallStep.Installing)
            installApk(tmp, expectedPkg = pkg)
            runCatching {
                network.client.newCall(GET(ext.iconUrl)).awaitSuccess().use { resp ->
                    resp.body.byteStream().use { input -> ExtensionLoader.iconFile(dir, pkg).outputStream().use { input.copyTo(it) } }
                }
            }
            setStep(pkg, InstallStep.Installed)
            loadInstalled()
            setStep(pkg, InstallStep.Idle)
        } catch (e: Exception) {
            setStep(pkg, InstallStep.Error)
        } finally {
            tmp.delete()
        }
    }

    /** Installs a local APK (also used by "Install from file"). */
    fun installApk(apk: File, expectedPkg: String? = null) {
        val meta = ExtensionLoader.readApkMetadata(apk)
        if (meta.signatures.isEmpty()) throw SecurityException("${meta.pkgName} is not signed")
        if (expectedPkg != null) require(meta.pkgName == expectedPkg) { "Package mismatch: ${meta.pkgName}" }
        val existing = ExtensionLoader.readMetadata(dir, meta.pkgName)
        if (existing != null && existing.signatures.isNotEmpty() && meta.signatures.isNotEmpty() &&
            existing.signatures.last() != meta.signatures.last()
        ) {
            throw SecurityException("Signature mismatch for ${meta.pkgName}: uninstall the existing extension first")
        }
        val target = ExtensionLoader.apkFile(dir, meta.pkgName)
        Files.copy(apk.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        // A new file per version: the previous version's jar may still be open (and locked on Windows).
        val jar = ExtensionLoader.jarFile(dir, meta.pkgName, meta.versionCode)
        if (!jar.exists()) ExtensionLoader.convertApkToJar(target, jar)
        ExtensionLoader.writeMetadata(dir, meta)
    }

    fun installFromFile(apk: File) {
        scope.launch {
            runCatching { installApk(apk) }
            loadInstalled()
        }
    }

    fun uninstall(pkgName: String) {
        scope.launch {
            _installed.value.firstOrNull { it.pkgName == pkgName }?.let { (it.classLoader as? java.io.Closeable)?.close() }
            listOf(
                ExtensionLoader.apkFile(dir, pkgName), ExtensionLoader.metaFile(dir, pkgName), ExtensionLoader.iconFile(dir, pkgName),
            ).plus(ExtensionLoader.allJars(dir, pkgName)).forEach { it.delete() }
            preferences.trustedExtensions.set(preferences.trustedExtensions.get().filterNot { it.startsWith("$pkgName:") }.toSet())
            loadInstalled()
        }
    }

    /** Mihon's TrustExtension.trust: trusts this exact pkg/version/signature, then reloads. */
    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        val current = preferences.trustedExtensions.get().filterNot { it.startsWith("$pkgName:") }.toMutableSet()
        current += "$pkgName:$versionCode:$signatureHash"
        preferences.trustedExtensions.set(current)
        scope.launch { loadInstalled() }
    }

    fun revokeAllTrust() {
        preferences.trustedExtensions.delete()
        scope.launch { loadInstalled() }
    }

    // ---- Stores -------------------------------------------------------------------------------

    sealed interface AddStoreResult {
        data object Success : AddStoreResult
        data object Duplicate : AddStoreResult
        data object InvalidUrl : AddStoreResult
        data class Error(val message: String) : AddStoreResult
    }

    suspend fun addStore(url: String): AddStoreResult {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return AddStoreResult.InvalidUrl
        if (storeRepository.contains(trimmed)) return AddStoreResult.Duplicate
        val result = storeService.fetch(trimmed)
        return result.fold(
            onSuccess = { store ->
                if (storeRepository.getAll().any { it.indexUrl == store.indexUrl }) return AddStoreResult.Duplicate
                storeRepository.upsert(store)
                loadInstalled()
                refreshAvailable()
                AddStoreResult.Success
            },
            onFailure = { AddStoreResult.Error(it.message ?: it.javaClass.simpleName) },
        )
    }

    fun removeStore(store: ExtensionStore) {
        storeRepository.delete(store.indexUrl)
        scope.launch {
            loadInstalled()
            refreshAvailable()
        }
    }

    /** Re-fetches store metadata (name, key) like Mihon's periodic store refresh. */
    fun refreshStores() {
        scope.launch {
            storeRepository.getAll().forEach { store ->
                storeService.fetch(store.indexUrl).getOrNull()?.let { storeRepository.upsert(it) }
            }
        }
    }
}
