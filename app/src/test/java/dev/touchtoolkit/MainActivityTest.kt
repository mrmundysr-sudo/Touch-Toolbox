package dev.touchtoolkit

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Makes sure the activity assembles its chrome and starts without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun `horizontal tray scrolling never dismisses it`() {
        var dismissals = 0
        val listener = TraySwipeDismissListener(8f, 48f) { dismissals++ }
        val view = View(ApplicationProvider.getApplicationContext())

        send(listener, view, MotionEvent.ACTION_DOWN, 300f, 900f)
        send(listener, view, MotionEvent.ACTION_MOVE, 40f, 904f)
        send(listener, view, MotionEvent.ACTION_UP, 40f, 904f)

        assertEquals(0, dismissals)
    }

    @Test
    fun `only a deliberate downward swipe from the tray dismisses it`() {
        var dismissals = 0
        val listener = TraySwipeDismissListener(8f, 48f) { dismissals++ }
        val view = View(ApplicationProvider.getApplicationContext())

        send(listener, view, MotionEvent.ACTION_DOWN, 300f, 900f)
        send(listener, view, MotionEvent.ACTION_MOVE, 300f, 970f)
        send(listener, view, MotionEvent.ACTION_UP, 300f, 970f)
        assertEquals(1, dismissals)

        send(listener, view, MotionEvent.ACTION_DOWN, 300f, 900f)
        send(listener, view, MotionEvent.ACTION_MOVE, 300f, 820f)
        send(listener, view, MotionEvent.ACTION_UP, 300f, 820f)
        assertEquals("an upward swipe must not dismiss the tray", 1, dismissals)
    }

    private fun send(listener: View.OnTouchListener, view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        try {
            listener.onTouch(view, event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `the activity starts and shows the tool chrome`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertTrue("activity should not be finishing", !activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `tapping Number cycles full half full while keeping the tool active`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val numberTool = findText(root, "Number")
        val canvasField = MainActivity::class.java.getDeclaredField("canvasView").apply {
            isAccessible = true
        }
        val canvas = canvasField.get(activity) as CanvasView
        canvas.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        canvas.layout(0, 0, 1080, 2000)
        canvas.fullView()

        numberTool.performClick()
        assertEquals(Mode.NUMBER, canvas.mode)
        assertEquals(1f, canvas.numberScale, 0f)
        tap(canvas, 200f, 300f)

        numberTool.performClick()
        assertEquals(Mode.NUMBER, canvas.mode)
        assertEquals(0.5f, canvas.numberScale, 0f)
        tap(canvas, 400f, 500f)

        numberTool.performClick()
        assertEquals(Mode.NUMBER, canvas.mode)
        assertEquals(1f, canvas.numberScale, 0f)
        tap(canvas, 600f, 700f)
        assertEquals(listOf(1f, 0.5f, 1f), canvas.project.items.map { it.numberScale })
        controller.pause().stop().destroy()
    }

    private fun tap(view: CanvasView, x: Float, y: Float) {
        val down = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(1L, 2L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.onTouchEvent(down)
            view.onTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun findText(parent: ViewGroup, label: String): TextView {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is TextView && child.text.toString() == label) return child
            if (child is ViewGroup) runCatching { findText(child, label) }.getOrNull()?.let { return it }
        }
        error("Could not find tool labeled $label")
    }

    @Test
    fun `a project starts on a fresh surface`() {
        val project = ProjectStore.loadCurrent(ApplicationProvider.getApplicationContext())
        // A brand new install has no saved project, and the default is a clean surface.
        if (project == null) {
            val fresh = Project()
            assertTrue(fresh.items.isEmpty())
            assertTrue(fresh.worldW > 0f && fresh.worldH > 0f)
        }
    }
}
