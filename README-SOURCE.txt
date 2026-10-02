Touch Toolkit - v1.1 source code
================================

An Android app for sketching phone-app screen layouts with one finger.

  app/src/main/java/dev/touchtoolkit/
    MainActivity.kt     activity, toolbar, modes, import/export, persistence
    CanvasView.kt       custom view: drawing, hit testing, all one-finger gestures
    Geometry.kt         Camera (viewport) and ShapeMath/Arrange helpers
    Model.kt            Project, Item, ItemType, ScreenOrientation, History
    Store.kt            on-device project + background image storage
    Mode.kt             mode enum with labels and hints
  app/src/main/res/     manifest resources, drawables, theme, file_paths
  app/src/test/java/dev/touchtoolkit/
    TouchToolkitTest.kt       logic tests (geometry, history, persistence)
    CanvasViewTouchTest.kt    Robolectric tests driving real MotionEvents
    CanvasRenderTest.kt       pixel tests for the white, edge-to-edge surface

HOW TO BUILD
------------
Requirements:
  - JDK 17 or newer (built and tested with JDK 21)
  - Android SDK with platform 34 and build-tools (ANDROID_HOME set)
  - Gradle 8.9

There is no Gradle wrapper in this archive, so use a local Gradle 8.9:

  1. Point the build at your SDK. Either create local.properties:
         sdk.dir=/path/to/Android/sdk
     or set the ANDROID_HOME environment variable.
  2. Build the debug APK:
         gradle assembleDebug
  3. Output lands in:
         app/build/outputs/apk/debug/app-debug.apk

Run the unit tests with:
         gradle testDebugUnitTest
     (The touch tests use Robolectric and need network access on first run
      to fetch its Android runtime jars.)

KEY DESIGN NOTES
----------------
- View, Shape and Move are deliberately separate operations and never share
  a gesture. Camera changes (zoom/pan/full view) never touch object geometry
  and never enter the undo history.
- A new project is a plain white surface drawn edge to edge: no dark
  surround, no page boundary. It is locked, so it can never be selected,
  moved, resized or reshaped. Empty-space taps are never edits.
- The fitted view is also the zoom-out floor, so the surface always fills
  the screen. Zoom is a press-and-hold magnifier: vertical drag from the
  control zooms continuously and can be stopped, reversed and resumed
  within one touch. The magnification persists after release, so editing
  continues zoomed in.
- Shaping is centre-anchored: drag up = taller, down = shorter, right = wider,
  left = narrower, diagonal changes both; 2x is applied so the edge follows
  the finger. A box is only shaped when the drag starts on it.
- Rotating the phone changes nothing about the artwork. Every mark keeps its
  world coordinates, the zoom is preserved, and only the tool controls
  re-lay out for the new screen shape.
- Hit testing uses generous invisible padding so thin lines and small dots
  stay easy to select; this never affects the exported artwork.
- Project state is saved locally as JSON plus the background image file.
  Files written by earlier versions (which contained NORTHSTAR items) still
  load, with only the obsolete marker dropped.
- Export renders the scene at project resolution without any editing chrome,
  as a PNG, and shares it via FileProvider. The device orientation at save
  time is recorded in the filename and the share note, so the receiver knows
  which way the mockup is meant to be read.
