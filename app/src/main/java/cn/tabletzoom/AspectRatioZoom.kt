package cn.tabletzoom

object AspectRatioZoom {
    private const val TABLET_RATIO = 3.0 / 2.0
    private const val VIDEO_RATIO = 4.0 / 3.0
    private const val TABLET_DEFAULT_ZOOM = 1.243
    private const val TABLET_DEFAULT_PAN_Y = -0.0167
    private val fraction = Regex("^(\\d+(?:\\.\\d+)?)\\s*[:/比]\\s*(\\d+(?:\\.\\d+)?)$")

    fun parse(value: String): Double? {
        val text = value.trim()
        val match = fraction.matchEntire(text)
        val ratio = if (match != null) {
            val numerator = match.groupValues[1].toDoubleOrNull() ?: return null
            val denominator = match.groupValues[2].toDoubleOrNull() ?: return null
            if (denominator == 0.0) return null
            numerator / denominator
        } else {
            text.toDoubleOrNull() ?: return null
        }
        return ratio.takeIf { it.isFinite() && it in 0.2..10.0 }
    }

    fun coverScale(screenRatio: Double, targetRatio: Double): Double {
        require(screenRatio.isFinite() && screenRatio > 0.0)
        require(targetRatio.isFinite() && targetRatio > 0.0)
        return maxOf(screenRatio / targetRatio, targetRatio / screenRatio)
    }

    fun defaultScale(screenRatio: Double, targetRatio: Double): Double {
        require(screenRatio.isFinite() && screenRatio > 0.0)
        require(targetRatio.isFinite() && targetRatio > 0.0)
        return if (
            kotlin.math.abs(screenRatio - TABLET_RATIO) < 0.000001 &&
            kotlin.math.abs(targetRatio - VIDEO_RATIO) < 0.000001
        ) TABLET_DEFAULT_ZOOM else coverScale(screenRatio, targetRatio)
    }

    fun defaultPanY(screenRatio: Double, targetRatio: Double): Double = if (
        kotlin.math.abs(screenRatio - TABLET_RATIO) < 0.000001 &&
        kotlin.math.abs(targetRatio - VIDEO_RATIO) < 0.000001
    ) TABLET_DEFAULT_PAN_Y else 0.0
}
