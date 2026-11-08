package cn.tabletzoom

import kotlin.math.max

data class MagnificationCenter(val x: Float, val y: Float)

object MagnificationPan {
    fun center(width: Float, height: Float, scale: Float, panXFraction: Float, panYFraction: Float): MagnificationCenter {
        if (scale <= 1f || width <= 0f || height <= 0f) return MagnificationCenter(width / 2f, height / 2f)
        val factor = -1f / scale
        return MagnificationCenter(
            (width / 2f + panXFraction * width * factor).coerceIn(0f, max(0f, width)),
            (height / 2f + panYFraction * height * factor).coerceIn(0f, max(0f, height))
        )
    }
}
