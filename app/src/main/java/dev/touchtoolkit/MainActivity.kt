package dev.touchtoolkit

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: CanvasView
    private lateinit var project: Project
    private val history = History()
    private lateinit var toolbar: View
    private val toolButtons = LinkedHashMap<String, TextView>()
    private var multiButton: TextView? = null
    private var activeTool = ""

    private val density by lazy { resources.displayMetrics.density }

    private val pickBackground =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importBackground(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        project = ProjectStore.loadCurrent(this) ?: Project()
        history.reset()

        canvasView = CanvasView(this)
        canvasView.project = project
        canvasView.background = loadBackground(project.bgFile)

        val root = FrameLayout(this)
        root.addView(
            canvasView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // ---- bottom chrome: status + tools --------------------------------
        val toolScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xF21E2127.toInt())
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad(10), pad(6), pad(10), pad(14))
        }
        toolScroll.addView(toolRow)

        // Row 1 — modes that change what the finger does.
        addTool(toolRow, "shape", "Shape") { setMode(Mode.SHAPE) }
        addTool(toolRow, "move", "Move") { setMode(Mode.MOVE) }
        addTool(toolRow, "select", "Select") { setMode(Mode.SELECT) }
        addTool(toolRow, "multi", "Multi") { toggleMultiSelect() }
        addTool(toolRow, "line", "Line") { setMode(Mode.LINE) }
        addTool(toolRow, "dot", "Dot") { setMode(Mode.DOT) }
        addTool(toolRow, "num", "Number") { setMode(Mode.NUMBER) }
        addTool(toolRow, "measure", "Measure Mark") { setMode(Mode.MEASURE) }
        addTool(toolRow, "pan", "Pan View") { setMode(Mode.PAN) }
        addTool(toolRow, "magnify", "Magnify") { setMode(Mode.MAGNIFY) }

        // Row 2 — object and project actions.
        val actionScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xF21E2127.toInt())
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad(10), 0, pad(10), pad(16))
        }
        actionScroll.addView(actionRow)

        addAction(actionRow, "New Box") { addBox() }
        addAction(actionRow, "Duplicate") { duplicate() }
        addAction(actionRow, "Delete") { deleteSelection() }
        addAction(actionRow, "Select All") { selectAll() }
        addAction(actionRow, "Undo") { undo() }
        addAction(actionRow, "Redo") { redo() }
        addAction(actionRow, "Import Image") {
            pickBackground.launch(arrayOf("image/*"))
        }
        addAction(actionRow, "Share Mockup") { shareMockup() }
        addAction(actionRow, "Rotate 90°") { rotatePicture() }
        addAction(actionRow, "Copy Marks") { copyMarks() }
        addAction(actionRow, "Edit Mark") {
            canvasView.selectedItems().firstOrNull { it.type == ItemType.MEASURE }
                ?.let { editMark(it) } ?: toast("Select an X mark first")
        }
        addAction(actionRow, "New Project") { newProject() }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolScroll)
            addView(actionScroll)
        }

        val barParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        root.addView(toolbar, barParams)

        setContentView(root)

        // ---- wiring ---------------------------------------------------------
        canvasView.onUiChanged = { refreshUi() }
        canvasView.onEditBefore = { before -> history.push(before) }
        canvasView.onToast = { msg -> toast(msg) }
        canvasView.onMeasureMark = { editMark(it) }
        canvasView.onToggleUi = { toggleUi() }
        toolScroll.setOnTouchListener(newTraySwipeDismissListener())
        actionScroll.setOnTouchListener(newTraySwipeDismissListener())

        toolbar.post {
            canvasView.setBottomInset(toolbar.height.toFloat())
            canvasView.fullView()
        }
        canvasView.post { canvasView.fullView() }
        refreshUi()
    }

    private fun pad(dp: Int) = (dp * density).toInt()

    private fun addTool(row: LinearLayout, key: String, label: String, onClick: () -> Unit) {
        val b = TextView(this).apply {
            text = label
            setTextColor(0xFFECEFF4.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(pad(6), pad(14), pad(6), pad(14))
            minWidth = pad(72)
            setOnClickListener {
                onClick()
                refreshUi()
            }
        }
        toolButtons[key] = b
        if (key == "multi") multiButton = b
        val lp = LinearLayout.LayoutParams(pad(76), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = pad(6)
        row.addView(b, lp)
    }

    private fun addAction(row: LinearLayout, label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = pad(6)
        row.addView(b, lp)
    }

    // =======================================================================
    // Mode and UI state
    // =======================================================================

    private fun setMode(m: Mode) {
        if (canvasView.mode == m) {
            if (m == Mode.NUMBER) {
                canvasView.numberScale = if (canvasView.numberScale == 1f) 0.5f else 1f
                toast(if (canvasView.numberScale == 0.5f) "Number size: half" else "Number size: full")
                refreshUi()
                return
            }
            activeTool = ""
            canvasView.mode = Mode.NONE
            refreshUi()
            return
        }
        if (m == Mode.NUMBER) canvasView.numberScale = 1f
        if (m == Mode.SHAPE) {
            // Tapping Shape again leaves shape mode.
            if (canvasView.mode == Mode.SHAPE) {
                activeTool = ""
                canvasView.mode = Mode.NONE
                toast("Shape finished")
                return
            }
            if (canvasView.selectedBox() == null) {
                val box = canvasView.selectedItems().firstOrNull { it.type == ItemType.BOX }
                if (box != null) canvasView.selectOnly(box.id)
            }
            if (canvasView.selectedBox() == null) {
                toast("Select a box first, or create one")
                return
            }
        }
        if (m == Mode.MOVE && canvasView.selection.isEmpty()) {
            canvasView.selectLastMarkup()
        }
        if (m != Mode.MOVE && canvasView.multiSelect) {
            canvasView.multiSelect = false
            multiButton?.text = "Multi: off"
        }
        activeTool = when (m) {
            Mode.NONE -> ""
            Mode.SHAPE -> "shape"
            Mode.MOVE -> "move"
            Mode.SELECT -> "select"
            Mode.LINE -> "line"
            Mode.DOT -> "dot"
            Mode.NUMBER -> "num"
            Mode.MEASURE -> "measure"
            Mode.PAN -> "pan"
            Mode.MAGNIFY -> "magnify"
        }
        canvasView.mode = m
        refreshUi()
    }

    /** Turns additive selection on so several objects can be aligned together. */
    private fun toggleMultiSelect() {
        val next = !canvasView.multiSelect
        if (next && canvasView.mode != Mode.MOVE) setMode(Mode.MOVE)
        canvasView.multiSelect = next
        multiButton?.text = if (next) "Multi: on" else "Multi: off"
        refreshUi()
        toast(if (next) "Tap objects to add them to the selection" else "Multi select off")
    }

    private fun selectAll() {
        if (project.items.isEmpty()) {
            toast("Nothing on the canvas yet")
            return
        }
        if (canvasView.mode != Mode.MOVE) setMode(Mode.MOVE)
        canvasView.multiSelect = false
        multiButton?.text = "Multi: off"
        canvasView.selectAll()
        toast("Selected ${project.items.size}")
    }

    private fun refreshUi() {
        toolButtons.forEach { (key, btn) ->
            val on = when (key) {
                "multi" -> canvasView.multiSelect
                else -> key == activeTool
            }
            btn.setBackgroundColor(if (on) 0xFF4C9AFF.toInt() else 0xFF262A32.toInt())
            btn.setTextColor(if (on) 0xFF0B0D10.toInt() else 0xFFECEFF4.toInt())
        }
    }

    /**
     * Minimise or restore the editing chrome. Deliberately touches nothing else:
     * the active tool, the selection and the zoom all survive, and the chosen
     * tool keeps working while the panel is hidden.
     */
    private fun toggleUi() {
        canvasView.uiVisible = !canvasView.uiVisible
        if (canvasView.uiVisible) {
            toolbar.visibility = View.VISIBLE
            toolbar.post { canvasView.setBottomInset(toolbar.height.toFloat()) }
        } else {
            toolbar.visibility = View.GONE
            canvasView.setBottomInset(0f)
        }
        // Refresh after the state change so the readout reflects the new chrome,
        // never a different tool.
        refreshUi()
    }

    private fun newTraySwipeDismissListener(): View.OnTouchListener = TraySwipeDismissListener(
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
        dismissDistancePx = 48f * density,
        onDismiss = ::hideTools
    )

    private fun hideTools() {
        if (!canvasView.uiVisible) return
        canvasView.uiVisible = false
        toolbar.visibility = View.GONE
        canvasView.setBottomInset(0f)
    }

    private fun showTools() {
        if (canvasView.uiVisible) return
        canvasView.uiVisible = true
        toolbar.visibility = View.VISIBLE
        toolbar.post { canvasView.setBottomInset(toolbar.height.toFloat()) }
        refreshUi()
    }

    // =======================================================================
    // Object actions
    // =======================================================================

    private fun addBox() {
        val before = project.snapshot()
        // An average-size square, comfortably inside the canvas.
        val side = min(project.worldW, project.worldH) * 0.32f
        val box = Item(
            type = ItemType.BOX,
            x = project.worldW / 2f,
            y = project.worldH / 2f,
            w = side,
            h = side
        )
        project.add(box)
        history.push(before)
        canvasView.selectOnly(box.id)
        canvasView.mode = Mode.SHAPE
        activeTool = "shape"
        persist()
        refreshUi()
        toast("Drag to shape it")
    }

    private fun duplicate() {
        val sel = canvasView.selectedItems()
        if (sel.isEmpty()) {
            toast("Select something first")
            return
        }
        val before = project.snapshot()
        val offset = min(project.worldW, project.worldH) * 0.06f
        val copies = Arrange.duplicate(project, sel, offset)
        history.push(before)
        canvasView.clearSelection()
        canvasView.selectOnly(copies.last().id)
        copies.forEach { canvasView.selection.add(it.id) }
        canvasView.invalidate()
        persist()
        toast("Duplicated ${copies.size}")
    }

    private fun deleteSelection() {
        val sel = canvasView.selectedItems()
        if (sel.isEmpty()) {
            toast("Select something first")
            return
        }
        val before = project.snapshot()
        sel.forEach { project.remove(it) }
        history.push(before)
        project.refreshNumberCounter()
        project.refreshIdCounter()
        canvasView.clearSelection()
        canvasView.invalidate()
        persist()
        toast("Deleted")
    }

    /** Align selected boxes to the left-most object and match the first one's size as a guide. */
    private fun align() {
        val boxes = canvasView.selectedItems().filter { it.type == ItemType.BOX }
        if (boxes.size < 2) {
            toast("Select two or more boxes")
            return
        }
        val before = project.snapshot()
        Arrange.alignLeft(boxes)
        history.push(before)
        canvasView.invalidate()
        persist()
        toast("Aligned left")
    }

    /** Equalise the horizontal gaps between selected boxes, keeping the outer two fixed. */
    private fun distribute() {
        val boxes = canvasView.selectedItems().filter { it.type == ItemType.BOX }
        if (boxes.size < 3) {
            toast("Select three or more boxes")
            return
        }
        val before = project.snapshot()
        Arrange.spaceEvenly(boxes)
        history.push(before)
        canvasView.invalidate()
        persist()
        toast("Spacing evened")
    }

    private fun undo() {
        val prev = history.undo(project.snapshot()) ?: run {
            toast("Nothing to undo")
            return
        }
        project.restore(prev)
        canvasView.clearSelection()
        canvasView.invalidate()
        persist()
        toast("Undo")
    }

    private fun redo() {
        val next = history.redo(project.snapshot()) ?: run {
            toast("Nothing to redo")
            return
        }
        project.restore(next)
        canvasView.clearSelection()
        canvasView.invalidate()
        persist()
        toast("Redo")
    }

    private fun newProject() {
        history.reset()
        ProjectStore.clearCurrent(this)
        ProjectStore.pruneBackgrounds(this, null)
        project = Project()
        project.items.clear()
        canvasView.project = project
        canvasView.background = null
        canvasView.mode = Mode.NONE
        activeTool = ""
        canvasView.post { canvasView.fullView() }
        refreshUi()
        toast("New project")
    }

    private fun rotatePicture() {
        if (canvasView.background == null) { toast("Import a picture first"); return }
        val before = project.snapshot()
        val oldW = project.worldW
        val oldH = project.worldH
        val quarter = (project.backgroundRotation + 90) % 360
        fun point(x: Float, y: Float): Pair<Float, Float> = oldH - y to x
        project.items.forEach { item ->
            val p = point(item.x, item.y)
            item.x = p.first
            item.y = p.second
            if (item.type == ItemType.LINE) {
                val q = point(item.x2, item.y2)
                item.x2 = q.first
                item.y2 = q.second
            }
            val t = item.w; item.w = item.h; item.h = t
        }
        project.worldW = oldH; project.worldH = oldW
        project.backgroundRotation = quarter
        history.push(before)
        canvasView.project = project
        canvasView.fullView()
        persist()
        toast("Picture rotated ${quarter}°")
    }

    // =======================================================================
    // Background import — keep the source resolution and aspect ratio
    // =======================================================================

    private fun importBackground(uri: Uri) {
        val name = ProjectStore.importBackground("bg.png")
        val target = ProjectStore.backgroundFile(this, name)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            toast("Could not read that image")
            return
        }

        val opts = BitmapFactory.Options().apply {
            // Keep the imported source at its original pixel dimensions.
            inSampleSize = 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) {
            toast("Could not read that image")
            return
        }

        runCatching {
            FileOutputStream(target).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.onFailure {
            toast("Could not save that image")
            return
        }

        val before = project.snapshot()
        project.bgFile = name
        // Use the source image's actual pixel dimensions as the mockup world.
        // The viewport may fit it visually, but the source and export remain exact.
        project.sourceW = srcW.toFloat()
        project.sourceH = srcH.toFloat()
        project.worldW = srcW.toFloat()
        project.worldH = srcH.toFloat()
        history.push(before)

        ProjectStore.pruneBackgrounds(this, name)
        canvasView.background = bmp
        canvasView.project = project
        canvasView.invalidate()
        canvasView.post { canvasView.fullView() }
        persist()
        toast("Background imported at $srcW x $srcH")
    }

    private fun loadBackground(name: String?): Bitmap? {
        if (name == null) return null
        val f = ProjectStore.backgroundFile(this, name)
        if (!f.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            })
        }.getOrNull()
    }

    // =======================================================================
    // Output
    // =======================================================================

    private fun format(value: Float): String = String.format(Locale.US, "%.4f", value)

    private fun markText(mark: Item): String = buildString {
        append("${mark.number}. ${mark.name.ifBlank { "Mark ${mark.number}" }}\n")
        append("X: ${format(mark.x / project.worldW)}\n")
        append("Y: ${format(mark.y / project.worldH)}\n")
        append("W: ${format(mark.w / project.worldW)}\n")
        append("H: ${format(mark.h / project.worldH)}")
    }

    private fun clipboard(value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Touch Toolkit marks", value))
        toast("Copied to clipboard")
    }

    private fun copyMarks() {
        val marks = project.items.filter { it.type == ItemType.MEASURE }.sortedBy { it.number }
        if (marks.isEmpty()) { toast("No measure marks yet"); return }
        clipboard("TOUCH TOOLKIT MEASUREMENTS\nCanvas: ${project.orientation.label}\n\n" +
            marks.joinToString("\n\n") { markText(it) })
    }

    private fun editMark(mark: Item) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(22), pad(8), pad(22), pad(8))
        }
        fun field(label: String, value: String): EditText = EditText(this).also {
            it.hint = label
            it.setSingleLine(true)
            it.setText(value)
            if (label != "Name") it.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            layout.addView(it)
        }
        val name = field("Name", mark.name)
        val x = field("X (0–1)", format(mark.x / project.worldW))
        val y = field("Y (0–1)", format(mark.y / project.worldH))
        val w = field("Width (0–1)", format(mark.w / project.worldW))
        val h = field("Height (0–1)", format(mark.h / project.worldH))
        val dialog = AlertDialog.Builder(this)
            .setTitle("MARK ${mark.number}")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNeutralButton("Skip name", null)
            .setNegativeButton("Copy mark") { _, _ -> clipboard(markText(mark)) }
            .create()
        dialog.setOnShowListener {
            fun save(skipName: Boolean) {
                val values = listOf(x, y, w, h).map { it.text.toString().toFloatOrNull() }
                if (values.any { it == null || it !in 0f..1f }) {
                    toast("Use values from 0 to 1"); return
                }
                val before = project.snapshot()
                mark.name = if (skipName) "" else name.text.toString().trim()
                mark.x = values[0]!! * project.worldW
                mark.y = values[1]!! * project.worldH
                mark.w = values[2]!! * project.worldW
                mark.h = values[3]!! * project.worldH
                history.push(before)
                persist()
                canvasView.invalidate()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { save(true) }
        }
        dialog.show()
    }

    /**
     * Render the mockup with no editing chrome and share it as a PNG.
     * Rendered at the project's own resolution so imported artwork keeps its detail.
     */
    private fun shareMockup() {
        // The user holds the phone in the intended final orientation before saving,
        // so read the live device orientation and record it on the project.
        val deviceOrientation = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT
        project.orientation = deviceOrientation
        persist()

        // Export from the project canvas, never from the on-screen viewport.
        // The previous code could retain a stale project height after an image
        // was imported/rotated, producing a valid PNG with the lower portion
        // left black by the share preview.
        val sourceW = project.sourceW.roundToInt().coerceAtLeast(1)
        val sourceH = project.sourceH.roundToInt().coerceAtLeast(1)
        val currentW = project.worldW.roundToInt().coerceAtLeast(1)
        val currentH = project.worldH.roundToInt().coerceAtLeast(1)
        val rotated = project.backgroundRotation
        val rendered = Bitmap.createBitmap(currentW, currentH, Bitmap.Config.ARGB_8888)
        canvasView.drawScene(Canvas(rendered), 1f, 0f, 0f,
            currentW.toFloat(), currentH.toFloat())

        // Rotation is an editing/view operation. The shared file is always
        // returned in the imported bitmap's original pixel dimensions and
        // orientation, never in the phone viewport's dimensions.
        val out = Bitmap.createBitmap(sourceW, sourceH, Bitmap.Config.ARGB_8888)
        val exportCanvas = Canvas(out)
        when (rotated) {
            90 -> {
                exportCanvas.translate(0f, sourceH.toFloat())
                exportCanvas.rotate(-90f)
                exportCanvas.drawBitmap(rendered, 0f, 0f, null)
            }
            180 -> {
                exportCanvas.translate(sourceW.toFloat(), sourceH.toFloat())
                exportCanvas.rotate(180f)
                exportCanvas.drawBitmap(rendered, 0f, 0f, null)
            }
            270 -> {
                exportCanvas.translate(sourceW.toFloat(), 0f)
                exportCanvas.rotate(90f)
                exportCanvas.drawBitmap(rendered, 0f, 0f, null)
            }
            else -> exportCanvas.drawBitmap(rendered, 0f, 0f, null)
        }
        rendered.recycle()

        val name = "touch-toolkit-${deviceOrientation.label.lowercase()}.png"
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val file = File(dir, name)
        runCatching {
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure {
            toast("Could not create the image")
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Touch Toolkit mockup (${deviceOrientation.label})")
            putExtra(
                Intent.EXTRA_TEXT,
                "Touch Toolkit mockup \u2014 ${deviceOrientation.label} screen"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${deviceOrientation.label} mockup"))
    }


    // =======================================================================

    private fun persist() {
        ProjectStore.saveCurrent(this, project)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The work surface and marks keep their coordinates; only the tool chrome
        // re-lays out to fit the new screen shape. The canvas adapts its viewport
        // itself once it has been measured, preserving the current zoom.
        toolbar.post {
            canvasView.setBottomInset(toolbar.height.toFloat())
            refreshUi()
        }
    }
}

/** Dismisses the tray only for a deliberate downward swipe that starts on it. */
internal class TraySwipeDismissListener(
    private val touchSlopPx: Float,
    private val dismissDistancePx: Float,
    private val onDismiss: () -> Unit
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var axis = Axis.UNDECIDED
    private var tracking = false

    private enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                axis = Axis.UNDECIDED
                tracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && axis == Axis.UNDECIDED) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) > touchSlopPx) {
                        // Lock direction once, so scrolling to either end of the
                        // horizontal tool lists can never turn into a dismissal.
                        axis = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                            Axis.HORIZONTAL
                        } else {
                            Axis.VERTICAL
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tracking && axis == Axis.VERTICAL) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dy >= dismissDistancePx && dy > kotlin.math.abs(dx) * 1.25f) onDismiss()
                }
                tracking = false
                axis = Axis.UNDECIDED
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                axis = Axis.UNDECIDED
            }
        }
        // Let the HorizontalScrollView continue handling its normal horizontal scroll.
        return false
    }
}
