# Touch Toolkit 1.3 testing checklist

A short manual check of the finished app. Each line is one thing to try. Tick it
if it behaves as described.

## Getting started

1. Install `TouchToolkit-1.3-debug.apk` on the phone.
2. Open the app. A plain white drawing surface fills the screen with a tool bar
   at the bottom.

## Drawing

3. Tap Shape, then tap New Box. Drag on the box: right makes it wider, left makes
   it narrower, up makes it taller, down makes it shorter.
4. Tap Move and drag an object. It moves and keeps its size.
5. Tap Line and tap twice to draw a line between the two points.
6. Tap Dot and tap once to place a dot.
7. Tap Number and tap several times. The numbers count up 1, 2, 3.
8. Tap Move View and drag. The picture pans and the objects stay put.
9. Tap Magnify and drag up or down. The view zooms in and out, and stays zoomed
   when you lift your finger.

## Hiding the tool panel

10. Swipe down on the tool bar. The panel slides away and the drawing fills the
    screen.
11. With a tool still chosen, keep drawing where the panel used to be. It should
    still work right down to the bottom edge.
12. With no tool chosen, tap an empty part of the screen. The panel hides, and a
    second tap on empty space brings it back.
13. With the panel hidden, drag up from the very bottom edge. The panel returns
    and the drawing does not move.

## Editing and history

14. Tap Undo and Redo. Your changes step backwards and forwards.
15. Tap Duplicate, Delete, Select All, Align Left and Space Evenly with something
    selected. Each does what its name says.

## Saving and sharing

16. Tap Share Mockup. The phone's share sheet opens with a PNG of the drawing.
17. Tap New Project, then close and reopen the app. Your drawing is still there.

## Turning the phone

18. Turn the phone sideways. The drawing keeps its shape and zoom, and the tool
    bar re-lays out to fit the wider screen.

## Background image

19. Tap Import Image and pick a picture. It appears behind your marks.
20. Export the drawing. The background image is included in the shared PNG.

## If something looks wrong

Take a screenshot and note the step number above. Nothing in the app should ever
crash or lose your drawing.
