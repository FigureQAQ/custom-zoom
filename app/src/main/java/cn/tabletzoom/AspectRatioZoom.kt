package cn.tabletzoom

object AspectRatioZoom {
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
}
