package cn.tabletzoom

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.MagnificationConfig
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent

/**
 * Controls Android's compositor-level magnifier. It never captures screen
 * frames and never adds a full-screen overlay, so the target app does not see
 * a MediaProjection/recording session and touch coordinates remain native.
 */
class ZoomAccessibilityService : AccessibilityService() {
    companion object {
        private const val PREFS = "zoom_settings"
        private const val KEY_SCALE = "scale"
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f

        @Volatile
        private var instance: ZoomAccessibilityService? = null

        fun startZoom(context: Context, scale: Float, packageName: String): Boolean {
            // packageName is intentionally accepted for the activity API. The
            // system magnifier operates on the current display, so it does not
            // need to inspect or filter the target app's frames.
            val safeScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putFloat(KEY_SCALE, safeScale)
                .apply()
            val service = instance ?: return false
            val applied = service.applyScale(safeScale)
            if (applied) service.updateOverlay(safeScale, safeScale > MIN_SCALE)
            return applied
        }

        fun stopZoom() {
            instance?.resetScale()
        }

        fun adjustZoom(delta: Float) {
            val service = instance ?: return
            val current = service.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat(KEY_SCALE, MIN_SCALE)
            val next = (current + delta).coerceIn(MIN_SCALE, MAX_SCALE)
            if (service.applyScale(next)) service.updateOverlay(next, next > MIN_SCALE)
            service.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putFloat(KEY_SCALE, next)
                .apply()
        }
    }

    private var magnification: MagnificationController? = null
    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var overlayLabel: TextView? = null
    private var overlayMinus: TextView? = null
    private var overlayPlus: TextView? = null
    private var overlayStop: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        magnification = getMagnificationController()
        windowManager = getSystemService(WindowManager::class.java)
        showOverlay()
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(KEY_SCALE, MIN_SCALE)
        // Explicitly reset as well as restore. Android can retain a previous
        // system magnification state after a service has been restarted.
        applyScale(saved)
        updateOverlay(saved, saved > MIN_SCALE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun applyScale(requested: Float): Boolean {
        val controller = magnification ?: return false
        val scale = requested.coerceIn(MIN_SCALE, MAX_SCALE)
        if (scale <= MIN_SCALE) {
            return controller.reset(false)
        }

        val bounds = displayBounds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ supports an atomic config update. Selecting fullscreen
            // mode avoids the separate window magnifier and keeps the change
            // inside Android's compositor.
            val builder = MagnificationConfig.Builder()
                .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(scale)
                .setCenterX(bounds.exactCenterX())
                .setCenterY(bounds.exactCenterY())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                builder.setActivated(true)
            }
            val config = builder.build()
            return controller.setMagnificationConfig(config, false)
        } else {
            @Suppress("DEPRECATION")
            val scaleSet = controller.setScale(scale, false)
            @Suppress("DEPRECATION")
            val centerSet = controller.setCenter(bounds.exactCenterX(), bounds.exactCenterY(), false)
            return scaleSet && centerSet
        }
    }

    private fun resetScale() {
        magnification?.reset(false)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SCALE, MIN_SCALE)
            .apply()
        updateOverlay(MIN_SCALE, false)
    }

    /**
     * A small control bar owned by the accessibility service. It contains no
     * screen pixels or video content; TYPE_ACCESSIBILITY_OVERLAY lets Android
     * keep it above other apps without screen capture.
     */
    private fun showOverlay() {
        if (overlay != null || windowManager == null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(4), dp(3))
            background = roundedBackground(0xDD20252B.toInt(), dp(18))
            contentDescription = "自定义缩放控制"
        }
        overlayLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "自定义缩放"
            setPadding(dp(2), 0, dp(6), 0)
            setOnClickListener { openMainActivity() }
            contentDescription = "打开自定义缩放设置"
        }
        root.addView(overlayLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        overlayMinus = overlayButton("−", "缩小", -0.05f)
        root.addView(overlayMinus, overlayButtonParams())
        overlayPlus = overlayButton("+", "放大", 0.05f)
        root.addView(overlayPlus, overlayButtonParams())
        overlayStop = TextView(this).apply {
            setTextColor(0xFFFFD180.toInt())
            textSize = 12f
            text = "停止"
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedBackground(0x55333333, dp(12))
            setOnClickListener { stopZoom() }
            contentDescription = "停止缩放"
        }
        root.addView(overlayStop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }
        runCatching { windowManager?.addView(root, params) }
            .onSuccess { overlay = root }
            .onFailure { overlay = null }
    }

    private fun updateOverlay(scale: Float, active: Boolean) {
        overlayLabel?.text = if (active) {
            "缩放 %.2f×".format(java.util.Locale.US, scale)
        } else {
            "自定义缩放"
        }
        overlayStop?.visibility = if (active) View.VISIBLE else View.INVISIBLE
        overlayMinus?.visibility = if (active) View.VISIBLE else View.INVISIBLE
        overlayPlus?.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    private fun overlayButton(label: String, description: String, delta: Float) = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(7), 0, dp(7), dp(1))
        contentDescription = description
        setOnClickListener { adjustZoom(delta) }
    }

    private fun overlayButtonParams() = LinearLayout.LayoutParams(
        dp(28),
        dp(30)
    )

    private fun openMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun displayBounds(): Rect {
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    override fun onDestroy() {
        resetScale()
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlay = null
        overlayLabel = null
        overlayMinus = null
        overlayPlus = null
        overlayStop = null
        windowManager = null
        magnification = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
