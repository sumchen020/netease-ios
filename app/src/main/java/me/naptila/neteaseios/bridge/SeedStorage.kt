package me.naptila.neteaseios.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.IOException

enum class SeedTransportMode(val prefValue: String) {
    STANDARD("standard"),
    SAF_MEDIA("saf_media");

    companion object {
        fun fromPref(value: String?): SeedTransportMode =
            entries.firstOrNull { it.prefValue == value } ?: STANDARD
    }
}

/** Both transports return a real filesystem path for MPay, never a SAF content URI. */
internal data class PreparedSeed(
    val sdkPath: String,
    val cleanupUri: Uri,
    val usedSaf: Boolean
)

internal class DirectoryAccessRequired : IOException("directory-access-required")

internal class SeedStorageException(message: String) : IOException(message)

internal object SeedStorage {
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val MEDIA_ID = "primary:Android/media"
    private val FILE_NAME = Regex("^ntes_mpay_[a-f0-9]{32}\\.tmp$")
    private val CACHE_URI = Regex("^content://[a-zA-Z0-9._-]+/widget_file_cache/ntes_mpay_[a-f0-9]{32}\\.tmp$")

    fun getTransportMode(context: Context, packageName: String): SeedTransportMode {
        val raw = MpayBridgeSession.prefs(context).getString("seed_mode_$packageName", null)
        // Apply the game's recommended default only when there is no explicit user choice.
        // A user-selected transport remains sticky per game.
        return if (raw == null && packageName == "com.netease.onmyoji") {
            SeedTransportMode.SAF_MEDIA
        } else {
            SeedTransportMode.fromPref(raw)
        }
    }

    fun setTransportMode(context: Context, packageName: String, mode: SeedTransportMode) {
        MpayBridgeSession.prefs(context).edit()
            .putString("seed_mode_$packageName", mode.prefValue)
            .apply()
    }

    fun isNonEmptyTokenEnabled(context: Context, packageName: String): Boolean =
        MpayBridgeSession.prefs(context).getBoolean("seed_non_empty_token_$packageName", false)

    fun setNonEmptyTokenEnabled(context: Context, packageName: String, enabled: Boolean) {
        MpayBridgeSession.prefs(context).edit()
            .putBoolean("seed_non_empty_token_$packageName", enabled)
            .apply()
    }

