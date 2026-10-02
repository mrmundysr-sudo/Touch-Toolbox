package dev.touchtoolkit

import org.json.JSONArray
import org.json.JSONObject

enum class ItemType { BOX, LINE, DOT, NUMBER, MEASURE }

/**
 * A single markup object. Geometry is stored in "world" coordinates, which are
 * independent of the screen so marks stay put while zooming and rotating.
 *
 * BOX / DOT / NUMBER store their centre in (x, y).
 * LINE stores its start in (x, y) and its end in (x2, y2).
 *
 * The workspace surface itself is deliberately not an Item: it cannot be
 * selected, moved, resized or reshaped.
 */
class Item(
    var type: ItemType = ItemType.BOX,
    var x: Float = 0f,
    var y: Float = 0f,
    var w: Float = 0f,
    var h: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    var number: Int = 0,
    var id: Long = 0L,
    var name: String = "",
    /** Text scale for placed NUMBER marks; defaults to full size for older projects. */
    var numberScale: Float = 1f,
) {
    fun clone(): Item = Item(type, x, y, w, h, x2, y2, number, id, name, numberScale)

    fun translate(dx: Float, dy: Float) {
        x += dx
        y += dy
        if (type == ItemType.LINE) {
            x2 += dx
            y2 += dy
        }
    }

    fun left(): Float = when (type) {
        ItemType.BOX -> x - w / 2f
        ItemType.LINE -> minOf(x, x2)
        else -> x
    }

    fun top(): Float = when (type) {
        ItemType.BOX -> y - h / 2f
        ItemType.LINE -> minOf(y, y2)
        else -> y
    }

    fun right(): Float = when (type) {
        ItemType.BOX -> x + w / 2f
        ItemType.LINE -> maxOf(x, x2)
        else -> x
    }

    fun bottom(): Float = when (type) {
        ItemType.BOX -> y + h / 2f
        ItemType.LINE -> maxOf(y, y2)
        else -> y
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("w", w.toDouble())
        put("h", h.toDouble())
        put("x2", x2.toDouble())
        put("y2", y2.toDouble())
        put("number", number)
        put("id", id)
        put("name", name)
        put("numberScale", numberScale.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): Item? {
            // Files saved before North Star was removed may still contain markers.
            val raw = o.optString("type", "BOX")
            val type = runCatching { ItemType.valueOf(raw) }.getOrNull() ?: return null
            return Item(
                type = type,
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                w = o.optDouble("w", 0.0).toFloat(),
                h = o.optDouble("h", 0.0).toFloat(),
                x2 = o.optDouble("x2", 0.0).toFloat(),
                y2 = o.optDouble("y2", 0.0).toFloat(),
                number = o.optInt("number", 0),
                id = o.optLong("id", 0L),
                name = o.optString("name", ""),
                numberScale = o.optDouble("numberScale", 1.0).toFloat().coerceIn(0.5f, 1f),
            )
        }
    }
}

/** Whether the intended phone screen is taller or wider than it is broad. */
enum class ScreenOrientation(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape");

    companion object {
        fun of(width: Float, height: Float): ScreenOrientation =
            if (height >= width) PORTRAIT else LANDSCAPE
    }
}

/** Everything that makes up a single mockup: one orientation, one background, one markup set. */
class Project {
    var worldW: Float = 1080f
    var worldH: Float = 1920f

    /**
     * Immutable pixel dimensions of the imported bitmap. worldW/worldH may
     * swap while the user rotates the editing view, but export must always use
     * these original dimensions.
     */
    var sourceW: Float = worldW
    var sourceH: Float = worldH

    /** The orientation this project's screen represents. Recorded at save time. */
    var orientation: ScreenOrientation = ScreenOrientation.PORTRAIT

    /** File name (inside the app's projects dir) of the background PNG, if any. */
    var bgFile: String? = null
    var backgroundRotation: Int = 0

    val items = ArrayList<Item>()

    var nextNumber: Int = 1
    var nextMeasure: Int = 1
    var nextId: Long = 1L

    fun add(item: Item): Item {
        item.id = nextId++
        items.add(item)
        return item
    }

    fun remove(item: Item) {
        items.remove(item)
    }

    fun byId(id: Long): Item? = items.firstOrNull { it.id == id }

    fun refreshIdCounter() {
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    fun refreshNumberCounter() {
        nextNumber = (items.filter { it.type == ItemType.NUMBER }.maxOfOrNull { it.number } ?: 0) + 1
        nextMeasure = maxOf(nextMeasure, (items.filter { it.type == ItemType.MEASURE }.maxOfOrNull { it.number } ?: 0) + 1)
    }

    fun snapshot(): ArrayList<Item> = ArrayList(items.map { it.clone() })

    fun restore(snap: List<Item>) {
        items.clear()
        snap.forEach { items.add(it.clone()) }
        refreshIdCounter()
        refreshNumberCounter()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("worldW", worldW.toDouble())
        put("worldH", worldH.toDouble())
        put("sourceW", sourceW.toDouble())
        put("sourceH", sourceH.toDouble())
        put("orientation", orientation.name)
        put("bgFile", bgFile ?: JSONObject.NULL)
        put("backgroundRotation", backgroundRotation)
        put("nextNumber", nextNumber)
        put("nextMeasure", nextMeasure)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        put("items", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Project = Project().apply {
            worldW = o.optDouble("worldW", 1080.0).toFloat()
            worldH = o.optDouble("worldH", 1920.0).toFloat()
            sourceW = o.optDouble("sourceW", worldW.toDouble()).toFloat()
            sourceH = o.optDouble("sourceH", worldH.toDouble()).toFloat()
            orientation = runCatching { ScreenOrientation.valueOf(o.optString("orientation", "")) }
                .getOrElse { ScreenOrientation.of(worldW, worldH) }
            bgFile = if (o.isNull("bgFile")) null else o.optString("bgFile").takeIf { it.isNotEmpty() }
            backgroundRotation = ((o.optInt("backgroundRotation", 0) % 360) + 360) % 360
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                Item.fromJson(arr.getJSONObject(i))?.let { items.add(it) }
            }
            refreshIdCounter()
            refreshNumberCounter()
            nextMeasure = maxOf(nextMeasure, o.optInt("nextMeasure", 1))
        }
    }
}
