package me.naptila.neteaseios

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small, temporary confirmation window visible over the selected game.
 * It is not a game-login detector, accessibility service, or screen reader.
 * No game content or user input is inspected. Requires explicit overlay grant.
 */
internal class GameReadyOverlay(
    private val activity: Activity,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit
) {
    private val manager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var confirming = false
    private val timeout = Runnable {
        if (root != null) {
            dismiss()
            onCancel()
        }
    }

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(activity) || root != null) return false

        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val background = if (isDark) Color.rgb(35, 37, 44) else Color.WHITE
        val foreground = if (isDark) Color.WHITE else Color.rgb(20, 35, 57)
        val muted = if (isDark) Color.rgb(195, 202, 219) else Color.rgb(83, 97, 119)
        val blue = if (isDark) Color.rgb(152, 187, 255) else Color.rgb(35, 78, 156)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(background)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), if (isDark) Color.rgb(75, 91, 118) else Color.rgb(213, 224, 242))
            }
            elevation = dp(12).toFloat()
        }

        panel.addView(TextView(activity).apply {
            text = "确认游戏已进入"
            textSize = 17f
            setTextColor(foreground)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val description = TextView(activity).apply {
            text = "已看到欢迎提示或进入游戏主页？"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(12))
        }
        panel.addView(description)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = TextView(activity).apply {
            text = "暂不准备"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            isClickable = true
            setOnClickListener { dismiss(); onCancel() }
        }
        val confirm = TextView(activity).apply {
            text = "确定"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(blue)
                cornerRadius = dp(11).toFloat()
            }
            setPadding(dp(23), dp(9), dp(23), dp(9))
            isClickable = true
            setOnClickListener {
                if (confirming) return@setOnClickListener
                confirming = true
                handler.removeCallbacks(timeout)
                description.text = "正在进入 iOS 登录桥接…"
                cancel.isEnabled = false
                isEnabled = false
                // Keep the permitted overlay visible briefly during game-webview
                // activation; Android restricts activity starts from background.
                onConfirm()
                handler.postDelayed({ dismiss() }, 3_500L)
            }
        }
        actions.addView(cancel)
        actions.addView(confirm)
        panel.addView(actions)

        val params = WindowManager.LayoutParams(
            dp(310),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
            title = "网易 iOS 登录确认"
        }
        return try {
            manager.addView(panel, params)
            root = panel
            handler.postDelayed(timeout, 90_000L)
            true
        } catch (_: Exception) {
            root = null
            false
        }
    }

    fun dismiss() {
        handler.removeCallbacks(timeout)
        val attached = root ?: return
        root = null
        runCatching { manager.removeViewImmediate(attached) }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
