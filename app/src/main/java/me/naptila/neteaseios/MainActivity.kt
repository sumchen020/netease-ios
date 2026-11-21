package me.naptila.neteaseios

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
    private var gameReadyOverlay: GameReadyOverlay? = null
    private var pendingOverlayPermissionPackage: String? = null
    private var launchGameAfterOverlaySettings: GameSpec? = null

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val expected = pendingOverlayPermissionPackage
            pendingOverlayPermissionPackage = null
            val game = uiState.currentGame
            if (expected != null && game?.spec?.packageName == expected && !uiState.busy) {
                // Activity-result delivery can precede onResume. Wait until the
                // helper is resumed before opening the game and showing overlay.
                launchGameAfterOverlaySettings = game.spec
            }
        }

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
                    onApplyCompatibility = ::applyCompatibilityAndPrepare,
                    onAuthorizeSafDirectory = ::authorizeCurrentGameDirectory,
                    onRevokeSafDirectory = ::revokeCurrentGameDirectory,
                    onPrepareIosLogin = ::requestPrepareIosLogin,
                    onGameReadyContinue = ::confirmGameReadyAndPrepare,
                    onGameReadyCancel = ::cancelGameReadyPrompt,
                    onOverlayPermissionRequest = ::requestOverlayPermission,
                    onOverlayPermissionSkip = ::skipOverlayPermission,
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

        // If the user switched back to the helper instead of using the game
        // overlay, remove it and fall back to the in-app confirmation dialog.
        if (gameReadyOverlay != null) {
            gameReadyOverlay?.dismiss()
            gameReadyOverlay = null
        }

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
        launchGameAfterOverlaySettings?.let { next ->
            launchGameAfterOverlaySettings = null
            window.decorView.post {
                if (!isFinishing && !isDestroyed &&
                    uiState.currentGame?.spec?.packageName == next.packageName && !uiState.busy) {
                    launchGameForVisit(next, forPrepare = true)
                }
            }
        }
    }

    override fun onDestroy() {
        gameReadyOverlay?.dismiss()
        gameReadyOverlay = null
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
        gameReadyOverlay?.dismiss()
        gameReadyOverlay = null
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
     * Apply both independent compatibility preferences on explicit confirmation.
     * Only newly generated seed files can use the new settings; never mutate
     * or delete MPay's existing account-switcher records.
     */
    private fun applyCompatibilityAndPrepare(nonEmptyToken: Boolean, rememberAccount: Boolean) {
        if (uiState.busy) return
        val game = uiState.currentGame ?: run {
            showMessage("请先选择目标网易游戏。", StatusTone.Warning)
            return
        }
        if (!game.installed) {
            showMessage("目标游戏未安装，无法应用兼容性设置。", StatusTone.Warning)
            return
        }
        if (uiState.nonEmptyToken == nonEmptyToken &&
            uiState.rememberAccount == rememberAccount) return

        val pkg = game.spec.packageName
        SeedStorage.setNonEmptyTokenEnabled(this, pkg, nonEmptyToken)
        SeedStorage.setRememberAccountEnabled(this, pkg, rememberAccount)
        uiState = uiState.copy(
            nonEmptyToken = nonEmptyToken,
            rememberAccount = rememberAccount
        )
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
        // Always bring the selected game to the foreground for user confirmation.
        // Launcher intent may resume an existing game; it is not a process-status API.
        if (!Settings.canDrawOverlays(this) &&
            !MpayBridgeSession.prefs(this).getBoolean("game_overlay_prompted", false)) {
            uiState = uiState.copy(overlayPermissionExplanation = true)
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
            return
        }
        if (forPrepare) {
            BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.GAME_LAUNCH_SENT)
        }
        if (forPrepare && Settings.canDrawOverlays(this)) {
            val overlay = GameReadyOverlay(
                activity = this,
                onConfirm = { confirmFromGameOverlay(spec.packageName) },
                onCancel = { cancelFromGameOverlay() }
            )
            if (overlay.show()) {
                gameReadyOverlay = overlay
                BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.OVERLAY_DISPLAYED)
            } else {
                BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.OVERLAY_FALLBACK)
            }
            // On a device that suppresses overlays for this game, the existing
            // return-to-assistant dialog stays available as a fallback.
        } else if (forPrepare) {
            BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.OVERLAY_FALLBACK)
        }
    }

    private fun confirmFromGameOverlay(packageName: String) {
        if (uiState.busy || uiState.currentGame?.spec?.packageName != packageName) return
        // The overlay keeps itself visible briefly while the game-webview bridge
        // launches, then removes itself automatically.
        gameReadyOverlay = null
        pendingGameVisitPackage = null
        pendingGameVisitForPrepare = false
        pendingGameVisitPaused = false
        lastReturnedGamePackage = packageName
        lastReturnedGameTimeMs = SystemClock.elapsedRealtime()
        BridgeDiagnosticLog.record(this, packageName, BridgeLogEvent.OVERLAY_CONFIRMED)
        beginPrepare()
    }

    private fun cancelFromGameOverlay() {
        gameReadyOverlay = null
        pendingGameVisitPackage = null
        pendingGameVisitForPrepare = false
        pendingGameVisitPaused = false
        uiState = uiState.copy(gameReadyConfirmation = false)
        showMessage("已取消准备。", StatusTone.Neutral)
    }

    private fun requestOverlayPermission() {
        val game = uiState.currentGame ?: return
        uiState = uiState.copy(overlayPermissionExplanation = false)
        MpayBridgeSession.prefs(this).edit().putBoolean("game_overlay_prompted", true).apply()
        if (Settings.canDrawOverlays(this)) {
            launchGameForVisit(game.spec, forPrepare = true)
            return
        }
        pendingOverlayPermissionPackage = game.spec.packageName
        try {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            pendingOverlayPermissionPackage = null
            launchGameForVisit(game.spec, forPrepare = true)
        }
    }

    private fun skipOverlayPermission() {
        uiState = uiState.copy(overlayPermissionExplanation = false)
        MpayBridgeSession.prefs(this).edit().putBoolean("game_overlay_prompted", true).apply()
        val spec = uiState.currentGame?.spec ?: return
        launchGameForVisit(spec, forPrepare = true)
    }

    private fun confirmGameReadyAndPrepare() {
        if (!uiState.gameReadyConfirmation || uiState.busy) return
        val spec = uiState.currentGame?.spec ?: return
        uiState = uiState.copy(gameReadyConfirmation = false)
        BridgeDiagnosticLog.record(this, spec.packageName, BridgeLogEvent.ASSISTANT_CONFIRMED)
        // Prepare the local bridge while the assistant is still foreground.
        // The previous implementation posted the entire preparation task to
        // decorView *after* opening the game; Android could defer that view
        // callback for tens of seconds once the assistant was backgrounded.
        // Only after the bridge is ready do we resume the game, then start
        // the same native game WebView bridge used by the overlay path.
        Toast.makeText(this, "已确认，正在切回游戏并启动桥接…", Toast.LENGTH_LONG).show()
        beginPrepare(reopenGameBeforeBridge = true)
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

    private fun beginPrepare(reopenGameBeforeBridge: Boolean = false) {
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
        BridgeDiagnosticLog.record(
            this, spec.packageName,
            if (uiState.rememberAccount) BridgeLogEvent.LEGACY_REMEMBER_ON
            else BridgeLogEvent.LEGACY_REMEMBER_OFF
        )
        val attempt = UUID.randomUUID().toString()
        activeAttempt = attempt
        history.begin(game, attempt)
        val selectedMode = uiState.transportMode
        val useNonEmptyToken = uiState.nonEmptyToken
        val useRememberAccount = uiState.rememberAccount
        uiState = uiState.copy(
            busy = true,
            submittedCurrent = false,
            unconfirmedCurrent = false,
            statusMessage = if (reopenGameBeforeBridge) {
                "正在准备桥接并切回「${spec.displayName}」…"
            } else {
                "正在检测「${spec.displayName}」游戏账号…"
            },
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
                    rememberAccount = useRememberAccount,
                    listener = object : MpayBridgeSession.Listener {
                        override fun onSubmitted(gameId: String, confirmed: Boolean, usedSaf: Boolean, callbackReason: String) {
                            if (!ownsAttempt(attempt)) return
                            val localWrite = if (usedSaf) {
                                "共享目录文件已写入且读回校验通过。"
                            } else {
                                "缓存临时文件已写入，未单独验证游戏端可读取。"
                            }
                            val modeNote = if (useNonEmptyToken) "本次使用兼容模式。" else ""
                            val status = when {
                                confirmed -> "游戏登录组件已接收。"
                                callbackReason == "manual_return" ->
                                    "在 SDK 返回结果前关闭了桥接页，无法确认游戏端是否接受。"
                                callbackReason == "unrecognized_callback" ->
                                    "收到未知格式的 SDK 回调，无法确认游戏端是否接受。"
                                callbackReason == "host_timeout" ->
                                    "桥接会话超时，但文件已写入且 SDK 加载请求已发出；结果仍待游戏内核对。"
                                else ->
                                    "等待 30 秒未收到可确认的 SDK 回调，不能判断最终登录平台。"
                            }
                            val outcome = if (confirmed) status else "请求已发送。$status"
                            val text = "${outcome}请返回游戏重新登录并核对区服和角色。$modeNote$localWrite"
                            complete(
                                result = if (confirmed) "submitted" else "unconfirmed",
                                text = text,
                                tone = if (confirmed) StatusTone.Success else StatusTone.Neutral
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
                    if (reopenGameBeforeBridge) {
                        val opened = runCatching {
                            GameTarget.open(this, spec.packageName)
                        }.getOrDefault(false)
                        if (!opened) {
                            next.close()
                            complete(
                                "无法重新打开「${spec.displayName}」，本次未启动桥接。",
                                StatusTone.Error
                            )
                            return@runOnUiThread
                        }
                        BridgeDiagnosticLog.record(
                            this, spec.packageName, BridgeLogEvent.GAME_REOPENED_BEFORE_BRIDGE
                        )
                    }
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
                nonEmptyToken = false,
                rememberAccount = false,
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
        val rememberAccount = SeedStorage.isRememberAccountEnabled(this, pkg)
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
                    message = "当前游戏安装版本待确认。上次结果仅供参考（$last）。"
                    tone = StatusTone.Neutral
                }

                submittedCurrent -> {
                    message = history.diagnostic(pkg)
                        ?: "上次准备时，游戏登录组件已接收。请在游戏内核对区服和角色。"
                    tone = StatusTone.Success
                }

                unconfirmedCurrent -> {
                    message = history.diagnostic(pkg)
                        ?: "上次请求已发送，待游戏内核对。请回「$gameName」核对区服和角色。"
                    tone = StatusTone.Neutral
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
            rememberAccount = rememberAccount,
            safAuthorized = safAuthorized,
            diagnosticEvents = BridgeDiagnosticLog.entries(this, pkg),
            statusMessage = message,
            statusTone = tone
        )
    }
}
