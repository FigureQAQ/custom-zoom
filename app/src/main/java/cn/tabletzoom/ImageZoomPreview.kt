package cn.tabletzoom

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

object ImageZoomPreview {
    private const val MIN_SCALE = 1f
    private const val MAX_SCALE = 5f
    private const val SCALE_STEP = 0.05f
    private const val MAX_DECODE_EDGE = 1920
    private const val PAN_STEP_PX = 1f

    data class Selection(val scale: Float, val panXFraction: Float, val panYFraction: Float)

    fun show(
        activity: Activity,
        uri: Uri,
        screenRatio: Double,
        initialScale: Float,
        initialPanXFraction: Float,
        initialPanYFraction: Float,
        defaultPanXFraction: Float,
        defaultPanYFraction: Float,
        onApply: (Selection) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val display = activity.resources.displayMetrics
        val density = display.density
        val displayAspect = if (display.widthPixels >= display.heightPixels) {
            screenRatio
        } else {
            1.0 / screenRatio
        }.takeIf { it.isFinite() && it > 0.0 } ?: 1.5

        val dialogWidthDp = min(display.widthPixels / density * 0.92f, 900f)
        val maxImageHeightDp = display.heightPixels / density * 0.58f
        val imageWidthDp = min(dialogWidthDp - 32f, maxImageHeightDp * displayAspect.toFloat())
        val imageHeightDp = imageWidthDp / displayAspect.toFloat()
        val imageWidthPx = (imageWidthDp * density).toInt().coerceAtLeast(1)
        val imageHeightPx = (imageHeightDp * density).toInt().coerceAtLeast(1)

        val bitmap = try {
            decodeOrientedBitmap(activity, uri, imageWidthPx, imageHeightPx)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            Toast.makeText(activity, "无法读取这张图片", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(activity)
        var scale = initialScale.coerceIn(MIN_SCALE, MAX_SCALE)
        var panX = initialPanXFraction * imageWidthPx
        var panY = initialPanYFraction * imageHeightPx
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 12))
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(activity).apply {
            text = "缩放预览"
            textSize = 18f
            setTextColor(0xFF20252B.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val scaleLabel = TextView(activity).apply {
            textSize = 14f
            setTextColor(0xFF4C5963.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 5), 0, dp(activity, 9))
        }
        root.addView(scaleLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val previewFrame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(0xFF15191D.toInt())
                cornerRadius = dp(activity, 4).toFloat()
            }
            clipToOutline = true
        }
        val image = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.MATRIX
            setLayerType(ImageView.LAYER_TYPE_HARDWARE, null)
        }
        previewFrame.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(previewFrame, LinearLayout.LayoutParams(imageWidthPx, imageHeightPx))

        fun updatePreview() {
            image.post {
                if (image.width <= 0 || image.height <= 0) return@post
                val cover = max(
                    image.width.toFloat() / bitmap.width,
                    image.height.toFloat() / bitmap.height
                )
                val effectiveScale = cover * scale
                val renderedWidth = bitmap.width * effectiveScale
                val renderedHeight = bitmap.height * effectiveScale
                val maxPanX = max(0f, (renderedWidth - image.width) / 2f)
                val maxPanY = max(0f, (renderedHeight - image.height) / 2f)
                panX = panX.coerceIn(-maxPanX, maxPanX)
                panY = panY.coerceIn(-maxPanY, maxPanY)
                val x = panX / image.width * 100f
                val y = panY / image.height * 100f
                scaleLabel.text = "%.2f×  ·  X %+.2f%%  Y %+.2f%%".format(java.util.Locale.US, scale, x, y)
                val matrix = Matrix().apply {
                    setScale(effectiveScale, effectiveScale)
                    postTranslate(
                        (image.width - renderedWidth) / 2f + panX,
                        (image.height - renderedHeight) / 2f + panY
                    )
                }
                image.imageMatrix = matrix
            }
        }

        var lastTouchX = 0f
        var lastTouchY = 0f
        image.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    updatePreview()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        val panControls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 2), 0, dp(activity, 2))
        }
        fun moveButton(label: String, dx: Float, dy: Float, description: String) = Button(activity).apply {
            text = label
            contentDescription = description
            setOnClickListener {
                panX += dx
                panY += dy
                updatePreview()
            }
        }
        val panButtonSize = dp(activity, 48)
        panControls.addView(moveButton("←", -PAN_STEP_PX, 0f, "照片向左微调 1 像素"), LinearLayout.LayoutParams(panButtonSize, panButtonSize))
        panControls.addView(moveButton("↑", 0f, -PAN_STEP_PX, "照片向上微调 1 像素"), LinearLayout.LayoutParams(panButtonSize, panButtonSize))
        panControls.addView(Button(activity).apply {
            text = "默认位置"
            contentDescription = "恢复示例图校准位置"
            setOnClickListener {
                panX = defaultPanXFraction * image.width
                panY = defaultPanYFraction * image.height
                updatePreview()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, panButtonSize))
        panControls.addView(moveButton("↓", 0f, PAN_STEP_PX, "照片向下微调 1 像素"), LinearLayout.LayoutParams(panButtonSize, panButtonSize))
        panControls.addView(moveButton("→", PAN_STEP_PX, 0f, "照片向右微调 1 像素"), LinearLayout.LayoutParams(panButtonSize, panButtonSize))
        root.addView(panControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 10), 0, dp(activity, 6))
        }
        fun adjustButton(label: String, delta: Float, description: String) = Button(activity).apply {
            text = label
            contentDescription = description
            setOnClickListener {
                scale = (scale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
                updatePreview()
            }
        }
        controls.addView(adjustButton("−", -SCALE_STEP, "缩小预览"))
        controls.addView(adjustButton("+", SCALE_STEP, "放大预览"))
        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(Button(activity).apply {
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(activity).apply {
            text = "使用此倍率"
            setOnClickListener {
                onApply(Selection(
                    scale,
                    panX / image.width.coerceAtLeast(1),
                    panY / image.height.coerceAtLeast(1)
                ))
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (dialogWidthDp * density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        updatePreview()
    }

    private fun decodeOrientedBitmap(activity: Activity, uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        val bitmap = BitmapFactory.Options().run {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, this) }
        } ?: return null

        val orientation = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(270f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (transform.isIdentity) return bitmap
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun calculateSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (max(width / (sample * 2), height / (sample * 2)) >= MAX_DECODE_EDGE) {
            sample *= 2
        }
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()
}
