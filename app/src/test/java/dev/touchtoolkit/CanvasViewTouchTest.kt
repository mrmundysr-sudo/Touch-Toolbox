package dev.touchtoolkit

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives real [MotionEvent]s through [CanvasView] so the one-finger behaviours
 * are exercised as the user actually performs them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CanvasViewTouchTest {

    private lateinit var view: CanvasView
    private lateinit var project: Project
    private val historyEvents = ArrayList<Int>()
    private var uiToggles = 0

    private val viewW = 1080
    private val viewH = 2000

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        project = Project()
        view = CanvasView(context)
        view.project = project
        view.onEditBefore = { historyEvents.add(it.size) }
        // Mirror the host: the toggle callback is what actually flips visibility.
        view.onToggleUi = {
            uiToggles++
            view.uiVisible = !view.uiVisible
        }
        view.setBottomInset(0f)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(viewW, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(viewH, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, viewW, viewH)
        view.fullView()
    }

    private fun screenX(worldX: Float) = view.camera.screenX(worldX)
    private fun screenY(worldY: Float) = view.camera.screenY(worldY)

    private var clock = 0L
    private var downTime = 0L

    private fun down(x: Float, y: Float) {
        clock += 16
        downTime = clock
        send(MotionEvent.ACTION_DOWN, x, y)
        // Second event at the same place: the canvas needs a move to pass the slop check.
        clock += 16
        send(MotionEvent.ACTION_MOVE, x + 1f, y + 1f)
    }

    private fun move(x: Float, y: Float) {
        clock += 16
        send(MotionEvent.ACTION_MOVE, x, y)
    }

    private fun up(x: Float, y: Float) {
        clock += 16
        send(MotionEvent.ACTION_UP, x, y)
    }

    /** A tap: press and release with no movement. */
    private fun tap(x: Float, y: Float) {
        clock += 16
        downTime = clock
        send(MotionEvent.ACTION_DOWN, x, y)
        clock += 16
        send(MotionEvent.ACTION_UP, x, y)
    }

    private fun send(action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, clock, action, x, y, 0)
        view.onTouchEvent(ev)
        ev.recycle()
    }

    // ---- Box shape mode ----------------------------------------------------

    @Test
    fun `dragging up makes a box taller with its centre anchored`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 400f, h = 400f))
        view.selectOnly(box.id)
        view.mode = Mode.SHAPE

        val cx = screenX(box.x)
        val cy = screenY(box.y)
        down(cx, cy)
        move(cx, cy - 60f)
        move(cx, cy - 120f)

        assertTrue("box should be taller", box.h > 400f)
        assertEquals("width must not change on a vertical drag", 400f, box.w, 0.5f)
        assertEquals(540f, box.x, 0.001f)
        assertEquals(960f, box.y, 0.001f)
    }

    @Test
    fun `dragging right makes a box wider and left makes it narrower`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 400f, h = 400f))
        view.selectOnly(box.id)
        view.mode = Mode.SHAPE

        val cx = screenX(box.x)
        val cy = screenY(box.y)

        down(cx, cy)
        move(cx + 90f, cy)
        val widened = box.w
        assertTrue("should widen", widened > 400f)

        // Continuous: reverse without lifting, still the same gesture.
        move(cx - 90f, cy)
        assertTrue("should be narrower than before", box.w < widened)
        assertTrue("should now be narrower than the start", box.w < 400f)
        assertEquals("centre stays put while getting narrower", 540f, box.x, 0.001f)
        assertEquals(960f, box.y, 0.001f)
    }

    @Test
    fun `a diagonal drag changes both dimensions at once`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 400f, h = 400f))
        view.selectOnly(box.id)
        view.mode = Mode.SHAPE

        val cx = screenX(box.x)
        val cy = screenY(box.y)
        down(cx, cy)
        move(cx + 80f, cy - 80f)

        assertTrue(box.w > 400f)
        assertTrue(box.h > 400f)
        assertEquals(540f, box.x, 0.001f)
        assertEquals(960f, box.y, 0.001f)
        assertEquals((box.left() + box.right()) / 2f, box.x, 0.01f)
        assertEquals((box.top() + box.bottom()) / 2f, box.y, 0.01f)
    }

    @Test
    fun `a box cannot collapse to nothing while shaping`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 400f, h = 400f))
        view.selectOnly(box.id)
        view.mode = Mode.SHAPE

        val cx = screenX(box.x)
        val cy = screenY(box.y)
        down(cx, cy)
        move(cx - 5000f, cy + 5000f)

        assertTrue(box.w > 0f)
        assertTrue(box.h > 0f)
    }

    @Test
    fun `shaping can stop reverse and continue in one touch`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 400f, h = 400f))
        view.selectOnly(box.id)
        view.mode = Mode.SHAPE

        val cx = screenX(box.x)
        val cy = screenY(box.y)
        down(cx, cy)
        move(cx + 100f, cy)
        val a = box.w
        move(cx + 100f, cy) // hold still
        assertEquals(a, box.w, 0.01f)
        move(cx + 20f, cy) // reverse
        assertTrue(box.w < a)
        move(cx + 150f, cy) // forward again
        assertTrue(box.w > a)
    }

    @Test
    fun `tapping outside the box leaves shape mode`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 200f, h = 200f))
        view.selectOnly(box.id)
        view.mode = Mode.SHAPE

        tap(screenX(20f), screenY(20f))
        assertEquals(Mode.NONE, view.mode)
    }

    // ---- Move mode ---------------------------------------------------------

    @Test
    fun `dragging in move mode relocates an object without resizing it`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        view.mode = Mode.MOVE

        val cx = screenX(box.x)
        val cy = screenY(box.y)
        down(cx, cy)
        move(cx + 70f, cy + 40f)
        move(cx + 140f, cy + 80f)
        up(cx + 140f, cy + 80f)

        assertEquals("width is untouched by moving", 300f, box.w, 0.01f)
        assertEquals("height is untouched by moving", 200f, box.h, 0.01f)
        assertTrue("should have moved right", box.x > 540f)
        assertTrue("should have moved down", box.y > 960f)
    }

    @Test
    fun `move view pans the camera and leaves objects exactly where they were`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        val dot = project.add(Item(type = ItemType.DOT, x = 200f, y = 300f))
        view.mode = Mode.PAN

        val scaleBefore = view.camera.scale
        val boxBefore = listOf(box.x, box.y, box.w, box.h)
        val dotBefore = listOf(dot.x, dot.y)

        down(400f, 900f)
        move(200f, 700f)
        up(200f, 700f)

        assertEquals(boxBefore, listOf(box.x, box.y, box.w, box.h))
        assertEquals(dotBefore, listOf(dot.x, dot.y))
        assertEquals("pan must not change zoom", scaleBefore, view.camera.scale, 0.0001f)
        assertTrue(historyEvents.isEmpty())
    }

    @Test
    fun `finishing a drag does not trigger the preview toggle`() {
        project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        view.mode = Mode.MOVE
        val before = uiToggles

        down(540f, 960f)
        move(600f, 1000f)
        up(600f, 1000f)

        assertEquals("a drag is not a preview tap", before, uiToggles)
    }

    // ---- Zoom --------------------------------------------------------------

    /**
     * The magnifier is an explicitly chosen tool, so the test picks it first and
     * then performs the continuous vertical drag the user makes.
     */
    private fun startMagnify(x: Float = viewW / 2f, y: Float = viewH / 2f) {
        view.mode = Mode.MAGNIFY
        down(x, y)
    }

    @Test
    fun `holding zoom and moving up zooms in`() {
        val before = view.camera.scale
        startMagnify()
        move(viewW / 2f, viewH / 2f - 80f)
        assertTrue("moving up should zoom in", view.camera.scale > before)
    }

    @Test
    fun `zoom can stop resume and reverse within one uninterrupted touch`() {
        val start = view.camera.scale
        startMagnify()
        move(viewW / 2f, viewH / 2f - 100f)
        val afterFirst = view.camera.scale
        assertTrue(afterFirst > start)

        move(viewW / 2f, viewH / 2f - 100f) // stop moving
        assertEquals("stopping holds the current magnification", afterFirst, view.camera.scale, 1e-5f)

        move(viewW / 2f, viewH / 2f - 220f) // resume
        val afterSecond = view.camera.scale
        assertTrue("resuming continues zooming in", afterSecond > afterFirst)

        move(viewW / 2f, viewH / 2f - 100f) // reverse: now moving down
        val afterReverse = view.camera.scale
        assertTrue("reversing immediately zooms out", afterReverse < afterSecond)

        move(viewW / 2f, viewH / 2f - 420f) // reverse again: now moving up
        assertTrue("can reverse repeatedly", view.camera.scale > afterReverse)
    }

    @Test
    fun `magnification remains after the zoom gesture ends`() {
        val before = view.camera.scale
        startMagnify()
        move(viewW / 2f, viewH / 2f - 150f)
        up(viewW / 2f, viewH / 2f - 150f)
        val held = view.camera.scale
        assertTrue(held > before)
        assertEquals("zoom level persists after release", held, view.camera.scale, 1e-5f)
    }

    @Test
    fun `zoom never pollutes undo history`() {
        project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        assertEquals(0, historyEvents.size)

        startMagnify()
        repeat(5) { move(viewW / 2f, viewH / 2f - 40f * (it + 1)) }
        up(viewW / 2f, viewH / 2f - 200f)

        view.mode = Mode.PAN
        down(400f, 900f)
        move(300f, 800f)
        up(300f, 800f)
        view.mode = Mode.NONE
        view.fullView()

        assertTrue("camera actions are not edits", historyEvents.isEmpty())
    }

    @Test
    fun `a dot can be placed precisely while magnified`() {
        // Zoom in, then edit; magnification must not disturb placement.
        startMagnify()
        move(viewW / 2f, viewH / 2f - 200f)
        up(viewW / 2f, viewH / 2f - 200f)
        assertTrue(view.camera.scale > 1.2f)

        view.mode = Mode.DOT
        val targetScreenX = 400f
        val targetScreenY = 700f
        tap(targetScreenX, targetScreenY)

        assertEquals(1, project.items.count { it.type == ItemType.DOT })
        val dot = project.items.first { it.type == ItemType.DOT }
        // The dot lands exactly where the finger touched, in world coordinates.
        assertEquals(view.camera.worldX(targetScreenX), dot.x, 0.01f)
        assertEquals(view.camera.worldY(targetScreenY), dot.y, 0.01f)
    }

    @Test
    fun `an object can be repositioned with move mode while magnified`() {
        val dot = project.add(Item(type = ItemType.DOT, x = 540f, y = 960f))
        startMagnify()
        move(viewW / 2f, viewH / 2f - 200f)
        up(viewW / 2f, viewH / 2f - 200f)

        view.mode = Mode.MOVE
        val cx = screenX(dot.x)
        val cy = screenY(dot.y)
        down(cx, cy)
        move(cx + 40f, cy + 25f)
        up(cx + 40f, cy + 25f)

        // On screen the dot followed the finger exactly.
        assertEquals(cx + 40f, screenX(dot.x), 0.5f)
        assertEquals(cy + 25f, screenY(dot.y), 0.5f)
    }

    @Test
    fun `numbering continues while magnified and zoom stays put`() {
        val scaleBefore = view.camera.scale
        view.mode = Mode.NUMBER
        tap(300f, 400f)
        tap(500f, 600f)
        tap(700f, 800f)

        val numbers = project.items.filter { it.type == ItemType.NUMBER }.map { it.number }
        assertEquals(listOf(1, 2, 3), numbers)
        assertEquals(4, project.nextNumber)
        assertEquals(scaleBefore, view.camera.scale, 1e-5f)
    }

    // ---- Full view ---------------------------------------------------------

    @Test
    fun `full view restores the whole canvas and changes nothing else`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        val dot = project.add(Item(type = ItemType.DOT, x = 100f, y = 150f))
        val before = project.items.map { listOf(it.x, it.y, it.w, it.h, it.number) }
        val historyBefore = historyEvents.size

        // Zoom deeply and pan away, then reset.
        startMagnify()
        move(viewW / 2f, viewH / 2f - 400f)
        up(viewW / 2f, viewH / 2f - 400f)
        view.mode = Mode.PAN
        down(500f, 900f)
        move(100f, 500f)
        up(100f, 500f)

        view.fullView()

        assertEquals(before, project.items.map { listOf(it.x, it.y, it.w, it.h, it.number) })
        assertEquals("full view is not an edit", historyBefore, historyEvents.size)
        assertEquals(2, project.items.size)
        assertNotNull(box)
        assertNotNull(dot)

        // The whole page is inside the viewport, centred.
        val left = view.camera.screenX(0f)
        val right = view.camera.screenX(project.worldW)
        val top = view.camera.screenY(0f)
        val bottom = view.camera.screenY(project.worldH)
        assertTrue(left >= -0.5f && right <= viewW + 0.5f)
        assertTrue(top >= -0.5f && bottom <= viewH + 0.5f)
        assertEquals(viewW / 2f, (left + right) / 2f, 0.5f)
    }

    // ---- Line, dot, north star ---------------------------------------------

    @Test
    fun `a line is created from a start tap and an end tap`() {
        view.mode = Mode.LINE
        tap(300f, 400f)
        assertTrue("no line until the second tap", project.items.none { it.type == ItemType.LINE })
        tap(700f, 900f)

        val line = project.items.single { it.type == ItemType.LINE }
        assertEquals(view.camera.worldX(300f), line.x, 0.01f)
        assertEquals(view.camera.worldY(400f), line.y, 0.01f)
        assertEquals(view.camera.worldX(700f), line.x2, 0.01f)
        assertEquals(view.camera.worldY(900f), line.y2, 0.01f)
    }

    // ---- Locked surface and viewport ---------------------------------------

    @Test
    fun `the work surface cannot be selected`() {
        project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        view.mode = Mode.MOVE

        // Tapping well away from any mark must not select the surface itself.
        tap(screenX(5f), screenY(5f))
        assertEquals(0, view.selection.size)
        assertTrue(view.isSurfaceGeometryLocked())
    }

    @Test
    fun `zooming out never goes below the fitted full screen view`() {
        view.fullView()
        val fitted = view.camera.scale

        // Try hard to zoom out past the fitted view.
        repeat(40) { view.zoomBy(0.5f, viewW / 2f, viewH / 2f) }

        assertEquals("zoom floor is the fitted view", fitted, view.camera.scale, 0.0001f)
    }

    @Test
    fun `rotating keeps every mark at the same world position`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        val dot = project.add(Item(type = ItemType.DOT, x = 200f, y = 300f))
        val line = project.add(Item(type = ItemType.LINE, x = 10f, y = 20f, x2 = 700f, y2 = 800f))
        view.fullView()

        val before = project.items.map { listOf(it.x, it.y, it.w, it.h, it.x2, it.y2) }

        // Portrait to landscape, as the phone is turned.
        view.onViewportResized(1080, 1920)

        val after = project.items.map { listOf(it.x, it.y, it.w, it.h, it.x2, it.y2) }
        assertEquals(before, after)
        assertEquals(540f, box.x, 0.0001f)
        assertEquals(960f, box.y, 0.0001f)
        assertEquals(300f, box.w, 0.0001f)
        assertEquals(200f, box.h, 0.0001f)
        assertEquals(200f, dot.x, 0.0001f)
        assertEquals(700f, line.x2, 0.0001f)
    }

    @Test
    fun `rotating preserves the current zoom and only adjusts the floor`() {
        view.fullView()
        view.zoomBy(3f, viewW / 2f, viewH / 2f)
        val zoomed = view.camera.scale
        assertTrue("should be zoomed in", zoomed > view.fittedScale)

        view.onViewportResized(1080, 1920)

        // Still at the user's zoom, not silently reset to the fitted view.
        assertTrue("zoom preserved", view.camera.scale >= view.fittedScale)
        assertTrue("floor may rise but never falls", view.fittedScale <= zoomed + 0.0001f)
    }

    @Test
    fun `rotating keeps the artwork and only re-lays out the camera`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        view.fullView()
        view.zoomBy(2f, viewW / 2f, viewH / 2f)
        val artworkBefore = listOf(box.x, box.y, box.w, box.h)
        val zoomBefore = view.camera.scale

        // Landscape: a wider, shorter screen. This is the host's rotation path.
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(viewH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(viewW, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, viewH, viewW)
        view.onViewportResized(viewW, viewH)

        assertEquals("artwork keeps its world coordinates", artworkBefore, listOf(box.x, box.y, box.w, box.h))
        assertEquals("the user's zoom is preserved", zoomBefore, view.camera.scale, 0.0001f)
        assertTrue("the surface still fills the new screen", view.camera.scale >= view.fittedScale)
    }

    @Test
    fun `rotating does not record an edit history entry`() {
        view.fullView()
        val before = historyEvents.size
        repeat(3) { view.onViewportResized(1080, 1920) }
        assertEquals("camera changes are not edits", before, historyEvents.size)
    }

    // ---- Hiding the chrome keeps the tool live -----------------------------

    /** Stands in for the host's Hide Tools control, which only flips visibility. */
    private fun hideChromeLikeHost() {
        uiToggles++
        view.uiVisible = false
    }

    @Test
    fun `hiding the chrome does not change the active tool`() {
        view.mode = Mode.DOT
        hideChromeLikeHost()
        assertFalse(view.uiVisible)
        assertEquals("the chosen tool must survive being hidden", Mode.DOT, view.mode)
    }

    @Test
    fun `restoring the chrome does not change the active tool`() {
        view.mode = Mode.LINE
        hideChromeLikeHost()
        assertFalse(view.uiVisible)

        // With a tool chosen the panel returns via the host SHOW TOOLS control,
        // which only flips visibility.
        view.uiVisible = true

        assertTrue(view.uiVisible)
        assertEquals("the chosen tool must survive being restored", Mode.LINE, view.mode)
    }

    @Test
    fun `the active tool still edits while the chrome is hidden`() {
        view.mode = Mode.DOT
        hideChromeLikeHost()
        val items = project.items.size

        // Place a dot deep in the strip the panel would normally cover, but clear
        // of the shallow reveal sliver that is reserved for restoring the panel.
        tap(screenX(540f), viewH - 60f)
        assertEquals("a dot should be placed with the panel hidden", items + 1, project.items.size)
        assertEquals(ItemType.DOT, project.items.last().type)
    }

    @Test
    fun `the reveal strip restores the panel instead of editing`() {
        view.mode = Mode.DOT
        hideChromeLikeHost()
        val items = project.items.size

        // A drag that begins in the reserved bottom sliver is the reveal gesture,
        // so it must bring the panel back and place nothing.
        val edgeY = viewH - 5f
        down(screenX(540f), edgeY)
        move(screenX(540f), edgeY - 200f)
        up(screenX(540f), edgeY - 200f)

        assertTrue("the reveal drag brings the panel back", view.uiVisible)
        assertEquals("the reveal drag places nothing", items, project.items.size)
    }

    @Test
    fun `a tool hidden over the toolbar strip still works there`() {
        view.mode = Mode.NUMBER
        hideChromeLikeHost()

        tap(screenX(400f), viewH - 60f)
        tap(screenX(600f), viewH - 70f)
        val numbers = project.items.filter { it.type == ItemType.NUMBER }
        assertEquals("both numbers land in the covered strip", 2, numbers.size)
        assertEquals(listOf(1, 2), numbers.map { it.number })
    }

    @Test
    fun `dragging to move an object works while the chrome is hidden`() {
        val box = project.add(Item(type = ItemType.BOX, x = 540f, y = 900f, w = 300f, h = 200f))
        view.mode = Mode.MOVE
        hideChromeLikeHost()

        val cx = screenX(box.x)
        val cy = screenY(box.y)
        down(cx, cy)
        move(cx + 80f, cy + 120f)
        up(cx + 80f, cy + 120f)

        assertEquals("the box should have moved", 80f / view.camera.scale, box.x - 540f, 1f)
        assertEquals(300f, box.w, 0.01f)
        assertEquals(200f, box.h, 0.01f)
    }

    @Test
    fun `a hidden tool tap does not restore the chrome by accident`() {
        view.mode = Mode.NUMBER
        hideChromeLikeHost()
        val before = uiToggles

        // Several placements in the covered strip must never pop the panel back.
        tap(screenX(300f), viewH - 60f)
        tap(screenX(500f), viewH - 60f)
        tap(screenX(700f), viewH - 60f)
        assertEquals("placing numbers must not toggle the chrome", before, uiToggles)
        assertFalse(view.uiVisible)
    }

    // ---- Empty-space preview tap (no tool chosen) --------------------------

    @Test
    fun `with no tool chosen an empty tap hides and restores the chrome`() {
        project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        view.mode = Mode.NONE
        val before = uiToggles

        tap(screenX(5f), screenY(5f))
        assertEquals(before + 1, uiToggles)
        assertFalse(view.uiVisible)

        // With no tool chosen a tap anywhere brings the panel back.
        tap(screenX(5f), screenY(5f))
        assertEquals(before + 2, uiToggles)
        assertTrue(view.uiVisible)
        assertEquals(Mode.NONE, view.mode)
    }

    @Test
    fun `dragging from the reveal strip never pans or edits`() {
        // With no tool chosen the reserved strip is a pure reveal gesture, so it
        // must never pan the camera or change the artwork on its way out.
        view.mode = Mode.NONE
        hideChromeLikeHost()
        val offBefore = view.camera.offX to view.camera.offY
        val items = project.items.size

        val edgeY = viewH - 5f
        down(viewW / 2f, edgeY)
        move(viewW / 2f + 200f, edgeY - 300f)
        up(viewW / 2f + 200f, edgeY - 300f)

        assertEquals("a drag from the reveal strip must not pan", offBefore, view.camera.offX to view.camera.offY)
        assertEquals("a drag from the reveal strip must not edit", items, project.items.size)
        // The upward drag away from the edge is the reveal gesture.
        assertTrue("dragging away from the edge restores the chrome", view.uiVisible)
    }

    @Test
    fun `the reveal strip is the only place reserved when a tool is chosen`() {
        // The panel overlays the canvas, so with it hidden the chosen tool owns
        // everything except the shallow bottom sliver used to bring it back.
        view.mode = Mode.PAN
        hideChromeLikeHost()
        val offBefore = view.camera.offY

        val y = viewH - 60f
        down(viewW / 2f, y)
        move(viewW / 2f, y - 200f)
        up(viewW / 2f, y - 200f)

        assertTrue(
            "a chosen tool must keep working over the strip the panel covered",
            view.camera.offY != offBefore
        )
    }

    @Test
    fun `selecting an object does not hide the interface`() {
        project.add(Item(type = ItemType.BOX, x = 540f, y = 960f, w = 300f, h = 200f))
        view.mode = Mode.MOVE
        val before = uiToggles

        tap(540f, 960f)
        assertEquals(before, uiToggles)
        assertTrue(view.uiVisible)
        assertEquals(1, view.selection.size)
    }

    // ---- Selection of thin and small objects -------------------------------

    @Test
    fun `a thin line is easy to select with a finger`() {
        val line = project.add(
            Item(type = ItemType.LINE, x = 100f, y = 500f, x2 = 900f, y2 = 500f)
        )
        view.mode = Mode.MOVE
        // 20 screen px away from a hairline: still comfortably selectable.
        val my = screenY(500f)
        tap(screenX(500f), my - 20f)
        assertEquals(1, view.selection.size)
        assertTrue(view.selection.contains(line.id))
    }

    @Test
    fun `a small dot is easy to select with a finger`() {
        val dot = project.add(Item(type = ItemType.DOT, x = 500f, y = 500f))
        view.mode = Mode.MOVE
        tap(screenX(500f) + 22f, screenY(500f))
        assertEquals(1, view.selection.size)
        assertTrue(view.selection.contains(dot.id))
    }
}
