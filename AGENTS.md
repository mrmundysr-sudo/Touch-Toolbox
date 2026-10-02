# Touch Toolkit — agent notes

Android app (`dev.touchtoolkit`) for sketching phone-app screen layouts with one
finger. Source root is this directory; download artifacts live in `.download/`.

## Toolchain (reinstall after an environment reset)

The Gradle wrapper (Gradle 8.9) is committed, so `./gradlew` works once a JDK
and an Android SDK are present. The SDK itself is not committed. What worked:

```
sudo apt-get install -y openjdk-21-jdk-headless unzip
# Android command line tools, then:
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$HOME/android-sdk" > local.properties
```

Build and test (export JAVA_HOME and ANDROID_HOME first):

```
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The first Robolectric run needs network access to fetch its Android runtime jars.
sdkmanager is long-running: start it in the background and poll its log rather
than waiting on it in the foreground.

## Build commands run long

Gradle invocations exceed the terminal soft timeout. Run them with `nohup ...
> /tmp/log 2>&1 &` and poll the log, otherwise a build looks like a failure.

## Architecture

- `Mode.kt` — the user explicitly picks what the finger does. Never infer intent
  from a gesture: SHAPE, MOVE, LINE, DOT, NUMBER, PAN are separate modes.
- `CanvasView.kt` — all custom drawing, hit testing and one-finger gestures.
- `Geometry.kt` — `Camera` (viewport) and `ShapeMath`/`Arrange` helpers.
- `Model.kt` — `Project`, `Item`, `ItemType`, `ScreenOrientation`, `History`.
- `Store.kt` — on-device JSON project + background image file. `MainActivity.kt`
  owns the chrome, import, and export.

## Invariants worth preserving

- View, Shape and Move never share a gesture. Camera changes never touch object
  geometry and never enter the undo history.
- The canvas is a plain white surface drawn edge to edge. The window background
  and system bars are white to match, so there is no dark surround or page
  boundary. The surface is locked: nothing may select, move, resize or reshape
  it, and an empty-space tap is never an edit.
- Hiding the tool panel must never change or disarm the active tool. The chosen
  tool keeps working across the whole canvas, including the strip the panel
  covered, and restoring the panel leaves the tool alone. An empty-space tap
  only toggles the panel when no tool is chosen; with a tool chosen every tap
  belongs to that tool. Toggle the panel with a tool active via the explicit
  Hide Tools / SHOW TOOLS control (canvasView.uiVisible from the host, or the
  floating handle on the canvas). A drag starting on the floating handle must
  never pan or edit.
- The fitted view is the zoom-out floor, so the surface always fills the screen.
- Zoom is a press-and-hold magnifier using one continuous vertical drag, and the
  magnification persists after release so editing continues while zoomed in.
- Box shaping is centre-anchored and only starts when the drag begins on the box.
- Rotation must not disturb artwork. Every mark keeps its world coordinates, the
  zoom is preserved, and only the tool control geometry re-lays out.
- Hit testing uses generous invisible padding; it must never change exported
  geometry.
- Export renders at project resolution with no chrome and records the device
  orientation in the filename and share note.

## Tests

`TouchToolkitTest` (logic), `CanvasViewTouchTest` (Robolectric driving real
MotionEvents), `CanvasRenderTest` (native-mode pixel tests for the white
edge-to-edge surface). When a touch test fails, check the harness first: the
`onToggleUi` stub has to flip `uiVisible` the way the host does, and zoom
gesture assertions must follow the spec (drag up = zoom in).

## Download artifacts

`.download/serve.py` serves the APK and ZIPs with download-friendly headers and
several path aliases, on port 12000. There are two public hosts with separate
ports (see the work-hosts context). After a rebuild, regenerate the APK copy,
the distribution ZIP and the source ZIP, update `serve.py` filenames, and verify
the checksums over the public URL.
