# Touch Toolkit 1.3 build results

## What this build is

The corrected Touch Toolkit source, built into a working debug APK. The app is a
one-finger sketching tool for planning phone-app screen layouts.

## Version

- versionName: 1.3
- versionCode: 4
- applicationId: dev.touchtoolkit
- minSdk: 24
- targetSdk: 34
- compileSdk: 34

## Build and test commands

```
./gradlew clean assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

All three finished successfully.

- The debug APK appears at `app/build/outputs/apk/debug/app-debug.apk`.
- Unit tests: 72 tests, 0 failures.
- Lint: 0 errors, 15 warnings. Every warning is environment or housekeeping
  only. They are an older target SDK, newer library versions being available,
  unused resources and a clickable-view accessibility note.

## What changed in this build

The supplied v1.3 source was mid-refactor. The on-canvas floating controls had
been moved to the host toolbar, but several documented behaviours were missing
or broken, and the bundled tests still targeted the removed controls.

Fixed in the app:

1. The empty-space tap now toggles the tool panel again when no tool is chosen,
   which the project notes document. It had stopped working, and the toggle
   helper in the activity was unreachable dead code.
2. A drag that starts in the reserved bottom reveal strip can no longer pan the
   camera or edit the artwork on its way out. The strip is the only way to bring
   the panel back, so it must stay a pure reveal gesture.
3. The reveal flag is reset at the start of every gesture, so it can never leak
   from one touch into the next.

Fixed in the tests:

1. The zoom tests now use the Magnify tool rather than the removed on-canvas
   zoom control.
2. The old restore-handle tests were replaced with tests for the current design:
   the canvas draws no floating controls of its own, the whole surface is plain
   white, and the reveal strip stays a shallow sliver at the bottom edge.
3. Tests that placed marks near the bottom edge now clear the shallow reveal
   sliver, and a new test confirms the reveal drag restores the panel without
   placing anything.
4. The rotation test now checks that artwork keeps its world coordinates and the
   user's zoom survives a screen-shape change.

## The Gradle wrapper

The supplied archive had no Gradle wrapper, so it could only be built with a
Gradle installed by hand. A wrapper for Gradle 8.9 is now included, so
`./gradlew` works out of the box. A local `local.properties` file is still
needed to point at an Android SDK, or you can set the `ANDROID_HOME`
environment variable.

## Toolchain used

- JDK 21
- Android SDK platform 34
- Gradle 8.9

## Source and APK match

Before packaging, the source tree was checked against the APK. No source or
build file is newer than the APK, so the two correspond exactly.
