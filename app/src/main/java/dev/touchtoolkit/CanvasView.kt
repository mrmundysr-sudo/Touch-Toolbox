package dev.touchtoolkit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * The mockup canvas.
 *
 * Camera state (scale / offset) is kept strictly apart from object geometry so
 * that zoom, pan and full-view can never mutate the mockup or its edit history.
 */
class CanvasView(context: Context) : View(context) {

    var project = Project()
        set(value) {
            field = value
            onProjectReplaced()
        }

    var mode: Mode = Mode.NONE
        set(value) {
            field = value
            pendingLineStart = null
            invalidate()
            onUiChanged?.invoke()
        }

    /**
     * True while the full editing chrome is on screen.
     *
     * Setting this to false only minimises the chrome. The active mode, the
     * selection and the zoom all stay exactly as they were, and the current
     * tool keeps working across the whole canvas, including the strip the
     * panel normally covers.
     */
    var uiVisible = true
        set(value) {
            field = value
            invalidate()
        }

    /** True while the chrome is minimised but editing stays fully live. */
    val chromeMinimized: Boolean get() = !uiVisible
    private var persistentGroupEdit = false

    var onUiChanged: (() -> Unit)? = null
    var onEditBefore: ((ArrayList<Item>) -> Unit)? = null
    var onToggleUi: (() -> Unit)? = null
    var onToast: ((String) -> Unit)? = null
    var onMeasureMark: ((Item) -> Unit)? = null

    var background: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    val selection = LinkedHashSet<Long>()
    private var soloId: Long? = null
    var multiSelect = false
        set(value) {
            field = value
            invalidate()
            onUiChanged?.invoke()
        }

    // ---- camera -----------------------------------------------------------
    val camera = Camera()
    private val scale get() = camera.scale
    private val offX get() = camera.offX
    private val offY get() = camera.offY
    private var fitted = false

    // ---- gesture bookkeeping ---------------------------------------------
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var gestureMoved = false
    private var gesturePushedHistory = false
    private var downX = 0f
    private var downY = 0f

    // A tap that begins on the floating chrome is never an edit on the canvas.
    private var pendingReveal = false
    /** True when the gesture began on a chrome control, so no drag may edit the canvas. */
    private var gestureOnChrome = false
    /** The mode in force when the gesture began, so a mid-gesture mode change cannot misfire. */
    private var modeAtDown = Mode.NONE

    private var dragging = false
    private var panning = false
    private var zooming = false
    private var lastZoomY = 0f
    private var edgeRevealDown = false

    private var shapeStartW = 0f
    private var shapeStartH = 0f
    private var shaping = false

    /** First tap of a point-to-point line, in world coordinates. */
    private var pendingLineStart: Pair<Float, Float>? = null

