package me.naptila.neteaseios

import android.content.pm.PackageManager
import android.os.Bundle
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.naptila.neteaseios.bridge.BridgeProtocol
import me.naptila.neteaseios.bridge.DirectoryAccessRequired
import me.naptila.neteaseios.bridge.GameSpec
import me.naptila.neteaseios.bridge.GameTarget
import me.naptila.neteaseios.bridge.InstalledGame
import me.naptila.neteaseios.bridge.MpayBridgeSession
import me.naptila.neteaseios.bridge.SeedStorage
import me.naptila.neteaseios.bridge.SeedStorageException
import me.naptila.neteaseios.bridge.SeedTransportMode
import me.naptila.neteaseios.ui.BridgeScreen
import me.naptila.neteaseios.ui.BridgeUiState
import me.naptila.neteaseios.ui.StatusTone
import me.naptila.neteaseios.ui.theme.NeteaseiosTheme

/** Generic NetEase MPay iOS account login helper; uses the target game's own login UI. */
class MainActivity : ComponentActivity() {

    private var uiState by mutableStateOf(BridgeUiState())
    private val history by lazy { PreparationHistory(MpayBridgeSession.prefs(this)) }
    private var activeAttempt: String? = null
    private var session: MpayBridgeSession? = null
    private var directorySpec: GameSpec? = null
    private var resumePrepareAfterGrant: Boolean = false

    private val directoryPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spec = directorySpec
            val shouldResumePrepare = resumePrepareAfterGrant
            directorySpec = null
            resumePrepareAfterGrant = false

            val uri = result.data?.data
            if (spec == null || result.resultCode != RESULT_OK || uri == null) {
                if (shouldResumePrepare) {
                    complete("未授权目录，本次准备已取消。", StatusTone.Warning, result = "cancelled")
                } else {
                    complete("已取消目录授权。", StatusTone.Neutral)
                }
            } else {
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SeedStorage.saveDirectoryGrant(
                                context = this@MainActivity,
                                packageName = spec.packageName,
                                uri = uri,
                                flags = result.data?.flags ?: 0
                            )
                        }
                        uiState = uiState.copy(busy = false)
                        refreshFromPrefs(updateStatusMessage = false)

