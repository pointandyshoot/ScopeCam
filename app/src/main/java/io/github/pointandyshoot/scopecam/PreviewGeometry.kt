package io.github.pointandyshoot.scopecam

import kotlin.math.min

/** Rear-camera geometry. Camera2 already applies sensor orientation to PRIVATE textures;
 * YUV pixels are still in sensor coordinates. Both must end up in the same fitted area. */
data class PreviewGeometry(
    val contentWidth: Float,
    val contentHeight: Float,
    val rawMatrix: FloatArray,
    val textureMatrix: FloatArray
)

fun previewGeometry(bufferWidth: Int, bufferHeight: Int, viewWidth: Int, viewHeight: Int,
                    sensorDegrees: Int, displayDegrees: Int): PreviewGeometry {
    require(bufferWidth > 0 && bufferHeight > 0 && viewWidth > 0 && viewHeight > 0)
    val relative = (sensorDegrees - displayDegrees + 360) % 360
    val rotatedWidth = if (relative % 180 == 0) bufferWidth else bufferHeight
    val rotatedHeight = if (relative % 180 == 0) bufferHeight else bufferWidth
    val scale = min(viewWidth.toFloat() / rotatedWidth, viewHeight.toFloat() / rotatedHeight)
    val nativeWidth = if (sensorDegrees % 180 == 0) bufferWidth else bufferHeight
    val nativeHeight = if (sensorDegrees % 180 == 0) bufferHeight else bufferWidth
    return PreviewGeometry(rotatedWidth * scale, rotatedHeight * scale,
        centredTransform(bufferWidth / 2f, bufferHeight / 2f, viewWidth / 2f, viewHeight / 2f,
            scale, scale, relative),
        centredTransform(viewWidth / 2f, viewHeight / 2f, viewWidth / 2f, viewHeight / 2f,
            nativeWidth * scale / viewWidth, nativeHeight * scale / viewHeight, -displayDegrees))
}

/** Scale first, then rotate; exact quarter turns avoid floating-point corner drift. */
private fun centredTransform(fromX: Float, fromY: Float, toX: Float, toY: Float,
                              sx: Float, sy: Float, degrees: Int): FloatArray {
    val (c, s) = when ((degrees + 360) % 360) {
        90 -> 0f to 1f
        180 -> -1f to 0f
        270 -> 0f to -1f
        else -> 1f to 0f
    }
    val a = c * sx; val b = -s * sy; val d = s * sx; val e = c * sy
    return floatArrayOf(a, b, toX - a * fromX - b * fromY,
        d, e, toY - d * fromX - e * fromY, 0f, 0f, 1f)
}
