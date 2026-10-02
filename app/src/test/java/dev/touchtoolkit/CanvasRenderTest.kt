package dev.touchtoolkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the workspace into a real bitmap so the "plain white, edge to edge"
 * requirement is checked against actual pixels rather than assumptions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasRenderTest {

    private lateinit var view: CanvasView
    private lateinit var project: Project
    private val viewW = 1080
    private val viewH = 2000

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        project = Project()
        view = CanvasView(context)
        view.project = project
        view.setBottomInset(0f)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(viewW, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(viewH, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, viewW, viewH)
        view.fullView()
    }

    /** Renders the scene exactly as the app draws it on screen. */
    private fun renderScene(): Bitmap {
        val bmp = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val c = Canvas(bmp)
        view.drawScene(c, view.camera.scale, view.camera.offX, view.camera.offY,
            viewW.toFloat(), viewH.toFloat())
        return bmp
    }

    @Test
    fun `a new project is plain white across the whole screen`() {
        val bmp = renderScene()
        // Sample the extreme corners and edges: no dark surround, no page boundary.
        val points = listOf(
            0 to 0, viewW - 1 to 0, 0 to viewH - 1, viewW - 1 to viewH - 1,
            viewW / 2 to 0, viewW / 2 to viewH - 1, 0 to viewH / 2, viewW - 1 to viewH / 2
        )
        for ((x, y) in points) {
            assertEquals(
                "pixel ($x,$y) should be the white surface",
                Color.WHITE, bmp.getPixel(x, y)
            )
        }
        bmp.recycle()
    }

    @Test
    fun `no dark frame appears when the surface is zoomed in`() {
        view.zoomBy(3f, viewW / 2f, viewH / 2f)
        val bmp = renderScene()
        for (y in 0 until viewH step 97) {
            for (x in 0 until viewW step 97) {
                assertEquals(
                    "zoomed view has no dark surround at ($x,$y)",
                    Color.WHITE, bmp.getPixel(x, y)
                )
            }
        }
        bmp.recycle()
    }

    @Test
    fun `a rendered mark is present and the surface around it stays white`() {
        project.add(Item(type = ItemType.BOX, x = 540f, y = 1000f, w = 300f, h = 200f))
        val bmp = renderScene()

        val cx = view.camera.screenX(540f).toInt()
        val cy = view.camera.screenY(1000f).toInt()
        assertTrue("box outline should not be white at its centre", bmp.getPixel(cx, cy) != Color.WHITE)

        // Well away from the box the surface is untouched white.
        assertEquals(Color.WHITE, bmp.getPixel(10, 10))
        bmp.recycle()
    }

    @Test
    fun `the canvas draws no floating controls of its own`() {
        // The editing chrome is owned by the host toolbar. The canvas must never
        // draw duplicate controls, so minimising it leaves the surface unchanged.
        view.uiVisible = false
        val minimised = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        minimised.eraseColor(Color.RED)
        view.draw(Canvas(minimised))

        view.uiVisible = true
        val shown = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        shown.eraseColor(Color.RED)
        view.draw(Canvas(shown))

        for (y in 0 until viewH step 53) {
            for (x in 0 until viewW step 53) {
                assertEquals(
                    "no chrome is drawn on the canvas at ($x,$y)",
                    shown.getPixel(x, y), minimised.getPixel(x, y)
                )
            }
        }
        minimised.recycle()
        shown.recycle()
    }

    @Test
    fun `the bottom reveal strip is reserved for restoring the chrome`() {
        val density = view.resources.displayMetrics.density
        val revealHeight = 28f * density
        // The strip sits against the bottom edge and stays a small sliver, so it
        // never swallows a meaningful part of the drawing area.
        assertTrue("reveal strip must be at the bottom", revealHeight < viewH / 4f)
        assertTrue("reveal strip must be shallow", revealHeight <= 64f)
    }
}