                        if (shouldResumePrepare) {
                            if (uiState.currentGame?.spec?.packageName == spec.packageName) {
                                beginPrepare()
                            } else {
                                showMessage("已保存目录授权，请重新选择目标游戏。", StatusTone.Warning)
                            }
                        } else {
                            showMessage("「${spec.displayName}」目录已授权，可以开始准备。", StatusTone.Neutral)
                        }
                    } catch (e: Exception) {
                        val msg = if (e is SeedStorageException) {
                            e.message.orEmpty().ifBlank { "目录授权失败，请重试。" }
                        } else {
                            "目录授权失败，请重试。"
                        }
                        complete(msg, StatusTone.Warning)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        MpayBridgeSession.cleanup(this)
        history.migratePreviousResult()
        val savedGame = loadSavedGameSpec()?.let { GameTarget.inspect(this, it, loadIcon = true) }

        if (history.recentPackages().isEmpty()) savedGame?.let { history.rememberPackage(it.spec.packageName) }
        uiState = uiState.copy(currentGame = savedGame, recentPackages = history.recentPackages())
        savedInstanceState?.getString("directory_package")?.let { pkg ->
            if (BridgeProtocol.isValidPackageName(pkg)) {
                activeAttempt = savedInstanceState.getString("directory_attempt")
                directorySpec = GameSpec(pkg, savedInstanceState.getString("directory_name") ?: pkg)
                resumePrepareAfterGrant = savedInstanceState.getBoolean("directory_resume_prepare", false)
                uiState = uiState.copy(busy = true)
            }
        }
        refreshFromPrefs(updateStatusMessage = true)
        loadInstalledAppsAsync()

        setContent {
            NeteaseiosTheme {
                BridgeScreen(
                    state = uiState,
                    onOpenGame = ::openTargetGame,
                    onSelectInstalledGame = ::selectInstalledGame,
                    onRefreshApps = ::loadInstalledAppsAsync,
                    onSelectTransportMode = ::selectTransportMode,
                    onApplyNonEmptyToken = ::applyNonEmptyTokenAndPrepare,
                    onAuthorizeSafDirectory = ::authorizeCurrentGameDirectory,
                    onPrepareIosLogin = ::beginPrepare
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null && !uiState.busy) {
            MpayBridgeSession.cleanup(this)
        }
        val current = uiState.currentGame
        if (current != null) {
            uiState = uiState.copy(
                currentGame = GameTarget.inspect(this, current.spec, loadIcon = true)
            )
        }
        refreshFromPrefs(updateStatusMessage = !uiState.busy)
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        directorySpec?.let {
            outState.putString("directory_attempt", activeAttempt)
            outState.putString("directory_package", it.packageName)
            outState.putString("directory_name", it.displayName)
            outState.putBoolean("directory_resume_prepare", resumePrepareAfterGrant)
        }
        super.onSaveInstanceState(outState)
    }

    private fun loadInstalledAppsAsync() {
        if (uiState.loadingApps) return
        uiState = uiState.copy(loadingApps = true)
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                GameTarget.listInstalledLauncherApps(this@MainActivity)
            }
            uiState = uiState.copy(
                installedApps = apps,
                confirmedPackages = history.confirmedPackages(apps),
                loadingApps = false
            )
        }
    }

    private fun selectInstalledGame(game: InstalledGame) {
        if (uiState.busy) return
        val withIcon = GameTarget.inspect(this, game.spec, loadIcon = true)
        history.rememberPackage(withIcon.spec.packageName)
        persistSelectedSpec(withIcon.spec)
        uiState = uiState.copy(currentGame = withIcon, recentPackages = history.recentPackages())
        refreshFromPrefs(updateStatusMessage = true)
    }

    private fun selectTransportMode(mode: SeedTransportMode) {
        if (uiState.busy) return
        val pkg = uiState.currentGame?.spec?.packageName
        if (pkg != null) {
            SeedStorage.setTransportMode(this, pkg, mode)
            val granted = SeedStorage.hasValidDirectoryGrant(this, pkg)
            uiState = uiState.copy(transportMode = mode, safAuthorized = granted)
        } else {
            uiState = uiState.copy(transportMode = mode)
        }
    }

    /**
     * The compatibility preference is committed only on explicit confirmation.
     * Re-run the bridge after saving: prior game login sessions cannot be retroactively changed.
     * SAF grants are still checked by the existing beginPrepare flow.
     */
    private fun applyNonEmptyTokenAndPrepare(enabled: Boolean) {
        if (uiState.busy) return
        val game = uiState.currentGame ?: run {
            showMessage("请先选择目标网易游戏。", StatusTone.Warning)
            return
        }
        if (!game.installed) {
            showMessage("目标游戏未安装，无法应用兼容性设置。", StatusTone.Warning)
            return
        }
        SeedStorage.setNonEmptyTokenEnabled(this, game.spec.packageName, enabled)
        uiState = uiState.copy(nonEmptyToken = enabled)
        beginPrepare()
    }

    private fun authorizeCurrentGameDirectory() {
        if (uiState.busy) return
        val spec = uiState.currentGame?.spec ?: run {
            showMessage("请先选择目标游戏。", StatusTone.Warning)
            return
        }
        launchDirectoryPicker(spec, resumePrepare = false)
    }

    private fun launchDirectoryPicker(spec: GameSpec, resumePrepare: Boolean) {
        if (isFinishing || isDestroyed) return
        session?.close()
        session = null
        directorySpec = spec
        resumePrepareAfterGrant = resumePrepare
        uiState = uiState.copy(busy = true)
        showMessage("请选择内部存储 Android/media（或 ${spec.packageName}）目录。", StatusTone.Warning)
        try {
            directoryPicker.launch(SeedStorage.directoryIntent())
        } catch (_: Exception) {
            directorySpec = null
            resumePrepareAfterGrant = false
            complete("系统目录选择器不可用。", StatusTone.Error)
        }
    }

    private fun loadSavedGameSpec(): GameSpec? {
        val p = MpayBridgeSession.prefs(this)
        val pkg = p.getString("selected_package", null)
            ?.takeIf { BridgeProtocol.isValidPackageName(it) }
            ?: return null
        val name = p.getString("selected_name", pkg) ?: pkg
        return GameSpec(
            packageName = pkg,
            displayName = name
        )
    }

    private fun persistSelectedSpec(spec: GameSpec) {
        MpayBridgeSession.prefs(this).edit()
            .putString("selected_package", spec.packageName)
            .putString("selected_name", spec.displayName)
            .apply()
    }

    private fun openTargetGame() {
        val spec = uiState.currentGame?.spec ?: run {
            showMessage("请先选择目标游戏。", StatusTone.Warning)
            return
        }
        try {
            if (!GameTarget.open(this, spec.packageName)) {
                showMessage("无法打开「${spec.displayName}」。", StatusTone.Error)
            }
        } catch (_: Exception) {
            showMessage("无法打开「${spec.displayName}」，请手动启动。", StatusTone.Warning)
        }
    }

    private fun beginPrepare() {
        if (uiState.busy) return
        val game = uiState.currentGame ?: return
        val spec = game.spec
        val attempt = UUID.randomUUID().toString()
        activeAttempt = attempt
        history.begin(game, attempt)
        val selectedMode = uiState.transportMode
        val useNonEmptyToken = uiState.nonEmptyToken
        uiState = uiState.copy(
            busy = true,
            submittedCurrent = false,
            unconfirmedCurrent = false,
            statusMessage = "正在连接「${spec.displayName}」准备 iOS 登录…",
            statusTone = StatusTone.Neutral
        )

        Thread({
            try {
                val target = GameTarget.verify(this, spec)
                val next = MpayBridgeSession(
                    context = this,
                    target = target,
                    transportMode = selectedMode,
                    nonEmptyToken = useNonEmptyToken,
                    listener = object : MpayBridgeSession.Listener {
                        override fun onSubmitted(gameId: String, confirmed: Boolean, usedSaf: Boolean) {
                            if (!ownsAttempt(attempt)) return
                            val text = if (confirmed) {
                                "登录组件已确认接收。请回「${target.appLabel}」用同一账号重登，核对区服和角色。"
                            } else {
                                "请求已发送（无独立回调），回「${target.appLabel}」点「切换账号」重登核对即可。"
                            }
                            complete(
                                result = if (confirmed) "submitted" else "unconfirmed",
                                text = text,
                                tone = if (confirmed) StatusTone.Success else StatusTone.Warning
                            )
                        }

                        override fun onFailed(message: String) {
                            if (ownsAttempt(attempt)) complete(text = message, tone = StatusTone.Error)
                        }
                    }
                )
                runOnUiThread {
                    if (!ownsAttempt(attempt)) {
                        next.close()
                        return@runOnUiThread
                    }
                    session?.close()
                    session = next
                    refreshFromPrefs(updateStatusMessage = false)
                    next.start()
                }
            } catch (_: PackageManager.NameNotFoundException) {
                runOnUiThread {
                    if (ownsAttempt(attempt)) complete("未找到已安装的「${spec.displayName}」。", StatusTone.Error)
                }
            } catch (_: DirectoryAccessRequired) {
                runOnUiThread {
                    if (ownsAttempt(attempt)) launchDirectoryPicker(spec, resumePrepare = true)
                }
            } catch (e: SeedStorageException) {
                runOnUiThread {
                    if (ownsAttempt(attempt)) complete(e.message ?: "准备文件通道不可用。", StatusTone.Error)
                }
            } catch (_: IllegalStateException) {
                runOnUiThread {
                    if (ownsAttempt(attempt)) complete("「${spec.displayName}」不支持此登录方式。", StatusTone.Warning)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (ownsAttempt(attempt)) complete("准备失败，请检查目录授权或剩余空间后重试。", StatusTone.Error)
                }
            }
        }, "NeteaseBridgeSetup").apply {
            isDaemon = true
            start()
        }
    }

    private fun ownsAttempt(attempt: String) =
        !isFinishing && !isDestroyed && activeAttempt == attempt

    private fun complete(text: String, tone: StatusTone, result: String = "failed") {
        val attempt = activeAttempt
        val pkg = uiState.currentGame?.spec?.packageName
        if (attempt != null && pkg != null) {
            val error = if (result == "failed" || result == "cancelled") text else null
            if (!history.finish(pkg, attempt, result, error)) {
                activeAttempt = null
                session?.close()
                session = null
                uiState = uiState.copy(busy = false)
                refreshFromPrefs(updateStatusMessage = true)
                return
            }
        }
        activeAttempt = null
        session?.close()
        session = null
        uiState = uiState.copy(busy = false)
        refreshFromPrefs(updateStatusMessage = false)
        showMessage(text, tone)
    }

    private fun showMessage(text: String, tone: StatusTone) {
        uiState = uiState.copy(
            statusMessage = text,
            statusTone = tone
        )
    }

    private fun refreshFromPrefs(updateStatusMessage: Boolean) {
        val current = uiState.currentGame
        if (current == null) {
            uiState = uiState.copy(
                detectedGameId = null,
                submittedCurrent = false,
                unconfirmedCurrent = false,
                safAuthorized = false,
                statusMessage = if (updateStatusMessage && !uiState.busy) "请先选择目标网易游戏。" else uiState.statusMessage,
                statusTone = if (updateStatusMessage && !uiState.busy) StatusTone.Neutral else uiState.statusTone
            )
            return
        }

        val pkg = current.spec.packageName
        val gameName = current.spec.displayName
        val p = MpayBridgeSession.prefs(this)
        val detectedGid = p.getString("last_game_id_$pkg", null)
        val state = history.result(pkg)
        val sameBuild = history.sameBuild(current)
        val submittedCurrent = sameBuild && state == "submitted"
        val unconfirmedCurrent = sameBuild && state == "unconfirmed"
        val mode = SeedStorage.getTransportMode(this, pkg)
        val nonEmptyToken = SeedStorage.isNonEmptyTokenEnabled(this, pkg)
        val safAuthorized = SeedStorage.hasValidDirectoryGrant(this, pkg)

        var message = uiState.statusMessage
        var tone = uiState.statusTone

        if (updateStatusMessage && !uiState.busy) {
            when {
                !current.installed -> {
                    message = "「$gameName」已卸载，请重新选择游戏。"
                    tone = StatusTone.Warning
                }

                state != null && !sameBuild -> {
                    val last = when (state) {
                        "submitted" -> "组件已确认接收"
                        "unconfirmed" -> "已发送，待核对"
                        else -> "未完成"
                    }
                    message = "上次准备结果：$last。当前安装版本待确认。"
                    tone = StatusTone.Neutral
                }

                submittedCurrent -> {
                    message = "上次准备结果：登录组件已确认接收。回「$gameName」用同一账号重登并核对角色。"
                    tone = StatusTone.Success
                }

                unconfirmedCurrent -> {
                    message = "上次请求已发送，未收到明确回调。请回「$gameName」重登并核对角色。"
                    tone = StatusTone.Warning
                }

                state == "failed" || state == "cancelled" -> {
                    message = history.error(pkg) ?: "操作未完成，请重试。"
                    tone = if (state == "failed") StatusTone.Error else StatusTone.Warning
                }

                state == "preparing" -> {
                    message = "操作未完成；若已离开网页，请关闭网页后重试。"
                    tone = StatusTone.Warning
                }

                else -> {
                    message = "先在「$gameName」登录账号，再点下方按钮一键准备。"
                    tone = StatusTone.Neutral
                }
            }
        }

        uiState = uiState.copy(
            detectedGameId = detectedGid,
            confirmedPackages = history.confirmedPackages(uiState.installedApps),
            submittedCurrent = submittedCurrent,
            unconfirmedCurrent = unconfirmedCurrent,
            transportMode = mode,
            nonEmptyToken = nonEmptyToken,
            safAuthorized = safAuthorized,
            statusMessage = message,
            statusTone = tone
        )
    }
}
