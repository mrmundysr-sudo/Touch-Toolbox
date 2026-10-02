package dev.touchtoolkit

/** The user explicitly picks what the finger is about to do. Never guessed from a gesture. */
enum class Mode(val label: String, val hint: String) {
    NONE("READY", "Pick a tool. The chosen tool works across the whole canvas"),
    SHAPE("SHAPE", "Drag on the box: up = taller, right = wider (centre stays put)"),
    MOVE("MOVE", "Tap an object, then drag it. Centre never changes here"),
    SELECT("SELECT", "Tap one markup to select it, then choose an action"),
    LINE("LINE", "Tap the start point, then tap the end point"),
    DOT("DOT", "Tap to place a centre point"),
    NUMBER("NUMBER", "Tap to place the next number"),
    MEASURE("MEASURE MARK", "Tap to place or edit an X mark"),
    PAN("MOVE VIEW", "Drag to move your viewpoint. Objects are untouched"),
    MAGNIFY("MAGNIFY", "Drag up to zoom in, down to zoom out"),
}