    // ---- paints -----------------------------------------------------------
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#FF2B6CB0")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#332B6CB0")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD32F2F")
    }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF0B57D0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val numberHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val surfacePaint = Paint().apply { color = Color.WHITE }
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF4C9AFF")
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6262A32")
    }
    private val controlStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#994C9AFF")
    }
    private val controlText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF4C9AFF")
    }
    private val rect = RectF()

    // ---- world-derived sizes (world units) --------------------------------
    private val unit get() = project.worldW
    val strokeWidth get() = unit * 0.0045f
    private val dotR get() = unit * 0.014f
    private val numberSize get() = unit * 0.055f
    /** Scale used for the next numbered mark; the Number tool cycles full/half. */
    var numberScale: Float = 1f
    private val minSide get() = unit * MINIMUM_BOX_SIDE_FRACTION
    private val targetSlopPx get() = 28f * density

    // ---- floating camera controls (screen space) --------------------------
    /** Space at the bottom of the view occupied by the tool chrome. */
    var bottomInsetPx = 0f
        private set

    /** Called by the host whenever the tool chrome is shown or hidden. */
    fun setBottomInset(px: Float) {
        val wasFitted = fitted
        bottomInsetPx = px
        // The chrome is an overlay, so it never changes the fitted camera.
        if (!wasFitted) invalidate()
    }

    // Control geometry is derived from the view's current size, so the temporary
    // tool chrome always repositions to fit the screen shape the user is holding.
    private val edgeRevealHeight get() = 28f * density

    init {
        // Edge to edge, with no visible boundary or frame around the work surface.
        setBackgroundColor(Color.WHITE)
    }

    private fun onProjectReplaced() {
        fitted = false
        selection.clear()
        soloId = null
        pendingLineStart = null
        invalidate()
    }

    fun clearSelection() {
        selection.clear()
        soloId = null
        invalidate()
        onUiChanged?.invoke()
    }

    /** The workspace surface is locked: it cannot be selected, moved, resized or reshaped. */
    fun isSurfaceGeometryLocked(): Boolean = true

    // =======================================================================
    // Camera — never touches object geometry or history
    // =======================================================================

    /**
     * One tap: the whole work surface fitted and centred. This is also the
     * minimum zoom, so the surface always fills the screen.
     */
    fun fullView() {
        camera.fit(project.worldW, project.worldH, width, height)
        fitted = true
        invalidate()
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        camera.zoomBy(factor, focusX, focusY)
        invalidate()
    }

    /** The zoom at which the whole surface fits the current view. */
    val fittedScale: Float get() = camera.minScale

    /**
     * The phone was rotated. The work surface and every mark keep their world
     * coordinates, so nothing rotates, flips, stretches or shifts. Only the
     * camera is adapted: the zoom is preserved (raising the floor if the
     * fitted view grew) and the same world point stays in view.
     */
    fun onViewportResized(oldW: Int, oldH: Int) {
        if (oldW <= 0 || oldH <= 0) {
            if (!fitted) fullView()
            return
        }
        val focusX = camera.worldX(oldW / 2f)
        val focusY = camera.worldY(oldH / 2f)
        val wasFitted = fitted && camera.scale <= camera.minScale * 1.001f

        camera.setViewport(project.worldW, project.worldH, width, height)
        if (wasFitted) {
            // Still at the fitted view, so re-fit for the new screen shape.
            camera.fit(project.worldW, project.worldH, width, height)
        } else {
            camera.centreOn(focusX, focusY, width, height)
        }
        fitted = true
        invalidate()
    }

    private fun toWorldX(px: Float) = camera.worldX(px)
    private fun toWorldY(py: Float) = camera.worldY(py)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onViewportResized(oldw, oldh)
    }

    // =======================================================================
    // Selection
    // =======================================================================

    fun selectedBox(): Item? {
        val id = soloId ?: return null
        val item = project.byId(id)
        return if (item?.type == ItemType.BOX) item else null
    }

    fun selectedItems(): List<Item> = selection.mapNotNull { project.byId(it) }

    fun selectOnly(id: Long?) {
        persistentGroupEdit = false
        selection.clear()
        soloId = id
        if (id != null) selection.add(id)
        invalidate()
        onUiChanged?.invoke()
    }

    fun selectAll() {
        persistentGroupEdit = true
        selection.clear()
        project.items.forEach { selection.add(it.id) }
        soloId = selection.lastOrNull()
        invalidate()
        onUiChanged?.invoke()
    }

    fun selectLastMarkup() {
        project.items.lastOrNull()?.let { selectOnly(it.id) }
    }

    private fun hitTest(px: Float, py: Float): Item? {
        val pad = targetSlopPx / scale
        for (i in project.items.indices.reversed()) {
            if (hits(project.items[i], px, py, pad)) return project.items[i]
        }
        return null
    }

    private fun hits(item: Item, px: Float, py: Float, pad: Float): Boolean = when (item.type) {
        ItemType.BOX -> px >= item.left() - pad && px <= item.right() + pad &&
                py >= item.top() - pad && py <= item.bottom() + pad
        ItemType.LINE -> distToSegment(px, py, item.x, item.y, item.x2, item.y2) <= pad
        ItemType.DOT -> hypot(px - item.x, py - item.y) <= dotR + pad
        ItemType.NUMBER -> {
            val textSize = numberSize * item.numberScale
            numberPaint.textSize = textSize
            val halfW = numberPaint.measureText(item.number.toString()) / 2f
            abs(px - item.x) <= halfW + pad && abs(py - item.y) <= textSize * 0.75f + pad
        }
        ItemType.MEASURE -> hypot(px - item.x, py - item.y) <= unit * 0.045f + pad
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    private fun recordHistory() {
        onEditBefore?.invoke(project.snapshot())
    }

    // =======================================================================
    // Touch
    // =======================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val px = event.x
        val py = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = px
                downY = py
                gestureMoved = false
                gesturePushedHistory = false
                pendingReveal = false
                gestureOnChrome = false
                modeAtDown = mode

                edgeRevealDown = false

                if (!uiVisible) {
                    edgeRevealDown = py >= height - edgeRevealHeight
                    // Reserve the bottom-edge start zone for the intentional
                    // tray reveal gesture; never let it also trigger a tool.
                    gestureOnChrome = edgeRevealDown
                    if (!edgeRevealDown) beginGesture(px, py)
                    return true
                }
                // The chosen tool owns the whole canvas, chrome visible or not.
                beginGesture(px, py)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureMoved && hypot(px - downX, py - downY) > slop) {
                    gestureMoved = true
                    // A real drag is never a tap, so drop any pending tap action.
                    pendingReveal = false
                }
                if (zooming) {
                    // Continuous, stoppable, reversible, no coarse jumps.
                    val dy = py - lastZoomY
                    lastZoomY = py
                    if (dy != 0f) zoomBy(exp(-dy * 0.0035f), width / 2f, height / 2f)
                    return true
                }
                if (gestureMoved && !gestureOnChrome) moveGesture(px, py)
                if (!uiVisible && edgeRevealDown && py < height - edgeRevealHeight - slop) {
                    onToggleUi?.invoke()
                    edgeRevealDown = false
                    return true
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (zooming) {
                    zooming = false
                    return true
                }
                val tapped = !gestureMoved && event.actionMasked == MotionEvent.ACTION_UP

                // A gesture that began on the reserved reveal strip (or on old
                // floating chrome) never edits the canvas and never toggles twice.
                if (!pendingReveal && !gestureOnChrome) {
                    endGesture(px, py, tapped)
                }
                // With no tool chosen an empty tap is not an edit, so it toggles
                // the chrome. With a tool chosen, every tap belongs to that tool,
                // so the panel is only hidden via the SHOW/HIDE control.
                return true
            }
        }
        return true
    }

    private fun beginGesture(px: Float, py: Float) {
        when (mode) {
            Mode.SHAPE -> {
                val box = selectedBox()
                if (box != null) {
                    shapeStartW = box.w
                    shapeStartH = box.h
                    // Only shape when the finger starts on the box, so a drag
                    // beginning on empty canvas cannot resize it by surprise.
                    val pad = targetSlopPx / scale
                    shaping = toWorldX(px) >= box.left() - pad && toWorldX(px) <= box.right() + pad &&
                            toWorldY(py) >= box.top() - pad && toWorldY(py) <= box.bottom() + pad
                } else {
                    shaping = false
                }
            }
            Mode.MOVE -> {
                val hit = hitTest(toWorldX(px), toWorldY(py))
                if (hit != null) {
                    if (persistentGroupEdit || multiSelect) {
                        if (selection.contains(hit.id)) {
                            selection.remove(hit.id)
                        } else {
                            selection.add(hit.id)
                        }
                        soloId = selection.lastOrNull()
                        invalidate()
                        onUiChanged?.invoke()
                    } else {
                        if (selection.contains(hit.id)) {
                            dragging = true
                        } else {
                            selection.add(hit.id)
                            soloId = hit.id
                            dragging = true
                            onUiChanged?.invoke()
                        }
                    }
                    if (persistentGroupEdit || multiSelect) dragging = selection.contains(hit.id)
                } else if (selection.isNotEmpty()) {
                    // Move uses the current selection even when the finger
                    // starts in open canvas space.
                    dragging = true
                } else if (!persistentGroupEdit && !multiSelect) {
                    dragging = false
                }
            }
            Mode.SELECT -> {
                hitTest(toWorldX(px), toWorldY(py))?.let { selectOnly(it.id) }
            }
            Mode.PAN -> panning = camera.scale > camera.minScale * 1.001f
            Mode.MAGNIFY -> { zooming = true; lastZoomY = py }
            Mode.NONE -> hitTest(toWorldX(px), toWorldY(py))?.let { selectOnly(it.id) }
            else -> Unit
        }
    }

    private fun moveGesture(px: Float, py: Float) {
        when (mode) {
            Mode.SHAPE -> {
                val box = selectedBox() ?: return
                if (!shaping) return
                if (!gesturePushedHistory) {
                    recordHistory()
                    gesturePushedHistory = true
                }
                val dx = toWorldX(px) - toWorldX(downX)
                val dy = toWorldY(py) - toWorldY(downY)
                // Centre stays anchored; 2x so the dragged edge tracks the finger.
                box.w = ShapeMath.width(shapeStartW, dx, minSide)
                box.h = ShapeMath.height(shapeStartH, dy, minSide)
                invalidate()
                onUiChanged?.invoke()
            }
            Mode.MOVE -> {
                if (!dragging) return
                if (!gesturePushedHistory) {
                    recordHistory()
                    gesturePushedHistory = true
                }
                val dx = toWorldX(px) - toWorldX(downX)
                val dy = toWorldY(py) - toWorldY(downY)
                selectedItems().forEach { it.translate(dx, dy) }
                downX = px
                downY = py
                invalidate()
                onUiChanged?.invoke()
            }
            Mode.PAN -> {
                if (!panning) return
                // Pan is a pure translation of the uniformly scaled surface.
                // Apply both axes from the same finger delta and clamp each
                // independently to the original work-area edges.
                val dx = px - downX
                val dy = py - downY
                camera.panBounded(dx, dy, project.worldW, project.worldH, width, height)
                downX = px
                downY = py
                invalidate()
            }
            Mode.MAGNIFY -> {
                val dy = py - lastZoomY
                lastZoomY = py
                if (dy != 0f) zoomBy(exp(-dy * 0.0035f), width / 2f, height / 2f)
            }
            else -> Unit
        }
    }

    private fun endGesture(px: Float, py: Float, isUp: Boolean) {
        val tapped = !gestureMoved && isUp
        when (mode) {
            Mode.SHAPE -> {
                shaping = false
                val box = selectedBox()
                val outside = box == null ||
                        toWorldX(px) < box.left() || toWorldX(px) > box.right() ||
                        toWorldY(py) < box.top() || toWorldY(py) > box.bottom()
                if (tapped && outside) {
                    selectOnly(null)
                    mode = Mode.NONE
                    onToast?.invoke("Shape finished")
                }
            }
            Mode.MOVE -> dragging = false
            Mode.SELECT -> Unit
            Mode.PAN -> panning = false
            // With no tool chosen an empty tap is not an edit, so it toggles the
            // chrome. With a tool chosen every tap belongs to that tool.
            Mode.NONE -> Unit
            // Magnification persists after release, so lifting the finger changes nothing.
            Mode.MAGNIFY -> Unit
            Mode.LINE -> if (tapped) {
                val start = pendingLineStart
                if (start == null) {
                    pendingLineStart = toWorldX(px) to toWorldY(py)
                    invalidate()
                    onToast?.invoke("Now tap the end point")
                } else {
                    recordHistory()
                    project.add(
                        Item(type = ItemType.LINE, x = start.first, y = start.second,
                            x2 = toWorldX(px), y2 = toWorldY(py))
                    )
                    pendingLineStart = null
                    invalidate()
                    onUiChanged?.invoke()
                }
            }
            Mode.DOT -> if (tapped) {
                recordHistory()
                project.add(Item(type = ItemType.DOT, x = toWorldX(px), y = toWorldY(py)))
                invalidate()
                onUiChanged?.invoke()
            }
            Mode.NUMBER -> if (tapped) {
                recordHistory()
                project.add(
                    Item(type = ItemType.NUMBER, x = toWorldX(px), y = toWorldY(py),
                        number = project.nextNumber++, numberScale = numberScale)
                )
                invalidate()
                onUiChanged?.invoke()
            }
            Mode.MEASURE -> if (tapped) {
                val x = toWorldX(px)
                val y = toWorldY(py)
                val existing = project.items.asReversed().firstOrNull {
                    it.type == ItemType.MEASURE && hits(it, x, y, targetSlopPx / scale)
                }
                if (existing != null) {
                    onMeasureMark?.invoke(existing)
                } else {
                    recordHistory()
                    val mark = project.add(Item(type = ItemType.MEASURE, x = x, y = y,
                        number = project.nextMeasure++))
                    invalidate()
                    onUiChanged?.invoke()
                    onMeasureMark?.invoke(mark)
                }
            }
        }
    }

    // =======================================================================
    // Drawing
    // =======================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!fitted) fullView()
        drawScene(canvas, scale, offX, offY, width.toFloat(), height.toFloat())
        // Selection and pending-line feedback are part of editing, not chrome, so
        // they stay visible while the panel is minimised. That is what makes
        // "keep editing with the chrome hidden" workable.
        drawSelectionOverlay(canvas, scale, offX, offY)
        drawPendingLine(canvas, scale, offX, offY)
        // Editing chrome is owned by the host toolbar; never draw floating controls here.
    }

    /** Draws only the mockup: the locked surface, background and marks. No chrome. */
    fun drawScene(canvas: Canvas, s: Float, ox: Float, oy: Float, viewW: Float, viewH: Float) {
        // The whole view is the work surface: no dark surround, no page edge.
        canvas.drawRect(0f, 0f, viewW, viewH, surfacePaint)

        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)

        // Only visible when zoomed out far enough for the surface not to fill the view.
        canvas.drawRect(0f, 0f, project.worldW, project.worldH, surfacePaint)

        background?.let { bmp ->
            // Filter only when minifying; magnified artwork keeps its true pixels.
            bgPaint.isFilterBitmap = s < 1f
            val rotation = project.backgroundRotation
            canvas.save()
            when (rotation) {
                90 -> { canvas.translate(project.worldW, 0f); canvas.rotate(90f) }
                180 -> { canvas.rotate(180f, project.worldW / 2f, project.worldH / 2f) }
                270 -> { canvas.translate(0f, project.worldH); canvas.rotate(270f) }
            }
            val sourceW = if (rotation == 90 || rotation == 270) project.worldH else project.worldW
            val sourceH = if (rotation == 90 || rotation == 270) project.worldW else project.worldH
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, sourceW, sourceH), bgPaint)
            canvas.restore()
        }

        val sw = strokeWidth
        strokePaint.strokeWidth = sw
        dotRingPaint.strokeWidth = sw * 0.9f
        numberPaint.textSize = numberSize
        numberHaloPaint.textSize = numberSize
        numberHaloPaint.strokeWidth = numberSize * 0.18f

        for (item in project.items) {
            when (item.type) {
                ItemType.BOX -> {
                    rect.set(item.left(), item.top(), item.right(), item.bottom())
                    canvas.drawRect(rect, fillPaint)
                    canvas.drawRect(rect, strokePaint)
                }
                ItemType.LINE -> canvas.drawLine(item.x, item.y, item.x2, item.y2, strokePaint)
                ItemType.DOT -> {
                    canvas.drawCircle(item.x, item.y, dotR, dotPaint)
                    canvas.drawCircle(item.x, item.y, dotR, dotRingPaint)
                }
                ItemType.NUMBER -> {
                    val textSize = numberSize * item.numberScale
                    numberPaint.textSize = textSize
                    numberHaloPaint.textSize = textSize
                    numberHaloPaint.strokeWidth = textSize * 0.18f
                    val baseline = item.y + textSize * 0.35f
                    canvas.drawText(item.number.toString(), item.x, baseline, numberHaloPaint)
                    canvas.drawText(item.number.toString(), item.x, baseline, numberPaint)
                }
                ItemType.MEASURE -> {
                    numberPaint.textSize = numberSize
                    val r = unit * 0.016f
                    canvas.drawLine(item.x - r, item.y - r, item.x + r, item.y + r, strokePaint)
                    canvas.drawLine(item.x - r, item.y + r, item.x + r, item.y - r, strokePaint)
                    canvas.drawText(item.number.toString(), item.x + r * 1.5f,
                        item.y - r, numberPaint)
                    if (item.w > 0f && item.h > 0f) {
                        canvas.drawRect(item.x - item.w / 2, item.y - item.h / 2,
                            item.x + item.w / 2, item.y + item.h / 2, strokePaint)
                    }
                }
            }
        }
        canvas.restore()
    }

    private fun drawSelectionOverlay(canvas: Canvas, s: Float, ox: Float, oy: Float) {
        if (selection.isEmpty()) return
        val sw = strokeWidth
        selPaint.strokeWidth = sw * 1.4f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        for (item in selectedItems()) {
            when (item.type) {
                ItemType.BOX -> rect.set(item.left(), item.top(), item.right(), item.bottom())
                ItemType.LINE -> rect.set(
                    minOf(item.x, item.x2), minOf(item.y, item.y2),
                    maxOf(item.x, item.x2), maxOf(item.y, item.y2)
                )
                else -> rect.set(
                    item.x - unit * 0.05f, item.y - unit * 0.05f,
                    item.x + unit * 0.05f, item.y + unit * 0.05f
                )
            }
            rect.inset(-sw * 3f, -sw * 3f)
            canvas.drawRect(rect, selPaint)
        }
        canvas.restore()
    }

    private fun drawPendingLine(canvas: Canvas, s: Float, ox: Float, oy: Float) {
        val start = pendingLineStart ?: return
        pendingPaint.strokeWidth = 2f * density
        val cx = start.first * s + ox
        val cy = start.second * s + oy
        canvas.drawCircle(cx, cy, 13f * density, pendingPaint)
        canvas.drawCircle(cx, cy, 4f * density, dotPaint)
    }

}
