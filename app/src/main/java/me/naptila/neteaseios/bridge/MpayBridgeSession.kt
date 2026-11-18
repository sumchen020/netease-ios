package me.naptila.neteaseios.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-session loopback MPay iOS login bridge for NetEase UniSDK/GMBridge5 games.
 * Reads the currently logged-in UID & game_id via gameContextInfo and immediately loads
 * an iOS session seed (default empty token, or optional non-empty placeholder) in one pass.
 * Never receives passwords, OTPs or active login tokens.
 */
class MpayBridgeSession(
    context: Context,
    private val target: GameTarget,
    private val transportMode: SeedTransportMode = SeedTransportMode.STANDARD,
    private val nonEmptyToken: Boolean = false,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onSubmitted(gameId: String, confirmed: Boolean, usedSaf: Boolean, callbackReason: String)
        fun onFailed(message: String)
    }

    private data class ContextIdentity(
        val uid: String?,
        val gameId: String
    )

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val resultDelivered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val server: ServerSocket
    private val nonce: String
    private val base: String
    private val host: String
    private val expectedGameId: String? = target.spec.gameId
    @Volatile
    private var capturedGameId: String = ""
    @Volatile
    private var verifiedContext: ContextIdentity? = null
    @Volatile
    private var preparedSeed: PreparedSeed? = null
    @Volatile
    private var unconfirmedCleanupDeferred = false

    init {
        SeedStorage.ensureReady(appContext, target, transportMode)

        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        nonce = random.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        base = "/bridge/$nonce"

        server = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            soTimeout = 1000
        }
        host = "127.0.0.1:${server.localPort}"
    }

    fun start() {
        BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.SESSION_STARTED)
        prefs(appContext).edit()
            .putString("state", "preparing")
            .putString("state_package", target.spec.packageName)
            .remove("last_error")
            .putString("operation", nonce)
            .remove("completed_operation")
            .apply()

        Thread(::serve, "NeteaseLocalBridge").apply {
            isDaemon = true
            start()
        }

        main.postDelayed({
            if (closed.get()) return@postDelayed
            if (!resultDelivered.get()) {
                fail("未能完成 iOS 登录准备，请先确保已在「${target.appLabel}」正常登录账号后再重试。")
            }
            close()
        }, 45_000L)

        // Opening a fresh container after any previous one has finished avoids stale static WebView state.
        main.postDelayed({
            if (closed.get()) return@postDelayed
            val intent = Intent().apply {
                component = ComponentName(target.spec.packageName, target.gmActivityClass)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("GM_WEBVIEW_URL", "http://$host$base/page")
            }
            try {
                appContext.startActivity(intent)
            } catch (_: Exception) {
                fail("无法拉起「${target.appLabel}」网页容器，该游戏大概率不支持此登录方式（仅支持开放网页桥接口的网易官服游戏）。")
                close()
            }
        }, 1500L)
    }

    private fun serve() {
        while (!closed.get()) {
            try {
                server.accept().use { socket ->
                    if (!socket.inetAddress.isLoopbackAddress) return@use
                    socket.soTimeout = 2500

                    val request = HttpRequest.read(socket.getInputStream())
                    val correctHost = host == request.headers["host"]
                    val origin = request.headers["origin"]
                    if (!correctHost || (origin != null && origin != "http://$host") || !request.path.startsWith("$base/")) {
                        reply(socket, 403, "text/plain", "Unavailable")
                        return@use
                    }

                    val route = request.path.substring(base.length)
                    if (request.method == "GET" && route == "/page") {
                        BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.PAGE_OPENED)
                        reply(
                            socket,
                            200,
                            "text/html; charset=utf-8",
                            BridgePage.render(
                                appLabel = target.appLabel,
                                base = "http://$host$base",
                                compatibilityMode = nonEmptyToken
                            )
                        )
                        return@use
                    }
                    if (request.method != "POST") {
                        reply(socket, 404, "text/plain", "Unavailable")
                        return@use
                    }

                    val input = try {
                        JSONObject(String(request.body, StandardCharsets.UTF_8))
                    } finally {
                        request.body.fill(0)
                    }

                    when (route) {
                        "/check" -> {
                            // A fresh game bridge account-context response is required before
                            // any iOS preparation file is generated. This does not prove server login.
                            val identity = runCatching { readIdentity(input) }.getOrNull()
                            if (identity == null) {
                                BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.ACCOUNT_CHECK_FAILED)
                                fail("游戏接口未返回可识别的账号状态，请返回游戏重新登录。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"source_mismatch\"}")
                                return@use
                            }
                            if (identity.uid == null) {
                                BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.ACCOUNT_CHECK_FAILED)
                                fail("未检测到游戏登录账号，请先在游戏中完成登录。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"not_logged_in\"}")
                                return@use
                            }
                            verifiedContext = identity
                            BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.ACCOUNT_CHECK_PASSED)
                            reply(socket, 200, "application/json", "{\"ok\":true}")
                        }

                        "/prepare" -> {
                            val parsed = runCatching { readIdentity(input) }.getOrNull()
                            if (parsed == null) {
                                fail("游戏客户端返回的上下文格式不匹配，请重新打开「${target.appLabel}」后再试。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"source_mismatch\"}")
                                return@use
                            }
                            val verified = verifiedContext
                            if (verified == null || verified != parsed || verified.uid == null) {
                                BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.ACCOUNT_CHECK_FAILED)
                                fail("游戏登录状态已变化，请重新检测后再准备。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"context_changed\"}")
                                return@use
                            }
                            // One verified game context can be used for one seed only.
                            verifiedContext = null
                            val uid = parsed.uid
                            if (uid == null) {
                                fail("未读取到已登录的网易账号，请先在「${target.appLabel}」正常登录该账号后再试。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"not_logged_in\"}")
                                return@use
                            }

                            BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.CONTEXT_RECEIVED)
                            capturedGameId = parsed.gameId
                            prefs(appContext).edit()
                                .putString("last_game_id_${target.spec.packageName}", parsed.gameId)
                                .apply()

                            val seed = try {
                                getOrWriteSeed(uid)
                            } catch (e: SeedStorageException) {
                                BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.STORAGE_ERROR)
                                fail(e.message ?: "准备文件写入失败，请检查目录授权或写入方式后重试。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"storage_failed\"}")
                                return@use
                            } catch (_: DirectoryAccessRequired) {
                                BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.STORAGE_ERROR)
                                fail("共享媒体目录授权已失效，请返回助手重新授权 Android/media 目录。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"directory_required\"}")
                                return@use
                            } catch (_: Exception) {
                                BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.STORAGE_ERROR)
                                fail("准备文件写入失败，请返回助手检查写入方式与剩余空间后重试。")
                                reply(socket, 200, "application/json", "{\"ok\":false,\"reason\":\"storage_failed\"}")
                                return@use
                            }

                            BridgeDiagnosticLog.record(
                                appContext, target.spec.packageName,
                                if (seed.usedSaf) BridgeLogEvent.SAF_VERIFIED else BridgeLogEvent.CACHE_WRITTEN
                            )
                            val command = JSONObject()
                                .put("methodId", "execute_extend_func")
                                .put(
                                    "reqData",
                                    JSONObject()
                                        .put("channel", BridgeProtocol.DEFAULT_LOGIN_CHANNEL)
                                        .put("methodId", "loadMpaySession")
                                        .put("path", seed.sdkPath)
                                        .put("callback_mode", "web")
                                )
                            val resp = JSONObject()
                                .put("ok", true)
                                .put("command", command)
                                .toString()
                            BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.LOAD_REQUEST_READY)
                            reply(socket, 200, "application/json", resp)
                        }

                        "/stage" -> {
                            // Accept only fixed event identifiers; never persist arbitrary web input.
                            val event = when (input.optString("event")) {
                                "load_dispatched" -> BridgeLogEvent.LOAD_DISPATCHED
                                "sdk_callback_seen" -> BridgeLogEvent.SDK_CALLBACK_RECEIVED
                                else -> null
                            }
                            if (event == null || preparedSeed == null) {
                                reply(socket, 400, "application/json", "{\"ok\":false}")
                            } else {
                                BridgeDiagnosticLog.record(appContext, target.spec.packageName, event)
                                reply(socket, 200, "application/json", "{\"ok\":true}")
                            }
                        }

                        "/submitted" -> {
                            if (preparedSeed == null) {
                                reply(socket, 403, "application/json", "{\"ok\":false}")
                                return@use
                            }
                            val confirmed = input.optBoolean("confirmed", false)
                            val callbackReason = when {
                                confirmed -> "sdk_confirmed"
                                input.optString("reason") == "manual_return" -> "manual_return"
                                input.optString("reason") == "unrecognized_callback" -> "unrecognized_callback"
                                else -> "callback_timeout"
                            }
                            val usedSaf = preparedSeed?.usedSaf == true
                            val gameId = capturedGameId
                            val first = resultDelivered.compareAndSet(false, true)
                            if (first) {
                                BridgeDiagnosticLog.record(
                                    appContext, target.spec.packageName,
                                    when {
                                        confirmed -> BridgeLogEvent.SDK_CONFIRMED
                                        callbackReason == "manual_return" -> BridgeLogEvent.USER_LEFT_EARLY
                                        callbackReason == "unrecognized_callback" -> BridgeLogEvent.SDK_UNKNOWN_CALLBACK
                                        else -> BridgeLogEvent.SDK_TIMEOUT
                                    }
                                )
                                unconfirmedCleanupDeferred = !confirmed
                                prefs(appContext).edit()
                                    .putString("state", if (confirmed) "submitted" else "unconfirmed")
                                    .putString("state_package", target.spec.packageName)
                                    .putString("completed_operation", nonce)
                                    .apply()
                            }
                            reply(socket, 200, "application/json", "{\"ok\":true}")
                            if (first) {
                                main.post { listener.onSubmitted(gameId, confirmed, usedSaf, callbackReason) }
                            }
                        }

                        "/failed" -> {
                            val reason = input.optString("reason")
                            val message = when (reason) {
                                "not_logged_in" -> "未读取到已登录的网易账号，请先在「${target.appLabel}」正常登录该账号后再试。"
                                "no_callback" -> "没有收到游戏接口响应，请返回游戏关闭当前页面后重试。"
                                "bridge_missing" -> "游戏网页桥未就绪，请先打开「${target.appLabel}」登录进入主页后再试。"
                                "load_not_dispatched" -> "临时准备文件已写入，但游戏网页桥未能提交 SDK 加载请求。请确认游戏组件是否支持此方法。"
                                "sdk_read_failed" -> "SDK 未能读取准备文件，该游戏可能不支持当前写入方式。"
                                "sdk_invalid_session" -> "SDK 已读取文件，但未接受账号准备格式，当前游戏仍需适配。"
                                "sdk_rejected" -> "SDK 拒绝了本次登录准备，当前游戏仍需适配。"
                                else -> "本次准备未完成，请返回游戏关闭当前页面后重试。"
                            }
                            BridgeDiagnosticLog.record(
                                appContext, target.spec.packageName,
                                when (reason) {
                                    "sdk_read_failed" -> BridgeLogEvent.SDK_READ_FAILED
                                    "sdk_invalid_session", "sdk_rejected" -> BridgeLogEvent.SDK_REJECTED
                                    "load_not_dispatched" -> BridgeLogEvent.LOAD_NOT_DISPATCHED
                                    "not_logged_in" -> BridgeLogEvent.NO_ACCOUNT
                                    "no_callback", "bridge_missing" -> BridgeLogEvent.BRIDGE_NOT_READY
                                    "storage_failed", "directory_required" -> BridgeLogEvent.STORAGE_ERROR
                                    else -> BridgeLogEvent.REQUEST_FAILED
                                }
                            )
                            reply(socket, 200, "application/json", "{\"ok\":true}")
                            fail(message)
                        }

                        else -> reply(socket, 404, "text/plain", "Unavailable")
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            }
        }
    }

    private fun readIdentity(input: JSONObject): ContextIdentity {
        val game = input.optString("game")
        if (!BridgeProtocol.supportedSource(game = game, expectedGameId = expectedGameId)) {
            throw IOException("source")
        }
        val full = input.optString("full")
        if (full.isBlank()) {
            return ContextIdentity(uid = null, gameId = game)
        }
        return ContextIdentity(
            uid = BridgeProtocol.uidFromQualifiedName(full),
            gameId = game
        )
    }

    @Synchronized
    private fun getOrWriteSeed(uid: String): PreparedSeed {
        preparedSeed?.let { return it }

        val fileName = "ntes_mpay_${UUID.randomUUID().toString().replace("-", "")}.tmp"
        val user = JSONObject()
            .put("id", uid)
            .put("token", if (nonEmptyToken) "0" else "")
            .put("need_mask", true)
            .put("login_type", BridgeProtocol.DEFAULT_LOGIN_TYPE)
            .put("login_channel", BridgeProtocol.DEFAULT_LOGIN_CHANNEL)
            .put(
                "pc_ext_info",
                JSONObject()
                    .put("src_client_type", BridgeProtocol.IOS_CLIENT_TYPE)
                    .put("is_remember", true)
                    .put("is_from_ticket_login", true)
            )
        val bytes = (JSONObject().put("user", user).toString() + "\n")
            .toByteArray(StandardCharsets.UTF_8)

        val seed = try {
            SeedStorage.write(appContext, target, fileName, bytes, transportMode)
        } finally {
            bytes.fill(0)
        }
        preparedSeed = seed

        val cleanupUri = seed.cleanupUri.toString()
        main.postDelayed({ SeedStorage.deleteAsync(appContext, cleanupUri) }, 45_000L)
        return seed
    }

    private fun fail(message: String) {
        if (!resultDelivered.compareAndSet(false, true)) return
        BridgeDiagnosticLog.record(appContext, target.spec.packageName, BridgeLogEvent.REQUEST_FAILED)
        prefs(appContext).edit()
            .putString("state", "failed")
            .putString("state_package", target.spec.packageName)
            .putString("last_error", message)
            .remove("completed_operation")
            .apply()
        main.post { listener.onFailed(message) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            val cleanupUri = preparedSeed?.cleanupUri?.toString()
            if (cleanupUri != null) {
                if (!unconfirmedCleanupDeferred) {
                    SeedStorage.deleteAsync(appContext, cleanupUri)
                }
                // Missing SDK callback is not a completed import. Keep the bounded
                // 45-second cleanup scheduled in getOrWriteSeed() instead of
                // deleting the seed 5 seconds after a provisional /submitted.
                // This avoids a race with games that read the file asynchronously.
            }
        }
    }

    companion object {
        private const val PREFS = "netease_mpay_bridge"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun cleanup(context: Context) {
            val appCtx = context.applicationContext
            Thread({ SeedStorage.cleanup(appCtx) }, "NeteaseSeedBootCleanup").apply {
                isDaemon = true
                start()
            }
        }

        private fun reply(socket: Socket, status: Int, type: String, text: String) {
            val body = text.toByteArray(StandardCharsets.UTF_8)
            val statusText = if (status == 200) "OK" else "Unavailable"
            val header = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "Content-Security-Policy: default-src 'none';connect-src 'self';script-src 'unsafe-inline';style-src 'unsafe-inline'\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(header.toByteArray(StandardCharsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }
}

/**
 * Bounded, local-only diagnostic events. Never writes UID, login tokens, payloads,
 * arbitrary SDK error messages or native response objects to the event history.
 */
internal enum class BridgeLogEvent(val label: String) {
    PREPARE_TAPPED("开始准备 iOS 登录"),
    TRANSPORT_SAF("准备方式：SAF 共享目录"),
    TRANSPORT_CACHE("准备方式：标准缓存"),
    PLACEHOLDER_ON("兼容设置：非空占位已开启"),
    PLACEHOLDER_OFF("兼容设置：非空占位已关闭"),
    SESSION_STARTED("桥接会话已启动"),
    PAGE_OPENED("游戏桥接页已打开"),
    CONTEXT_RECEIVED("收到账号上下文（不保存内容）"),
    ACCOUNT_CHECK_PASSED("写入前游戏账号检查通过"),
    ACCOUNT_CHECK_FAILED("写入前游戏账号检查失败，未继续写入"),
    SAF_VERIFIED("临时文件写入完成且 SAF 读回一致"),
    CACHE_WRITTEN("缓存临时文件写入完成"),
    LOAD_REQUEST_READY("已生成 SDK 加载请求"),
    LOAD_DISPATCHED("网页桥调用已发出（非 SDK 成功证明）"),
    SDK_CALLBACK_RECEIVED("收到 SDK 回调（内容未记录）"),
    SDK_CONFIRMED("SDK 返回成功码，游戏角色需另行核对"),
    SDK_UNKNOWN_CALLBACK("SDK 回调无法识别，结果未知"),
    SDK_TIMEOUT("30 秒未得到 SDK 确认"),
    USER_LEFT_EARLY("提前返回游戏，无法确认 SDK 结果"),
    SDK_READ_FAILED("SDK 报告无法读取准备文件"),
    SDK_REJECTED("SDK 拒绝或报告登录格式无效"),
    LOAD_NOT_DISPATCHED("网页桥未能调用 SDK 加载方法"),
    NO_ACCOUNT("未读取到已登录账号"),
    STORAGE_ERROR("目录授权或文件写入失败"),
    BRIDGE_NOT_READY("游戏网页桥未就绪"),
    REQUEST_FAILED("本次准备未完成")
}

internal object BridgeDiagnosticLog {
    private const val MAX_ENTRIES = 60
    private const val MAX_TEXT_LENGTH = 12_000

    @Synchronized
    fun record(context: Context, packageName: String, event: BridgeLogEvent) {
        if (!BridgeProtocol.isValidPackageName(packageName)) return
        val prefs = MpayBridgeSession.prefs(context)
        val key = "bridge_log_$packageName"
        val old = prefs.getString(key, "").orEmpty().lineSequence()
            .filter { it.isNotBlank() }.takeLastSafe(MAX_ENTRIES - 1)
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val updated = (old + "$time  ${event.label}").takeLast(MAX_ENTRIES)
        prefs.edit().putString(key, updated.joinToString("\n").takeLast(MAX_TEXT_LENGTH)).apply()
    }

    @Synchronized
    fun entries(context: Context, packageName: String): List<String> {
        if (!BridgeProtocol.isValidPackageName(packageName)) return emptyList()
        return MpayBridgeSession.prefs(context)
            .getString("bridge_log_$packageName", "").orEmpty()
            .lineSequence().filter { it.isNotBlank() }.take(MAX_ENTRIES).toList()
    }

    @Synchronized
    fun clear(context: Context, packageName: String) {
        if (!BridgeProtocol.isValidPackageName(packageName)) return
        MpayBridgeSession.prefs(context).edit().remove("bridge_log_$packageName").apply()
    }

    private fun Sequence<String>.takeLastSafe(limit: Int): List<String> =
        toList().takeLast(limit)
}
