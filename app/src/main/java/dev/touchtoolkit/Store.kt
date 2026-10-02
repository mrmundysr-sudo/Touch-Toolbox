package dev.touchtoolkit

import android.content.Context
import java.io.File

/**
 * Snapshot-based undo/redo. Only real canvas edits call [push]; camera changes
 * (zoom / pan / full view) never touch this stack.
 */
class History(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<ArrayList<Item>>()
    private val redoStack = ArrayDeque<ArrayList<Item>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun reset() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Record the state *before* an edit. */
    fun push(before: ArrayList<Item>) {
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(current: ArrayList<Item>): ArrayList<Item>? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    fun redo(current: ArrayList<Item>): ArrayList<Item>? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }
}

/** Local, on-device project storage. No accounts, no server. */
object ProjectStore {
    private const val DIR = "projects"
    private const val CURRENT = "current.json"
    private const val BG_PREFIX = "bg_"

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun currentFile(ctx: Context): File = File(dir(ctx), CURRENT)

    fun saveCurrent(ctx: Context, project: Project) {
        runCatching { currentFile(ctx).writeText(project.toJson().toString()) }
    }

    fun loadCurrent(ctx: Context): Project? = runCatching {
        val f = currentFile(ctx)
        if (!f.exists()) null else Project.fromJson(org.json.JSONObject(f.readText()))
    }.getOrNull()

    fun clearCurrent(ctx: Context) {
        runCatching { currentFile(ctx).delete() }
    }

    /** Build the storage name for an imported background. */
    fun importBackground(displayName: String): String {
        return "$BG_PREFIX${System.currentTimeMillis()}_${sanitize(displayName)}"
    }

    fun backgroundFile(ctx: Context, name: String): File = File(dir(ctx), name)

    /** Delete background files no longer referenced by the current project. */
    fun pruneBackgrounds(ctx: Context, keep: String?) {
        runCatching {
            dir(ctx).listFiles()?.forEach { f ->
                if (f.name.startsWith(BG_PREFIX) && f.name != keep) f.delete()
            }
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "bg.jpg" }
}
