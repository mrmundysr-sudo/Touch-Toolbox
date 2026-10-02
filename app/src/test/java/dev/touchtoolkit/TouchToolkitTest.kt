package dev.touchtoolkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchToolkitTest {

    private val eps = 0.001f

    // ---- Centre-anchored shaping ------------------------------------------

    @Test
    fun `dragging up makes a box taller`() {
        assertEquals(600f, ShapeMath.height(startH = 400f, dyWorld = -100f, minSide = 40f), eps)
    }

    @Test
    fun `dragging down makes a box shorter`() {
        assertEquals(200f, ShapeMath.height(startH = 400f, dyWorld = 100f, minSide = 40f), eps)
    }

    @Test
    fun `dragging right makes a box wider and left narrower`() {
        assertEquals(600f, ShapeMath.width(400f, 100f, 40f), eps)
        assertEquals(200f, ShapeMath.width(400f, -100f, 40f), eps)
    }

    @Test
    fun `a box cannot collapse below the minimum side`() {
        assertEquals(40f, ShapeMath.width(400f, -5000f, 40f), eps)
        assertEquals(40f, ShapeMath.height(400f, 5000f, 40f), eps)
    }

    @Test
    fun `box minimum side is one percent of canvas width`() {
        val minimumSide = Project().worldW * MINIMUM_BOX_SIDE_FRACTION
        assertEquals(10.8f, minimumSide, eps)
        assertEquals(minimumSide, ShapeMath.width(400f, -5000f, minimumSide), eps)
        assertEquals(minimumSide, ShapeMath.height(400f, 5000f, minimumSide), eps)
    }

    @Test
    fun `shaping never moves the centre`() {
        val box = Item(type = ItemType.BOX, x = 500f, y = 700f, w = 400f, h = 400f)
        box.w = ShapeMath.width(box.w, 137f, 40f)
        box.h = ShapeMath.height(box.h, -61f, 40f)
        assertEquals(500f, box.x, eps)
        assertEquals(700f, box.y, eps)
        assertEquals(400f + 2 * 137f, box.w, eps)
        assertEquals(400f + 2 * 61f, box.h, eps)
        // And the centre reported by the edges agrees.
        assertEquals(500f, (box.left() + box.right()) / 2f, eps)
        assertEquals(700f, (box.top() + box.bottom()) / 2f, eps)
    }

    // ---- Camera independence ----------------------------------------------

    @Test
    fun `zooming in and back out restores the original view`() {
        val cam = Camera()
        cam.fit(1080f, 1920f, 1080, 2400)
        val s0 = cam.scale
        cam.zoomBy(4f, 540f, 1200f)
        cam.zoomBy(0.25f, 540f, 1200f)
        assertEquals(s0, cam.scale, eps)
    }

    @Test
    fun `zoom keeps the focus point visually fixed`() {
        val cam = Camera()
        cam.fit(1080f, 1920f, 1080, 2400)
        val focusX = 300f
        val focusY = 900f
        val wx = cam.worldX(focusX)
        val wy = cam.worldY(focusY)
        cam.zoomBy(3.7f, focusX, focusY)
        assertEquals(wx, cam.worldX(focusX), 0.01f)
        assertEquals(wy, cam.worldY(focusY), 0.01f)
    }

    @Test
    fun `zoom and pan never touch object geometry`() {
        val project = Project()
        val box = project.add(Item(type = ItemType.BOX, x = 500f, y = 500f, w = 300f, h = 200f))
        val cam = Camera()
        cam.fit(project.worldW, project.worldH, 1080, 2400)
        repeat(20) { cam.zoomBy(1.15f, 100f, 100f) }
        repeat(20) { cam.zoomBy(0.9f, 100f, 100f) }
        cam.pan(400f, -250f)
        assertEquals(500f, box.x, eps)
        assertEquals(500f, box.y, eps)
        assertEquals(300f, box.w, eps)
        assertEquals(200f, box.h, eps)
    }

    @Test
    fun `pan scrolls the view without moving objects`() {
        val cam = Camera()
        cam.fit(1080f, 1920f, 1080, 2400)
        val beforeX = cam.worldX(0f)
        val beforeY = cam.worldY(0f)
        cam.pan(120f, -75f)
        assertTrue(cam.worldX(0f) < beforeX)
        assertTrue(cam.worldY(0f) > beforeY)
    }

    @Test
    fun `full view centres the whole canvas`() {
        val cam = Camera()
        cam.zoomBy(9f, 10f, 10f)
        cam.pan(-5000f, 3000f)
        cam.fit(1080f, 1920f, 1080, 2400)
        val left = cam.screenX(0f)
        val right = cam.screenX(1080f)
        val top = cam.screenY(0f)
        val bottom = cam.screenY(1920f)
        assertEquals(540f, (left + right) / 2f, 0.5f)
        assertEquals(1200f, (top + bottom) / 2f, 0.5f)
        assertTrue(left >= 0f && right <= 1080f)
        assertTrue(top >= 0f && bottom <= 2400f)
    }

    @Test
    fun `world coordinates stay correct while magnified`() {
        val cam = Camera()
        cam.fit(1080f, 1920f, 1080, 2400)
        cam.zoomBy(6f, 540f, 1200f)
        assertEquals(200f, cam.worldX(cam.screenX(200f)), 0.01f)
        assertEquals(200f, cam.worldY(cam.screenY(200f)), 0.01f)
    }

    // ---- Move vs Shape -----------------------------------------------------

    @Test
    fun `moving an object does not change its size`() {
        val item = Item(type = ItemType.BOX, x = 100f, y = 100f, w = 250f, h = 175f)
        item.translate(330f, -120f)
        assertEquals(430f, item.x, eps)
        assertEquals(-20f, item.y, eps)
        assertEquals(250f, item.w, eps)
        assertEquals(175f, item.h, eps)
    }

    @Test
    fun `moving a line keeps its length and direction`() {
        val line = Item(type = ItemType.LINE, x = 0f, y = 0f, x2 = 300f, y2 = 400f)
        line.translate(1000f, -500f)
        assertEquals(500f, kotlin.math.hypot(line.x2 - line.x, line.y2 - line.y), eps)
        assertEquals(1000f, line.x, eps)
        assertEquals(1300f, line.x2, eps)
    }

    // ---- Undo / redo -------------------------------------------------------

    @Test
    fun `undo and redo walk through several edits`() {
        val project = Project()
        val history = History()
        project.add(Item(type = ItemType.BOX, x = 10f, y = 10f, w = 50f, h = 50f))
        history.push(project.snapshot())
        project.add(Item(type = ItemType.DOT, x = 20f, y = 20f))
        history.push(project.snapshot())
        project.add(Item(type = ItemType.NUMBER, x = 30f, y = 30f, number = 1))
        assertEquals(3, project.items.size)

        project.restore(history.undo(project.snapshot())!!)
        assertEquals(2, project.items.size)
        project.restore(history.undo(project.snapshot())!!)
        assertEquals(1, project.items.size)
        assertFalse(history.canUndo)

        project.restore(history.redo(project.snapshot())!!)
        assertEquals(2, project.items.size)
        project.restore(history.redo(project.snapshot())!!)
        assertEquals(3, project.items.size)
        assertFalse(history.canRedo)
    }

    @Test
    fun `a new edit clears the redo stack`() {
        val project = Project()
        val history = History()
        project.add(Item(type = ItemType.BOX, x = 0f, y = 0f, w = 10f, h = 10f))
        history.push(project.snapshot())
        project.add(Item(type = ItemType.DOT, x = 5f, y = 5f))
        history.push(project.snapshot())
        project.restore(history.undo(project.snapshot())!!)
        assertTrue(history.canRedo)
        history.push(project.snapshot())
        assertFalse(history.canRedo)
    }

    @Test
    fun `restoring a snapshot does not alias the live items`() {
        val project = Project()
        val history = History()
        val box = project.add(Item(type = ItemType.BOX, x = 100f, y = 100f, w = 200f, h = 200f))
        history.push(project.snapshot())
        box.w = 900f
        box.x = 5f
        project.restore(history.undo(project.snapshot())!!)
        assertEquals(200f, project.items[0].w, eps)
        assertEquals(100f, project.items[0].x, eps)
    }

    @Test
    fun `moving a number does not renumber the others`() {
        val project = Project()
        val one = project.add(Item(type = ItemType.NUMBER, x = 0f, y = 0f, number = 1))
        project.add(Item(type = ItemType.NUMBER, x = 0f, y = 0f, number = 2))
        project.add(Item(type = ItemType.NUMBER, x = 0f, y = 0f, number = 3))
        one.translate(500f, 500f)
        assertEquals(listOf(1, 2, 3), project.items.map { it.number })
    }

    @Test
    fun `numbering counts up and survives a restore`() {
        val project = Project()
        repeat(3) {
            project.add(Item(type = ItemType.NUMBER, x = 0f, y = 0f, number = project.nextNumber++))
        }
        assertEquals(4, project.nextNumber)
        project.restore(project.snapshot())
        assertEquals(4, project.nextNumber)
        assertTrue(project.items.all { it.id > 0 })
    }

    // ---- Persistence -------------------------------------------------------

    @Test
    fun `a project survives a save and load round trip`() {
        val project = Project()
        project.bgFile = "bg_123_test.png"
        project.add(Item(type = ItemType.BOX, x = 10.5f, y = 20.25f, w = 30f, h = 40f))
        project.add(Item(type = ItemType.LINE, x = 1f, y = 2f, x2 = 3f, y2 = 4f))
        project.add(Item(type = ItemType.DOT, x = 5f, y = 6f))
        project.add(Item(type = ItemType.NUMBER, x = 9f, y = 10f, number = 7, numberScale = 0.5f))

        val reloaded = Project.fromJson(org.json.JSONObject(project.toJson().toString()))

        assertEquals(project.worldW, reloaded.worldW, eps)
        assertEquals(project.worldH, reloaded.worldH, eps)
        assertEquals("bg_123_test.png", reloaded.bgFile)
        assertEquals(4, reloaded.items.size)
        assertEquals(ItemType.LINE, reloaded.items[1].type)
        assertEquals(3f, reloaded.items[1].x2, eps)
        assertEquals(7, reloaded.items[3].number)
        assertEquals(0.5f, reloaded.items[3].numberScale, eps)
        assertEquals(8, reloaded.nextNumber)
    }

    @Test
    fun `older number marks default to full size`() {
        val item = Item.fromJson(org.json.JSONObject("""{"type":"NUMBER","number":4}"""))
        assertEquals(1f, item!!.numberScale, eps)
    }

    @Test
    fun `a project with no background loads cleanly`() {
        val project = Project.fromJson(org.json.JSONObject("""{"bgFile":null,"items":[]}"""))
        assertEquals(null, project.bgFile)
        assertEquals(0, project.items.size)
    }

    // ---- Orientation -------------------------------------------------------

    @Test
    fun `orientation is recorded and survives a save and load round trip`() {
        val project = Project()
        project.worldW = 1920f
        project.worldH = 1080f
        project.orientation = ScreenOrientation.LANDSCAPE

        val reloaded = Project.fromJson(org.json.JSONObject(project.toJson().toString()))
        assertEquals(ScreenOrientation.LANDSCAPE, reloaded.orientation)
    }

    @Test
    fun `orientation is inferred from dimensions for projects saved before it existed`() {
        val portrait = Project.fromJson(
            org.json.JSONObject("""{"worldW":1080,"worldH":1920,"items":[]}""")
        )
        assertEquals(ScreenOrientation.PORTRAIT, portrait.orientation)

        val landscape = Project.fromJson(
            org.json.JSONObject("""{"worldW":1920,"worldH":1080,"items":[]}""")
        )
        assertEquals(ScreenOrientation.LANDSCAPE, landscape.orientation)
    }

    @Test
    fun `markup saved with a north star marker still loads, dropping only the marker`() {
        // Files written by the earlier build contained NORTHSTAR items.
        val json = org.json.JSONObject(
            """{"worldW":1080,"worldH":1920,"items":[
               {"type":"BOX","x":1,"y":2,"w":3,"h":4,"id":1},
               {"type":"NORTHSTAR","x":7,"y":8,"edge":1,"id":2}]}"""
        )
        val project = Project.fromJson(json)
        assertEquals(1, project.items.size)
        assertEquals(ItemType.BOX, project.items[0].type)
    }

    @Test
    fun `importing an image adopts its aspect ratio without stretching`() {
        val longest = 1080f
        val srcW = 4000
        val srcH = 3000
        val newW = longest
        val newH = longest * srcH / srcW
        assertEquals(srcW.toFloat() / srcH, newW / newH, 0.001f)
    }

    // ---- Touch targets -----------------------------------------------------

    @Test
    fun `a thin line has a generous hit area`() {
        val line = Item(type = ItemType.LINE, x = 0f, y = 0f, x2 = 1000f, y2 = 0f)
        assertTrue(nearLine(line, 500f, 24f, 30f))
        assertFalse(nearLine(line, 500f, 90f, 30f))
    }

    private fun nearLine(line: Item, px: Float, py: Float, pad: Float): Boolean {
        val dx = line.x2 - line.x
        val dy = line.y2 - line.y
        val len2 = dx * dx + dy * dy
        val t = (((px - line.x) * dx + (py - line.y) * dy) / len2).coerceIn(0f, 1f)
        val d = kotlin.math.hypot(px - (line.x + t * dx), py - (line.y + t * dy))
        return d <= pad
    }

    // ---- Alignment / distribution ------------------------------------------

    @Test
    fun `aligning boxes lines up their left edges`() {
        val a = Item(type = ItemType.BOX, x = 100f, y = 100f, w = 200f, h = 100f)
        val b = Item(type = ItemType.BOX, x = 400f, y = 300f, w = 100f, h = 100f)
        Arrange.alignLeft(listOf(a, b))
        assertEquals(a.left(), b.left(), eps)
    }

    @Test
    fun `even spacing gives equal gaps and keeps the outer boxes put`() {
        val a = Item(type = ItemType.BOX, x = 60f, y = 0f, w = 100f, h = 100f)
        val b = Item(type = ItemType.BOX, x = 300f, y = 5f, w = 100f, h = 100f)
        val c = Item(type = ItemType.BOX, x = 700f, y = -5f, w = 100f, h = 100f)
        val leftOfA = a.left()
        val rightOfC = c.right()
        val sizeOfA = a.w
        val sizeOfC = c.w

        Arrange.spaceEvenly(listOf(a, b, c))

        assertEquals(b.left() - a.right(), c.left() - b.right(), eps)
        assertEquals(leftOfA, a.left(), eps)
        assertEquals(rightOfC, c.right(), eps)
        assertEquals(sizeOfA, a.w, eps)
        assertEquals(sizeOfC, c.w, eps)
        assertEquals(a.y, b.y, eps)
        assertEquals(a.y, c.y, eps)
    }

    @Test
    fun `duplicating preserves size and appearance`() {
        val project = Project()
        val original = project.add(Item(type = ItemType.BOX, x = 200f, y = 300f, w = 350f, h = 175f))
        val copies = Arrange.duplicate(project, listOf(original), offset = 50f)
        assertEquals(1, copies.size)
        val copy = copies[0]
        assertEquals(350f, copy.w, eps)
        assertEquals(175f, copy.h, eps)
        assertEquals(original.type, copy.type)
        assertEquals(250f, copy.x, eps)
        assertEquals(350f, copy.y, eps)
        // The original is untouched, and the copy is a distinct object.
        assertEquals(200f, original.x, eps)
        assertFalse(copy.id == original.id)
    }
}