    fun hasValidDirectoryGrant(context: Context, packageName: String): Boolean {
        if (!BridgeProtocol.isValidPackageName(packageName)) return false
        val saved = MpayBridgeSession.prefs(context).getString("media_tree_$packageName", null)
            ?: return false
        val tree = runCatching { Uri.parse(saved) }.getOrNull() ?: return false
        if (runCatching { requireTree(tree, packageName) }.isFailure) return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            }
        }.getOrDefault(false)
    }

    /**
     * Verifies transport readiness before launching GMWebviewActivity so missing providers
     * or missing SAF grants are surfaced immediately without opening an empty WebView first.
     */
    fun ensureReady(context: Context, target: GameTarget, mode: SeedTransportMode) {
        if (mode == SeedTransportMode.STANDARD) {
            val probeUri = target.cacheUri("probe")
            val reachable = runCatching {
                context.contentResolver.acquireUnstableContentProviderClient(probeUri)?.use { true } ?: false
            }.getOrDefault(false)
            if (!reachable) {
                throw SeedStorageException(
                    "无法连接「${target.appLabel}」缓存接口；若该游戏未开放缓存接口（如阴阳师），请切换为「共享目录方式」后重试。"
                )
            }
            return
        }

        val pkg = target.spec.packageName
        val saved = MpayBridgeSession.prefs(context).getString("media_tree_$pkg", null)
            ?: throw DirectoryAccessRequired()
        val tree = runCatching { Uri.parse(saved) }.getOrNull() ?: throw DirectoryAccessRequired()
        if (runCatching { requireTree(tree, pkg) }.isFailure) {
            throw DirectoryAccessRequired()
        }
        val persisted = runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            }
        }.getOrDefault(false)
        if (!persisted) {
            throw DirectoryAccessRequired()
        }

        val folderId = "$MEDIA_ID/$pkg"
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        val folderExists = runCatching {
            context.contentResolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
                ?.use { it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR } == true
        }.getOrDefault(false)
        if (!folderExists && DocumentsContract.getTreeDocumentId(tree) != MEDIA_ID) {
            throw DirectoryAccessRequired()
        }

        resolvePrimaryStorageRoot(context)
    }

    fun directoryIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(AUTHORITY, MEDIA_ID)
        )
    }

    fun saveDirectoryGrant(context: Context, packageName: String, uri: Uri, flags: Int) {
        requireTree(uri, packageName)
        val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val access = flags and requiredFlags
        if (access != requiredFlags) {
            throw SeedStorageException("目录未授予读写权限，请重新选择。")
        }
        try {
            context.contentResolver.takePersistableUriPermission(uri, access)
        } catch (_: SecurityException) {
            throw SeedStorageException("系统未允许保存目录授权，请重新选择目录。")
        }
        MpayBridgeSession.prefs(context).edit()
            .putString("media_tree_$packageName", uri.toString())
            .apply()
    }

    /**
     * Detach a single game's SAF directory. Android grants belong to this helper app,
     * so release the underlying persisted permission only if no other game still
     * refers to the same tree. Never delete or enumerate any game files.
     */
    fun revokeDirectoryGrant(context: Context, packageName: String) {
        require(BridgeProtocol.isValidPackageName(packageName))
        val prefs = MpayBridgeSession.prefs(context)
        val key = "media_tree_$packageName"
        val stored = prefs.getString(key, null) ?: return
        val otherGameUsesSameTree = prefs.all.any { (otherKey, value) ->
            otherKey != key && otherKey.startsWith("media_tree_") && value == stored
        }
        if (!otherGameUsesSameTree) {
            val uri = runCatching { Uri.parse(stored) }.getOrNull()
            if (uri != null) {
                val persisted = context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri
                }
                if (persisted) {
                    try {
                        context.contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                        throw SeedStorageException("无法撤销系统目录权限，请稍后重试。")
                    }
                }
            }
        }
        // Remove this game's association even when a shared tree must stay granted
        // for another selected NetEase game.
        prefs.edit().remove(key).apply()
    }

    fun write(
        context: Context,
        target: GameTarget,
        name: String,
        bytes: ByteArray,
        mode: SeedTransportMode
    ): PreparedSeed {
        require(FILE_NAME.matches(name))
        if (mode == SeedTransportMode.STANDARD) {
            val cache = target.cacheUri(name)
            remember(context, cache, add = true)
            try {
                writeBytes(context, cache, bytes)
                return PreparedSeed(
                    sdkPath = target.localCacheFilePath(name),
                    cleanupUri = cache,
                    usedSaf = false
                )
            } catch (_: Exception) {
                delete(context, cache.toString())
                throw SeedStorageException(
                    "无法写入「${target.appLabel}」缓存接口；若该游戏未开放缓存接口（如阴阳师），请切换为「共享目录方式」后重试。"
                )
            }
        }

        val pkg = target.spec.packageName
        val saved = MpayBridgeSession.prefs(context).getString("media_tree_$pkg", null)
            ?: throw DirectoryAccessRequired()
        val tree = Uri.parse(saved)
        try {
            requireTree(tree, pkg)
            if (context.contentResolver.persistedUriPermissions.none {
                    it.uri == tree && it.isReadPermission && it.isWritePermission
                }
            ) {
                throw DirectoryAccessRequired()
            }
            return writeMedia(context, pkg, tree, name, bytes)
        } catch (e: DirectoryAccessRequired) {
            throw e
        } catch (_: SecurityException) {
            throw DirectoryAccessRequired()
        } catch (e: SeedStorageException) {
            throw e
        } catch (_: Exception) {
            throw SeedStorageException("无法写入已授权的游戏媒体目录，请检查目录是否仍存在且空间充足。")
        }
    }

    private fun writeMedia(
        context: Context,
        pkg: String,
        tree: Uri,
        name: String,
        bytes: ByteArray
    ): PreparedSeed {
        val resolver = context.contentResolver
        val folderId = "$MEDIA_ID/$pkg"
        var folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        val folderExists = runCatching {
            resolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
                ?.use { it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR } == true
        }.getOrDefault(false)

        if (!folderExists) {
            if (DocumentsContract.getTreeDocumentId(tree) != MEDIA_ID) {
                throw DirectoryAccessRequired()
            }
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, MEDIA_ID)
            val created = DocumentsContract.createDocument(
                resolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                pkg
            )
            if (created == null || DocumentsContract.getDocumentId(created) != folderId) {
                throw SeedStorageException("未能创建游戏媒体目录。")
            }
            folder = created
        }

        val file = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name)
            ?: throw SeedStorageException("未能创建临时准备文件。")
        if (DocumentsContract.getDocumentId(file) != "$folderId/$name") {
            // Only delete the URI just returned by our own createDocument call; never scan game files.
            runCatching { DocumentsContract.deleteDocument(resolver, file) }
            throw SeedStorageException("系统返回的文件名与预期路径不一致。")
        }

        remember(context, file, add = true)
        try {
            writeBytes(context, file, bytes)
            // Persisted SAF permission alone proves neither write nor read visibility.
            // Check the bytes through the same document URI before asking MPay to load them.
            verifyReadBack(context, file, bytes)
        } catch (e: Exception) {
            delete(context, file.toString())
            if (e is SeedStorageException) throw e
            throw SeedStorageException("共享目录临时文件写入或读回校验失败，请检查目录授权和存储状态。")
        }

        val primaryRoot = resolvePrimaryStorageRoot(context)
        return PreparedSeed(
            sdkPath = "$primaryRoot/Android/media/$pkg/$name",
            cleanupUri = file,
            usedSaf = true
        )
    }

    private fun resolvePrimaryStorageRoot(context: Context): String {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw SeedStorageException("主共享存储当前不可用。")
        }
        val volumeDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java)
                ?.primaryStorageVolume
                ?.directory
                ?.absolutePath
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val root = (volumeDir ?: Environment.getExternalStorageDirectory()?.absolutePath)
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: throw SeedStorageException("无法解析当前用户主存储路径。")
        return root
    }

    private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt").use { out ->
            if (out == null) throw IOException("stream-unavailable")
            out.write(bytes)
            out.flush()
        }
    }

    /** Verifies only this helper's transient seed, without logging or persisting its contents. */
    private fun verifyReadBack(context: Context, uri: Uri, expected: ByteArray) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw SeedStorageException("已创建临时文件，但无法读回；SDK 可能也无法读取。")
        input.use { stream ->
            for (byte in expected) {
                if (stream.read() != (byte.toInt() and 0xff)) {
                    throw SeedStorageException("临时文件读回内容不一致，已取消 SDK 加载请求。")
                }
            }
            if (stream.read() != -1) {
                throw SeedStorageException("临时文件读回长度不一致，已取消 SDK 加载请求。")
            }
        }
    }

    private fun requireTree(uri: Uri, pkg: String) {
        if (!BridgeProtocol.isValidPackageName(pkg) ||
            uri.scheme != "content" ||
            uri.authority != AUTHORITY ||
            !DocumentsContract.isTreeUri(uri)
        ) {
            throw SeedStorageException("请选择内部存储的 Android/media 目录或该游戏的子目录。")
        }
        val id = DocumentsContract.getTreeDocumentId(uri)
        if (id != MEDIA_ID && id != "$MEDIA_ID/$pkg") {
            throw SeedStorageException("请选择内部存储的 Android/media 目录或 $pkg 子目录。")
        }
    }

    fun cleanup(context: Context) {
        val p = MpayBridgeSession.prefs(context)
        val entries = p.getStringSet("temporary_uris", emptySet()).orEmpty().toSet()
        entries.forEach { delete(context, it) }
        // Clean up any legacy keys from older two-step versions.
        if (p.contains("uid") || p.contains("captured_at")) {
            p.edit()
                .remove("uid")
                .remove("captured_at")
                .remove("captured_package")
                .remove("captured_game_id")
                .apply()
        }
    }

    /** Accept only this helper's random temp names under the two supported transports. */
    private fun safeMediaUri(uri: Uri): Boolean = runCatching {
        if (uri.scheme != "content" || uri.authority != AUTHORITY || !DocumentsContract.isTreeUri(uri)) {
            return false
        }
        val id = DocumentsContract.getDocumentId(uri)
        val parts = id.removePrefix("$MEDIA_ID/").split('/')
        if (!id.startsWith("$MEDIA_ID/") ||
            parts.size != 2 ||
            !BridgeProtocol.isValidPackageName(parts[0]) ||
            !FILE_NAME.matches(parts[1])
        ) {
            return false
        }
        requireTree(uri, parts[0])
        true
    }.getOrDefault(false)

    fun delete(context: Context, value: String?) {
        if (value == null) return
        val uri = Uri.parse(value)
        val media = safeMediaUri(uri)
        if (!media && !CACHE_URI.matches(value)) return
        runCatching {
            if (media) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
            remember(context, uri, add = false)
        }
    }

    fun deleteAsync(context: Context, value: String?) {
        if (value == null) return
        val appCtx = context.applicationContext
        Thread({ delete(appCtx, value) }, "NeteaseSeedCleanup").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun remember(context: Context, uri: Uri, add: Boolean) {
        val p = MpayBridgeSession.prefs(context)
        val entries = p.getStringSet("temporary_uris", emptySet()).orEmpty().toMutableSet()
        if (add) entries.add(uri.toString()) else entries.remove(uri.toString())
        p.edit().putStringSet("temporary_uris", entries).apply()
    }
}
