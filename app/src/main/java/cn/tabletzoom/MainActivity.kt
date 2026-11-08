package cn.tabletzoom

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    companion object {
        private const val PREFS = "zoom_settings"
        private const val KEY_LAST_APP = "last_app_package"
        private const val KEY_SCALE = "scale"
        private const val KEY_SCALE_SCREEN_RATIO = "scale_screen_ratio"
        private const val KEY_SCALE_TARGET_RATIO = "scale_target_ratio"
        private const val KEY_ACTIVE_SCALE_KEY = "active_scale_key"
        private const val KEY_DEFAULT_SCALE = "default_scale"
        private const val REQUEST_PICK_IMAGE = 7201
    }

    private data class LaunchableApp(val label: String, val packageName: String) {
        override fun toString() = label
    }

    private lateinit var apps: List<LaunchableApp>
    private lateinit var appPicker: Spinner
    private lateinit var zoomValue: TextView
    private lateinit var currentRatioInput: EditText
    private lateinit var targetRatioInput: EditText
    private lateinit var presetPicker: Spinner
    private lateinit var accessibilityStatus: TextView
    private lateinit var startButton: Button
    private var zoom = 1f
    private var applyingPreset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityStatus.isInitialized) refreshAccessibilityState()
    }

    private fun buildScreen() {
        val pad = dp(24)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFF5F6F7.toInt())
        }
        content.addView(TextView(this).apply {
            text = "自定义缩放"
            textSize = 23f
            setTextColor(0xFF20252B.toInt())
        }, fullWidth())
        content.addView(TextView(this).apply {
            text = "请开启无障碍服务，并在最近任务中锁定本应用以保持后台运行。屏幕比例自动读取；上次应用和倍率会记住。"
            textSize = 15f
            setTextColor(0xFF515B66.toInt())
            setPadding(0, dp(8), 0, dp(18))
        }, fullWidth())

        content.addView(TextView(this).apply { text = "当前屏幕比例"; textSize = 14f })
        currentRatioInput = ratioInput(detectedScreenRatio())
        content.addView(currentRatioInput, fullWidth())

        content.addView(TextView(this).apply {
            text = "目标视频比例"
            textSize = 14f
            setPadding(0, dp(14), 0, 0)
        }, fullWidth())
        targetRatioInput = ratioInput("4:3")
        content.addView(targetRatioInput, fullWidth())
        content.addView(TextView(this).apply {
            text = "常用比例"
            textSize = 13f
            setTextColor(0xFF65717B.toInt())
            setPadding(0, dp(5), 0, 0)
        }, fullWidth())
        presetPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("常用比例…", "16:9", "16:10", "3:2", "7:5", "4:3", "5:4", "1:1", "9:16")
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (position > 0) {
                        applyingPreset = true
                        targetRatioInput.setText(parent?.getItemAtPosition(position).toString())
                        applyingPreset = false
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        content.addView(presetPicker, fullWidth())

        zoomValue = TextView(this).apply {
            textSize = 19f
            setTextColor(0xFF18705C.toInt())
            setPadding(0, dp(18), 0, dp(3))
        }
        content.addView(zoomValue, fullWidth())
        content.addView(TextView(this).apply {
            text = "放大后会裁切画面边缘。可编辑上方比例调整结果。"
            textSize = 13f
            setTextColor(0xFF65717B.toInt())
            setPadding(0, 0, 0, dp(16))
        }, fullWidth())
        content.addView(Button(this).apply {
            text = "从相册选择图片预览"
            setOnClickListener { pickPreviewImage() }
        }, fullWidth())

        content.addView(TextView(this).apply { text = "打开的应用"; textSize = 14f })
        appPicker = Spinner(this)
        apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == applicationContext.packageName) return@mapNotNull null
            LaunchableApp(info.loadLabel(packageManager).toString(), packageName)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        appPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps)
        val lastPackage = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_APP, null)
        val lastIndex = lastPackage?.let { packageName ->
            apps.indexOfFirst { it.packageName == packageName }
        } ?: -1
        if (lastIndex >= 0) appPicker.setSelection(lastIndex)
        appPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                apps.getOrNull(position)?.let { selected ->
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST_APP, selected.packageName)
                        .apply()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        content.addView(appPicker, fullWidth())

        accessibilityStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(18), 0, dp(4))
        }
        content.addView(accessibilityStatus, fullWidth())
        startButton = Button(this).apply {
            text = "开始缩放"
            setOnClickListener { startZoom() }
        }
        content.addView(startButton, fullWidth().apply { topMargin = dp(12) })
        content.addView(Button(this).apply {
            text = "停止缩放"
            setOnClickListener {
                ZoomAccessibilityService.stopZoom()
                refreshAccessibilityState()
            }
        }, fullWidth())
        content.addView(Button(this).apply {
            text = "显示缩放悬浮窗"
            setOnClickListener { ZoomAccessibilityService.showOverlayControls() }
        }, fullWidth())

        val scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateScale()
        }
        currentRatioInput.addTextChangedListener(watcher)
        targetRatioInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingPreset) presetPicker.setSelection(0)
            }
            override fun afterTextChanged(s: Editable?) = updateScale()
        })
        presetPicker.setSelection(5)
        updateScale()
        refreshAccessibilityState()
    }

    private fun ratioInput(value: String) = EditText(this).apply {
        setSingleLine(true)
        textSize = 17f
        hint = "例如 3:2 或 1.500"
        setText(value)
    }

    private fun updateScale() {
        val current = AspectRatioZoom.parse(currentRatioInput.text.toString())
        val target = AspectRatioZoom.parse(targetRatioInput.text.toString())
        if (current == null || target == null) {
            zoomValue.text = "请输入有效比例，例如 3:2、4:3 或 1.500"
            zoom = 0f
            return
        }
        val defaultScale = AspectRatioZoom.defaultScale(current, target).toFloat()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pairScaleKey = scalePreferenceKey(current, target)
        val remembered = if (prefs.contains(pairScaleKey)) {
            prefs.getFloat(pairScaleKey, Float.NaN)
        } else if (
            prefs.getString(KEY_SCALE_SCREEN_RATIO, null) == ratioKey(current) &&
            prefs.getString(KEY_SCALE_TARGET_RATIO, null) == ratioKey(target)
        ) {
            prefs.getFloat(KEY_SCALE, Float.NaN)
        } else {
            Float.NaN
        }
        val hasRemembered = remembered.isFinite() && remembered >= 1f
        zoom = if (hasRemembered) remembered else defaultScale
        val label = if (hasRemembered) "记忆倍率" else "默认倍率"
        zoomValue.text = "$label  %.2f×".format(java.util.Locale.US, zoom)
    }

    private fun ratioKey(value: Double) = "%.6f".format(java.util.Locale.US, value)

    private fun scalePreferenceKey(screenRatio: Double, targetRatio: Double) =
        "scale_${ratioKey(screenRatio)}_${ratioKey(targetRatio)}"

    private fun pickPreviewImage() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(picker, REQUEST_PICK_IMAGE)
    }

    @Deprecated("Uses the platform document picker for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK) return
        val imageUri: Uri = data?.data ?: return
        val screenRatio = AspectRatioZoom.parse(currentRatioInput.text.toString()) ?: return
        ImageZoomPreview.show(this, imageUri, screenRatio, zoom) { confirmedScale ->
            val targetRatio = AspectRatioZoom.parse(targetRatioInput.text.toString()) ?: return@show
            val defaultScale = AspectRatioZoom.defaultScale(screenRatio, targetRatio).toFloat()
            zoom = confirmedScale
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putFloat(KEY_SCALE, confirmedScale)
                .putFloat(scalePreferenceKey(screenRatio, targetRatio), confirmedScale)
                .putString(KEY_ACTIVE_SCALE_KEY, scalePreferenceKey(screenRatio, targetRatio))
                .putString(KEY_SCALE_SCREEN_RATIO, ratioKey(screenRatio))
                .putString(KEY_SCALE_TARGET_RATIO, ratioKey(targetRatio))
                .putFloat(KEY_DEFAULT_SCALE, defaultScale)
                .apply()
            zoomValue.text = "记忆倍率  %.2f×".format(java.util.Locale.US, zoom)
        }
    }

    @Suppress("DEPRECATION")
    private fun detectedScreenRatio(): String {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels).toDouble()
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).toDouble()
        return "%.3f".format(java.util.Locale.US, longSide / shortSide)
    }

    private fun startZoom() {
        if (zoom < 1f) {
            zoomValue.text = "请先输入有效的屏幕比例和目标比例"
            return
        }
        if (apps.isEmpty()) {
            zoomValue.text = "没有找到可打开的应用"
            return
        }
        if (!isAccessibilityEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            accessibilityStatus.text = "请在系统设置中开启“自定义缩放”无障碍服务，然后返回本应用"
            return
        }
        val selected = apps.getOrNull(appPicker.selectedItemPosition)
        if (selected == null) {
            zoomValue.text = "请选择要打开的应用"
            return
        }
        val currentRatio = AspectRatioZoom.parse(currentRatioInput.text.toString()) ?: return
        val targetRatio = AspectRatioZoom.parse(targetRatioInput.text.toString()) ?: return
        val defaultScale = AspectRatioZoom.defaultScale(currentRatio, targetRatio).toFloat()
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_APP, selected.packageName)
            .putString(KEY_ACTIVE_SCALE_KEY, scalePreferenceKey(currentRatio, targetRatio))
            .putString(KEY_SCALE_SCREEN_RATIO, ratioKey(currentRatio))
            .putString(KEY_SCALE_TARGET_RATIO, ratioKey(targetRatio))
            .putFloat(KEY_DEFAULT_SCALE, defaultScale)
            .putFloat(scalePreferenceKey(currentRatio, targetRatio), zoom)
            .apply()
        packageManager.getLaunchIntentForPackage(selected.packageName)?.let(::startActivity)
        window.decorView.postDelayed({
            if (!ZoomAccessibilityService.startZoom(this, zoom, selected.packageName)) {
                accessibilityStatus.text = "无障碍服务尚未连接，请稍候再点一次开始缩放"
            } else {
                accessibilityStatus.text = "缩放已开启 · 悬浮窗可调节倍率或停止"
            }
        }, 350L)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ZoomAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshAccessibilityState() {
        val enabled = isAccessibilityEnabled()
        accessibilityStatus.text = if (enabled) {
            "无障碍服务已开启 · 悬浮窗空闲 3 秒后自动隐藏"
        } else {
            "需要开启无障碍服务才能缩放当前屏幕"
        }
        startButton.text = if (enabled) "开始缩放" else "开启无障碍服务"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
