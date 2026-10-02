package dev.touchtoolkit

/** Minimum box width and height as a fraction of canvas width. */
const val MINIMUM_BOX_SIDE_FRACTION = 0.01f

/**
 * Viewport transform. Deliberately separate from [Project] so that camera
 * changes can never touch object geometry or markup positions.
 *
 * [minScale] is the fitted view: the whole work surface visible. Zooming out
 * past it is impossible, so the surface always fills the screen.
 */
class Camera {
    var scale = 1f
        private set
    var offX = 0f
        private set
    var offY = 0f
        private set

    var minScale = 0.04f
        private set
    var maxScale = 40f

    fun worldX(px: Float) = (px - offX) / scale
    fun worldY(py: Float) = (py - offY) / scale
    fun screenX(wx: Float) = wx * scale + offX
    fun screenY(wy: Float) = wy * scale + offY

    /** The scale at which the whole work surface fits inside the view. */
    fun fitScale(worldW: Float, worldH: Float, viewW: Int, viewH: Int, margin: Float = 1f): Float {
        if (viewW <= 0 || viewH <= 0 || worldW <= 0f || worldH <= 0f) return minScale
        return minOf(viewW / worldW, viewH / worldH) * margin
    }

    /**
     * Recompute how far out the user may zoom for a view of this size, without
     * disturbing the current zoom. Used when the phone rotates.
     */
    fun setViewport(worldW: Float, worldH: Float, viewW: Int, viewH: Int) {
        minScale = fitScale(worldW, worldH, viewW, viewH)
        maxScale = maxOf(minScale * 40f, 40f)
        if (scale < minScale) scale = minScale
    }

    /** Fit the whole world into the view, centred. This is the starting view. */
    fun fit(worldW: Float, worldH: Float, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        setViewport(worldW, worldH, viewW, viewH)
        scale = minScale
        centre(worldW, worldH, viewW, viewH)
    }

    /** Centres the world in a view of this size without changing the zoom. */
    fun centre(worldW: Float, worldH: Float, viewW: Int, viewH: Int) {
        offX = (viewW - worldW * scale) / 2f
        offY = (viewH - worldH * scale) / 2f
    }

    /** Centres the view on a world point without changing the zoom. */
    fun centreOn(worldX: Float, worldY: Float, viewW: Int, viewH: Int) {
        offX = viewW / 2f - worldX * scale
        offY = viewH / 2f - worldY * scale
    }

    /** Scale about a screen-space focus point, which stays visually fixed. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        if (next == scale) return
        val ratio = next / scale
        offX = focusX - (focusX - offX) * ratio
        offY = focusY - (focusY - offY) * ratio
        scale = next
    }

    fun pan(dxScreen: Float, dyScreen: Float) {
        offX += dxScreen
        offY += dyScreen
    }

    fun panBounded(dxScreen: Float, dyScreen: Float, worldW: Float, worldH: Float,
                   viewW: Int, viewH: Int) {
        val fit = fitScale(worldW, worldH, viewW, viewH)
        val normalized = (scale / fit).coerceAtLeast(1f)
        val baseX = (viewW - worldW * scale) / 2f
        val baseY = (viewH - worldH * scale) / 2f
        val maxX = viewW * (normalized - 1f) / 2f
        val maxY = viewH * (normalized - 1f) / 2f
        val relativeX = (offX - baseX + dxScreen).coerceIn(-maxX, maxX)
        val relativeY = (offY - baseY + dyScreen).coerceIn(-maxY, maxY)
        offX = baseX + relativeX
        offY = baseY + relativeY
    }
}

/**
 * Centre-anchored shaping. Dragging up makes a box taller, right makes it
 * wider, and the centre never moves.
 */
object ShapeMath {
    fun width(startW: Float, dxWorld: Float, minSide: Float): Float =
        (startW + 2f * dxWorld).coerceAtLeast(minSide)

    fun height(startH: Float, dyWorld: Float, minSide: Float): Float =
        (startH - 2f * dyWorld).coerceAtLeast(minSide)
}

/** Simple tidy-up helpers that compensate for imprecise finger placement. */
object Arrange {
    /** Adds an offset copy of each item to the project, preserving size and look. */
    fun duplicate(project: Project, sources: List<Item>, offset: Float): List<Item> =
        sources.map {
            val copy = it.clone()
            copy.id = 0
            if (copy.type == ItemType.MEASURE) copy.number = project.nextMeasure++
            copy.translate(offset, offset)
            project.add(copy)
        }

    /** Lines the selected boxes up on their left edges. */
    fun alignLeft(boxes: List<Item>) {
        if (boxes.size < 2) return
        val refLeft = boxes.minOf { it.left() }
        boxes.forEach { it.x = refLeft + it.w / 2f }
    }

    /**
     * Keeps the outer two boxes where they are, puts the rest on a single row
     * and gives every gap the same width.
     */
    fun spaceEvenly(boxes: List<Item>) {
        if (boxes.size < 3) return
        val sorted = boxes.sortedBy { it.left() }
        val first = sorted.first()
        val last = sorted.last()
        val totalWidth = sorted.sumOf { it.w.toDouble() }.toFloat()
        val gap = (last.right() - first.left() - totalWidth) / (sorted.size - 1)
        var cursor = first.left()
        sorted.forEach { box ->
            box.y = first.y
            box.x = cursor + box.w / 2f
            cursor += box.w + gap
        }
    }
}
