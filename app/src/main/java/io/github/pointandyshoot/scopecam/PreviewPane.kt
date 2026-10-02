package io.github.pointandyshoot.scopecam

import android.content.Context
import android.graphics.*
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.min
import kotlin.math.roundToInt

/** Preview-only transform; fits the complete buffer, preserving calibration coordinates. */
class PreviewPane(context: Context) : FrameLayout(context) {
    val texture = TextureView(context)
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.MATRIX }
    private val overlay = FinderOverlay(context)
    private var bitmap: Bitmap? = null
    @Volatile private var sourceSize = Size(1280, 720)
    @Volatile private var sensorDegrees = 90
    @Volatile private var displayDegrees = 0
    private val rotationDegrees: Int get() = (sensorDegrees - displayDegrees + 360) % 360
    @Volatile private var sensorAspect = 4f / 3f
    private var contentMaxWidth = 0
    private var contentMaxHeight = 0
    var onReady: (() -> Unit)? = null
    var onLost: (() -> Unit)? = null
    var onCalibrate: ((Calibration) -> Unit)? = null
    var calibrating = false
        set(value) { field = value; overlay.calibrating = value; overlay.invalidate() }
    var finderFraction = .02f
        set(value) { field = value; overlay.fraction = value; overlay.invalidate() }
    var calibration = Calibration()
        set(value) { field = value.bounded(); updateTransform() }
    var showReticle = false
        set(value) { field = value; overlay.visibility = if (value) VISIBLE else GONE }

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture, LayoutParams(-1, -1))
        addView(image, LayoutParams(-1, -1))
        image.visibility = GONE
        addView(overlay, LayoutParams(-1, -1))
        overlay.visibility = GONE
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { updateTransform(); onReady?.invoke() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // View dimensions can change independently of the configured camera stream.
                surface.setDefaultBufferSize(sourceSize.width, sourceSize.height)
                updateTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { onLost?.invoke(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        setOnTouchListener { _, event ->
            if (!calibrating) return@setOnTouchListener false
            if (event.action == MotionEvent.ACTION_UP) {
                val area = contentRect()
                if (area.contains(event.x, event.y)) {
                    val point = Calibration((event.x - area.left) / area.width(), (event.y - area.top) / area.height())
                    onCalibrate?.invoke(sensorPoint(rotatePoint(point, -rotationDegrees), sensorAspect,
                        sourceSize.width.toFloat() / sourceSize.height).bounded())
                    performClick()
                }
            }
            true
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    val ready: Boolean get() = texture.isAvailable
    /** WRAP_CONTENT inset: its bounds follow the fitted image, inside these limits. */
    fun fitContentWithin(maxWidth: Int, maxHeight: Int) {
        require(maxWidth > 0 && maxHeight > 0)
        contentMaxWidth = maxWidth; contentMaxHeight = maxHeight
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (contentMaxWidth == 0 || contentMaxHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        fun available(spec: Int, limit: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED)
            limit else min(limit, MeasureSpec.getSize(spec))
        val availableWidth = available(widthMeasureSpec, contentMaxWidth)
        val availableHeight = available(heightMeasureSpec, contentMaxHeight)
        if (availableWidth == 0 || availableHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val fit = previewGeometry(sourceSize.width, sourceSize.height, availableWidth, availableHeight,
            sensorDegrees, displayDegrees)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(fit.contentWidth.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(fit.contentHeight.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
    }
    fun sensorAspect(value: Float) { sensorAspect = value; post { updateTransform() } }
    fun rotatePreview(sensor: Int, display: Int) {
        val changed = sensorDegrees != sensor || displayDegrees != display
        sensorDegrees = sensor; displayDegrees = display; updateTransform()
        if (changed) requestLayout()
    }
    fun surface(size: Size, sensor: Int, display: Int): Surface {
        sourceSize = size; sensorDegrees = sensor; displayDegrees = display
        val source = texture.surfaceTexture ?: error("Preview surface is not ready")
        source.setDefaultBufferSize(size.width, size.height)
        post { clearYuv(); requestLayout(); updateTransform() }
        return Surface(source)
    }
    fun showYuv(value: Bitmap, sensor: Int, display: Int) {
        val changed = sourceSize.width != value.width || sourceSize.height != value.height ||
            sensorDegrees != sensor || displayDegrees != display
        sourceSize = Size(value.width, value.height); sensorDegrees = sensor; displayDegrees = display
        bitmap = value
        value.density = Bitmap.DENSITY_NONE // Matrix coordinates are buffer pixels, not dp.
        image.setImageBitmap(value); image.visibility = VISIBLE
        // Displayed bitmaps are collected after RenderThread releases them; recycling
        // them on a handler can race a queued display-list draw.
        updateTransform()
        if (changed) requestLayout()
    }
    fun clearYuv() {
        image.visibility = GONE; image.setImageDrawable(null)
        bitmap = null
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); updateTransform() }
    private fun contentRect(): RectF {
        if (width == 0 || height == 0) return RectF()
        val geometry = geometry()
        val w = geometry.contentWidth; val h = geometry.contentHeight
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
    private fun geometry() = previewGeometry(sourceSize.width, sourceSize.height, width, height,
        sensorDegrees, displayDegrees)
    fun diagnostics(): String = "${if (image.visibility == VISIBLE) "YUV" else "PRIVATE"} · buffer=$sourceSize · view=${width}x$height · sensor=$sensorDegrees° · display=$displayDegrees° · relative=$rotationDegrees°"
    private fun updateTransform() {
        if (width == 0 || height == 0) return
        val area = contentRect()
        val geometry = geometry()
        // PRIVATE already has sensor rotation. Undo its stretch using those rotated
        // dimensions, then compensate only display rotation. Raw YUV needs both.
        texture.setTransform(Matrix().apply { setValues(geometry.textureMatrix) })
        image.imageMatrix = Matrix().apply { setValues(geometry.rawMatrix) }
        overlay.area = area
        overlay.point = rotatePoint(previewPoint(calibration, sensorAspect, sourceSize.width.toFloat() / sourceSize.height), rotationDegrees)
        overlay.fraction = finderFraction
        overlay.invalidate()
    }
    private class FinderOverlay(context: Context) : View(context) {
        var area = RectF()
        var point = Calibration()
        var fraction = .02f
        var calibrating = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(109, 220, 207); style = Paint.Style.STROKE; strokeWidth = 2f }
        override fun onDraw(canvas: Canvas) {
            val x = area.left + point.x * area.width(); val y = area.top + point.y * area.height()
            val halfW = (area.width() * fraction / 2).coerceAtLeast(2f)
            val halfH = (area.height() * fraction / 2).coerceAtLeast(2f)
            canvas.drawRect(x - halfW, y - halfH, x + halfW, y + halfH, paint)
            canvas.drawLine(x - halfW - 9, y, x - halfW - 3, y, paint)
            canvas.drawLine(x + halfW + 3, y, x + halfW + 9, y, paint)
            canvas.drawLine(x, y - halfH - 9, x, y - halfH - 3, paint)
            canvas.drawLine(x, y + halfH + 3, x, y + halfH + 9, paint)
            if (calibrating) { paint.style = Paint.Style.FILL; paint.textSize = 30f; canvas.drawText("Tap centred subject", 12f, 36f, paint); paint.style = Paint.Style.STROKE }
        }
    }
}
