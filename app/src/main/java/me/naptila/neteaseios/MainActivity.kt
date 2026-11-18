package me.naptila.neteaseios

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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
import me.naptila.neteaseios.bridge.BridgeDiagnosticLog
import me.naptila.neteaseios.bridge.BridgeLogEvent
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

    // Launch-and-return signal, not privileged cross-app process monitoring.
    // Returning from a game is only a hint; the MPay gameContextInfo check
    // independently gates every write.
    private var pendingGameVisitPackage: String? = null
    private var pendingGameVisitForPrepare: Boolean = false
    private var pendingGameVisitPaused: Boolean = false
    private var lastReturnedGamePackage: String? = null
    private var lastReturnedGameTimeMs: Long = 0L

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
                                requestPrepareIosLogin()
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
        savedInstanceState?.getString("pending_game_visit_package")
            ?.takeIf(BridgeProtocol::isValidPackageName)?.let {
                pendingGameVisitPackage = it
                pendingGameVisitForPrepare =
                    savedInstanceState?.getBoolean("pending_game_visit_for_prepare", false) == true
                pendingGameVisitPaused =
                    savedInstanceState?.getBoolean("pending_game_visit_paused", false) == true
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
                    onRevokeSafDirectory = ::revokeCurrentGameDirectory,
                    onPrepareIosLogin = ::requestPrepareIosLogin,
                    onGameReadyContinue = ::confirmGameReadyAndPrepare,
                    onGameReadyCancel = ::cancelGameReadyPrompt,
                    onRefreshDiagnosticLog = ::refreshDiagnosticLog,
                    onClearDiagnosticLog = ::clearDiagnosticLog
                )
            }
        }
    }

    override fun onPause() {
        if (pendingGameVisitPackage != null) {
            pendingGameVisitPaused = true
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Do not purge pending seed files on every return to the assistant.
        // A callback-less MPay import may still be reading asynchronously;
        // the bridge schedules bounded cleanup and onCreate handles leftovers.
        val current = uiState.currentGame
        if (current != null) {
            uiState = uiState.copy(
                currentGame = GameTarget.inspect(this, current.spec, loadIcon = true)
            )
        }
        refreshFromPrefs(updateStatusMessage = !uiState.busy)

        val returningPackage = pendingGameVisitPackage
        if (returningPackage != null && pendingGameVisitPaused) {
            val shouldPrepare = pendingGameVisitForPrepare
            pendingGameVisitPackage = null
            pendingGameVisitPaused = false
            pendingGameVisitForPrepare = false
            if (uiState.currentGame?.spec?.packageName == returningPackage) {
                lastReturnedGamePackage = returningPackage
                lastReturnedGameTimeMs = SystemClock.elapsedRealtime()
                if (shouldPrepare) {
                    uiState = uiState.copy(gameReadyConfirmation = true)
                    showMessage("请确认已进入游戏，再继续检测账号。", StatusTone.Neutral)
                }
            }
        }
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
        pendingGameVisitPackage?.let {
            outState.putString("pending_game_visit_package", it)
            outState.putBoolean("pending_game_visit_for_prepare", pendingGameVisitForPrepare)
            outState.putBoolean("pending_game_visit_paused", pendingGameVisitPaused)
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
        pendingGameVisitPackage = null
        pendingGameVisitPaused = false
        pendingGameVisitForPrepare = false
        lastReturnedGamePackage = null
        val withIcon = GameTarget.inspect(this, game.spec, loadIcon = true)
        history.rememberPackage(withIcon.spec.packageName)
        persistSelectedSpec(withIcon.spec)
        uiState = uiState.copy(
            currentGame = withIcon,
            recentPackages = history.recentPackages(),
            gameReadyConfirmation = false
        )
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
        // Avoid opening the game or rewriting a preference when nothing changed.
        if (uiState.nonEmptyToken == enabled) return
        SeedStorage.setNonEmptyTokenEnabled(this, game.spec.packageName, enabled)
        uiState = uiState.copy(nonEmptyToken = enabled)
        requestPrepareIosLogin()
    }

    private fun authorizeCurrentGameDirectory() {
        if (uiState.busy) return
        val spec = uiState.currentGame?.spec ?: run {
            showMessage("请先选择目标游戏。", StatusTone.Warning)
            return
        }
        launchDirectoryPicker(spec, resumePrepare = false)
    }

    private fun revokeCurrentGameDirectory() {
        if (uiState.busy) return
        val spec = uiState.currentGame?.spec ?: return
        if (uiState.transportMode != SeedTransportMode.SAF_MEDIA) return

        try {
            SeedStorage.revokeDirectoryGrant(this, spec.packageName)
            refreshFromPrefs(updateStatusMessage = false)
            showMessage("已取消「${spec.displayName}」的目录授权，需要使用共享目录时请重新授权。", StatusTone.Neutral)
        } catch (e: SeedStorageException) {
            showMessage(e.message ?: "取消目录授权失败，请重试。", StatusTone.Error)
        } catch (_: Exception) {
            showMessage("取消目录授权失败，请重试。", StatusTone.Error)
        }
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
        val game = uiState.currentGame ?: return
        launchGameForVisit(game.spec, forPrepare = false)
    }

    /**
     * Normal apps cannot determine whether a different game is still running
     * or directly observe its "欢迎进入游戏" banner. We reuse a recent, witnessed
     * return from a game opened by this helper; otherwise open it and let the
     * user confirm the welcome/home screen on return.
     */
    private fun requestPrepareIosLogin() {
        if (uiState.busy || uiState.gameReadyConfirmation) return
        val game = uiState.currentGame ?: run {
            showMessage("请先选择目标游戏。", StatusTone.Warning)
            return
        }
        if (!game.installed) {
            showMessage("游戏未安装，请先安装或重新选择。", StatusTone.Warning)
            return
        }
        if (uiState.transportMode == SeedTransportMode.SAF_MEDIA && !uiState.safAuthorized) {
            showMessage("请先授权 Android/media 目录。", StatusTone.Warning)
            return
        }
        val recentlyReturned = lastReturnedGamePackage == game.spec.packageName &&
            SystemClock.elapsedRealtime() - lastReturnedGameTimeMs in 0L..RECENT_GAME_RETURN_MS
        if (recentlyReturned) {
            // Recent activity is not proof of login. The bridge still requires
            // an independent, fresh gameContextInfo response before seed writing.
            beginPrepare()
        } else {
            launchGameForVisit(game.spec, forPrepare = true)
        }
    }

    private fun launchGameForVisit(spec: GameSpec, forPrepare: Boolean) {
        if (uiState.busy) return
        pendingGameVisitPackage = spec.packageName
        pendingGameVisitForPrepare = forPrepare
        pendingGameVisitPaused = false
        uiState = uiState.copy(gameReadyConfirmation = false)
        if (forPrepare) {
            showMessage("进入游戏看到欢迎提示后，返回助手继续。", StatusTone.Neutral)
        }
        val opened = runCatching {
            GameTarget.open(this, spec.packageName)
        }.getOrDefault(false)
        if (!opened) {
            pendingGameVisitPackage = null
            pendingGameVisitForPrepare = false
            showMessage("无法打开「${spec.displayName}」，请手动启动游戏。", StatusTone.Warning)
        }
    }

    private fun confirmGameReadyAndPrepare() {
        if (!uiState.gameReadyConfirmation || uiState.busy) return
        uiState = uiState.copy(gameReadyConfirmation = false)
        beginPrepare()
    }

    private fun cancelGameReadyPrompt() {
        uiState = uiState.copy(gameReadyConfirmation = false)
        lastReturnedGamePackage = null
        lastReturnedGameTimeMs = 0L
        showMessage("已取消准备，可重新检测并准备。", StatusTone.Neutral)
    }

    private companion object {
        // A soft workflow convenience hint, never a proof that a game is running.
        const val RECENT_GAME_RETURN_MS = 3 * 60 * 1000L
    }

    private fun beginPrepare() {
        if (uiState.busy) return
        val game = uiState.currentGame ?: return
        val spec = game.spec
        BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.PREPARE_TAPPED)
        BridgeDiagnosticLog.record(
            this, spec.packageName,
            if (uiState.transportMode == SeedTransportMode.SAF_MEDIA)
                BridgeLogEvent.TRANSPORT_SAF else BridgeLogEvent.TRANSPORT_CACHE
        )
        BridgeDiagnosticLog.record(
            this, spec.packageName,
            if (uiState.nonEmptyToken) BridgeLogEvent.PLACEHOLDER_ON else BridgeLogEvent.PLACEHOLDER_OFF
        )
        val attempt = UUID.randomUUID().toString()
        activeAttempt = attempt
        history.begin(game, attempt)
        val selectedMode = uiState.transportMode
        val useNonEmptyToken = uiState.nonEmptyToken
        uiState = uiState.copy(
            busy = true,
            submittedCurrent = false,
            unconfirmedCurrent = false,
            statusMessage = "正在检测「${spec.displayName}」游戏账号…",
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
                        override fun onSubmitted(gameId: String, confirmed: Boolean, usedSaf: Boolean, callbackReason: String) {
                            if (!ownsAttempt(attempt)) return
                            val localWrite = if (usedSaf) {
                                "共享目录文件已写入且读回校验通过。"
                            } else {
                                "缓存临时文件已写入，未单独验证游戏端可读取。"
                            }
                            val status = when {
                                confirmed -> "游戏 SDK 返回成功回调，但服务器最终角色仍需在游戏内核对。"
                                callbackReason == "manual_return" ->
                                    "你在 SDK 返回结果之前离开桥接页，无法判断 SDK 是否接受。"
                                callbackReason == "unrecognized_callback" ->
                                    "收到了 SDK 回调，但格式不符合当前解析规则，结果未知，需要适配回调格式。"
                                else ->
                                    "等待 30 秒仍未收到 SDK 回调，结果未知；不能判定 iOS 登录成功。"
                            }
                            val text = "$localWrite$status 请回「${target.appLabel}」使用实际显示的登录入口核对区服与角色。"
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
                BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.STORAGE_ERROR)
                runOnUiThread {
                    if (ownsAttempt(attempt)) launchDirectoryPicker(spec, resumePrepare = true)
                }
            } catch (e: SeedStorageException) {
                BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.STORAGE_ERROR)
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
            if (!history.finish(pkg, attempt, result, error, text)) {
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

    private fun refreshDiagnosticLog() {
        val pkg = uiState.currentGame?.spec?.packageName
        uiState = uiState.copy(
            diagnosticEvents = if (pkg == null) emptyList() else BridgeDiagnosticLog.entries(this, pkg)
        )
    }

    private fun clearDiagnosticLog() {
        val pkg = uiState.currentGame?.spec?.packageName ?: return
        BridgeDiagnosticLog.clear(this, pkg)
        refreshDiagnosticLog()
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
                diagnosticEvents = emptyList(),
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
                    message = history.diagnostic(pkg)
                        ?: "上次 SDK 返回成功回调；仍需在游戏内核对实际区服和角色。"
                    tone = StatusTone.Success
                }

                unconfirmedCurrent -> {
                    message = history.diagnostic(pkg)
                        ?: "上次请求已发送，SDK 未确认是否接受。请回「$gameName」重新登录并核对角色。"
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
                    message = "先在「$gameName」登录，再点击「检测并准备」。"
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
            diagnosticEvents = BridgeDiagnosticLog.entries(this, pkg),
            statusMessage = message,
            statusTone = tone
        )
    }
}
