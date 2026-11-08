package cn.tabletzoom

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.MagnificationConfig
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        private const val KEY_DEFAULT_SCALE = "default_scale"
        private const val KEY_SCALE_ACTIVE = "scale_active"
        private const val KEY_ACTIVE_SCALE_KEY = "active_scale_key"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_PAN_X = "pan_x"
        private const val KEY_PAN_Y = "pan_y"
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
        private const val FALLBACK_DEFAULT_SCALE = 1.17f
        private const val OVERLAY_HIDE_DELAY_MS = 3000L

        @Volatile
        private var instance: ZoomAccessibilityService? = null

        fun startZoom(
            context: Context,
            scale: Float,
            packageName: String,
            requestedAt: Long,
            panX: Float,
            panY: Float
        ): Boolean {
            // MainActivity has already opened the target app before this call.
            // Mark the session here: its first window event may have arrived
            // before the new package name was saved.
            val service = instance ?: return false
            // HOME may arrive during MainActivity's short launch delay.
            // Never activate a request that the user has already left.
            if (service.lastHomeEventAt >= requestedAt) return false
            val safeScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val scaleKey = prefs.getString(KEY_ACTIVE_SCALE_KEY, KEY_SCALE) ?: KEY_SCALE
            prefs
                .edit()
                .putFloat(KEY_SCALE, safeScale)
                .putFloat(scaleKey, safeScale)
                .putFloat(KEY_PAN_X, panX)
                .putFloat(KEY_PAN_Y, panY)
                .putString(KEY_TARGET_PACKAGE, packageName)
                .putBoolean(KEY_SCALE_ACTIVE, true)
                .apply()
            service.sessionStartedAt = requestedAt
            service.showOverlay()
            val applied = service.applyScale(safeScale, panX, panY)
            if (applied) {
                service.targetPackageSeen = safeScale > MIN_SCALE
                service.updateOverlay(safeScale, safeScale > MIN_SCALE)
            }
            return applied
        }

        fun stopZoom() {
            instance?.resetScale()
        }

        fun adjustZoom(delta: Float) {
            val service = instance ?: return
            val prefs = service.getSharedPreferences(PREFS, MODE_PRIVATE)
            val scaleKey = prefs.getString(KEY_ACTIVE_SCALE_KEY, KEY_SCALE) ?: KEY_SCALE
            val current = prefs.getFloat(scaleKey, prefs.getFloat(KEY_SCALE, MIN_SCALE))
            val next = (current + delta).coerceIn(MIN_SCALE, MAX_SCALE)
            val panX = prefs.getFloat(KEY_PAN_X, 0f)
            val panY = prefs.getFloat(KEY_PAN_Y, 0f)
            if (service.applyScale(next, panX, panY)) service.updateOverlay(next, next > MIN_SCALE)
            prefs.edit()
                .putFloat(KEY_SCALE, next)
                .putFloat(scaleKey, next)
                .putBoolean(KEY_SCALE_ACTIVE, true)
                .apply()
        }

        fun restoreDefaultZoom() {
            val service = instance ?: return
            val prefs = service.getSharedPreferences(PREFS, MODE_PRIVATE)
            val defaultScale = prefs.getFloat(KEY_DEFAULT_SCALE, FALLBACK_DEFAULT_SCALE)
                .coerceIn(MIN_SCALE, MAX_SCALE)
            val panX = prefs.getFloat(KEY_PAN_X, 0f)
            val panY = prefs.getFloat(KEY_PAN_Y, 0f)
            if (service.applyScale(defaultScale, panX, panY)) {
                val scaleKey = prefs.getString(KEY_ACTIVE_SCALE_KEY, KEY_SCALE) ?: KEY_SCALE
                prefs.edit()
                    .putFloat(KEY_SCALE, defaultScale)
                    .putFloat(scaleKey, defaultScale)
                    .putBoolean(KEY_SCALE_ACTIVE, true)
                    .apply()
                service.updateOverlay(defaultScale, true)
            }
        }

        fun showOverlayControls() {
            val service = instance ?: return
            service.showOverlay()
            val prefs = service.getSharedPreferences(PREFS, MODE_PRIVATE)
            val scaleKey = prefs.getString(KEY_ACTIVE_SCALE_KEY, KEY_SCALE) ?: KEY_SCALE
            val scale = prefs.getFloat(scaleKey, prefs.getFloat(KEY_SCALE, FALLBACK_DEFAULT_SCALE))
            service.updateOverlay(scale, prefs.getBoolean(KEY_SCALE_ACTIVE, false))
        }
    }

    private var magnification: MagnificationController? = null
    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var overlayLabel: TextView? = null
    private var overlayMinus: TextView? = null
    private var overlayPlus: TextView? = null
    private var overlayDefault: TextView? = null
    private var overlayStop: TextView? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var overlayHideTask: Runnable? = null
    private var overlayActive = false
    private var targetPackageSeen = false
    private var sessionStartedAt = 0L
    private var lastHomeEventAt = 0L
    private val homePackage by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0
        )?.activityInfo?.packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        magnification = getMagnificationController()
        windowManager = getSystemService(WindowManager::class.java)
        showOverlay()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val scaleKey = prefs.getString(KEY_ACTIVE_SCALE_KEY, KEY_SCALE) ?: KEY_SCALE
        val saved = prefs.getFloat(scaleKey, prefs.getFloat(KEY_SCALE, FALLBACK_DEFAULT_SCALE))
        val active = prefs.getBoolean(KEY_SCALE_ACTIVE, false)
        targetPackageSeen = active
        sessionStartedAt = SystemClock.uptimeMillis()
        // Explicitly reset as well as restore. Android can retain a previous
        // system magnification state after a service has been restarted.
        applyScale(
            if (active) saved else MIN_SCALE,
            prefs.getFloat(KEY_PAN_X, 0f),
            prefs.getFloat(KEY_PAN_Y, 0f)
        )
        updateOverlay(saved, active)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == homePackage) lastHomeEventAt = event.eventTime
        val target = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_TARGET_PACKAGE, null)
        if (target != null && packageName == target) {
            targetPackageSeen = true
            return
        }
        if (
            overlayActive && targetPackageSeen &&
            event.eventTime >= sessionStartedAt && packageName == homePackage
        ) {
            targetPackageSeen = false
            resetScale()
        }
        // Our accessibility overlay also emits events under our package name.
        // MainActivity.onResume handles returning to settings without treating
        // the control bar itself as an app switch.
    }

    override fun onInterrupt() = Unit

    private fun applyScale(requested: Float, panX: Float = 0f, panY: Float = 0f): Boolean {
        val controller = magnification ?: return false
        val scale = requested.coerceIn(MIN_SCALE, MAX_SCALE)
        if (scale <= MIN_SCALE) {
            return deactivateMagnification()
        }

        val bounds = displayBounds()
        val center = MagnificationPan.center(
            bounds.width().toFloat(), bounds.height().toFloat(), scale, panX, panY
        )
        val centerX = center.x
        val centerY = center.y
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ supports an atomic config update. Selecting fullscreen
            // mode avoids the separate window magnifier and keeps the change
            // inside Android's compositor.
            val builder = MagnificationConfig.Builder()
                .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(scale)
                .setCenterX(centerX)
                .setCenterY(centerY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                builder.setActivated(true)
            }
            val config = builder.build()
            return controller.setMagnificationConfig(config, false)
        } else {
            @Suppress("DEPRECATION")
            val scaleSet = controller.setScale(scale, false)
            @Suppress("DEPRECATION")
            val centerSet = controller.setCenter(centerX, centerY, false)
            return scaleSet && centerSet
        }
    }

    private fun deactivateMagnification(): Boolean {
        val controller = magnification ?: return false
        var accepted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val inactive = MagnificationConfig.Builder()
                .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                .setActivated(false)
                .setScale(MIN_SCALE)
                .build()
            accepted = controller.setMagnificationConfig(inactive, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            accepted = controller.resetCurrentMagnification(false) || accepted
        }
        return controller.reset(false) || accepted
    }

    private fun resetScale() {
        deactivateMagnification()
        targetPackageSeen = false
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCALE_ACTIVE, false)
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
        overlayDefault = TextView(this).apply {
            setTextColor(0xFFB8D8FF.toInt())
            textSize = 12f
            text = "默认"
            setPadding(dp(7), dp(5), dp(7), dp(5))
            contentDescription = "恢复默认比例"
            setOnClickListener { restoreDefaultZoom() }
        }
        root.addView(overlayDefault, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
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
        overlayActive = active
        overlayLabel?.text = if (active) {
            "缩放 %.2f×".format(java.util.Locale.US, scale)
        } else {
            "自定义缩放"
        }
        setControlsVisible(active)
        scheduleOverlayHide()
    }

    private fun setControlsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        overlayStop?.visibility = visibility
        overlayMinus?.visibility = visibility
        overlayPlus?.visibility = visibility
        overlayDefault?.visibility = visibility
    }

    private fun scheduleOverlayHide() {
        cancelOverlayHide()
        val task = Runnable {
            removeOverlay()
        }
        overlayHideTask = task
        overlayHandler.postDelayed(task, OVERLAY_HIDE_DELAY_MS)
    }

    private fun cancelOverlayHide() {
        overlayHideTask?.let(overlayHandler::removeCallbacks)
        overlayHideTask = null
    }

    private fun removeOverlay() {
        cancelOverlayHide()
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlay = null
        overlayLabel = null
        overlayMinus = null
        overlayPlus = null
        overlayDefault = null
        overlayStop = null
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
        cancelOverlayHide()
        overlayHandler.removeCallbacksAndMessages(null)
        resetScale()
        removeOverlay()
        windowManager = null
        magnification = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
