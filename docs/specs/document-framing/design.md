# Design — Live document framing over the preview

Accompanies v1.6.0, and records the defect v1.6.1 fixes (§4a). The PRD's acceptance criteria are
cited as AC-n.

## 0. Method, and what counts as evidence here

Two constraints shaped every decision below:

1. **The imaging core stays framework-free.** Plain `IntArray` / `GrayImage`, no `android.graphics`,
   no Compose, no OpenCV, so it runs in plain JVM tests. The parts that decide *where the border is*
   and *whether to move it* are therefore in `core/imaging/`, and only the drawing is Compose.
2. **No device at the keyboard.** This environment had no attached device, so nothing about the
   overlay's on-screen appearance or its performance on the phone is claimed to be verified. The
   DEVICE items below are recorded as unverified by me, and the owner verifies them.

The competing scanner's behaviour was read from decompiled sources rather than from memory. Every
file and line cited in §1 was opened in this environment; the one number whose consumer could not
be recovered is marked as such.

## 1. What the competing scanner actually does

**F1 — Detection runs on the preview stream, in native code.** `DetectPreviewBorderHandle` hands the
frame's bytes to `ScannerUtils.detectFrameBorder(byte[], width, height, out[8])`
(`camscanner/scanner/ScannerUtils.java:330`), which is a JNI wrapper over
`PageScanner.DetectFrameBorder(...)` (`nativelib/PageScanner.java:74`) — eight ints out, which is
four corners. A second result is produced by `ScannerUtils.detectCurveBorder(...)`
(`ScannerUtils.java:305`), and the two are arbitrated in native code by
`PageScanner.detectBorderModeIoU(...)` (`nativelib/PageScanner.java:170`); mode `2` selects the curve
corners, anything else the straight ones (`DetectPreviewBorderHandle.java:294-300`). So the live
border is a first-class, continuously-updated measurement, not a one-off.

**F2 — The work is throttled by a single-in-flight dispatcher, not by a timer.**
`AbstractPreviewHandle` owns a `HandlerThread("PreviewDataHandle")`
(`capture/preview/AbstractPreviewHandle.java:65`) and a busy flag: a frame is dispatched only when
the flag is idle, the flag is cleared while a detection is outstanding and set again when it
completes. There is also a minimum-interval timestamp field, which is never given a value in this
build, so in practice the back-pressure — *one detection at a time* — is what paces detection.

**F3 — Frames are also skipped outright.** `AutoDrawBorderHandle` carries a frame counter and
multiplies it by a configured factor (`AutoDrawBorderHandle.java:87`), and the whole path runs on a
thread named `workbench_find_border` (`:92`). Its constructor passes a **5 dp** value to the base
handle — the only density-independent constant in the entire border path
(`AutoDrawBorderHandle.java:208`, `super(DisplayUtil.m126575o(context, 5), z)`; the only other dp
values in the package are in a debug panel). The per-corner comparison that consumes it lives in a
Kotlin lambda that jadx could not decompile, so the *reader* of the 5 dp is inferred from position
rather than read: it is the constant the live border path is built around.

**F4 — Nothing snaps, and nothing shimmers.** `BorderView` draws the shade as one path holding the
whole canvas *and* the page quad with `Path.FillType.EVEN_ODD`
(`view/BorderView.java:338`) — the hole-punch that leaves only the page undimmed. The border is a
`STROKE` of width `2`, divided by the view's minimum scale at draw time (`:270`, `:360`), so it stays
two density-independent pixels wide however the preview is scaled. It repaints on a fixed 16 ms
cadence (`:261`, `:389` `postInvalidateDelayed(16L)`) and moves through a `BorderScroller` tween
rather than by assignment.

**F5 — The border colour is teal.** `BorderView.java:271` sets `-15090532`, which is `0xFF19BC9C` —
a green-teal, not the system accent and not white.

**F6 — The border is not gated on the automatic shutter.** The border path is fed by the same
preview data the auto-capture state machine consumes, and draws regardless of which auto-capture
state is current. The toggle governs when the shutter fires, not whether the user can see the page.

## 2. What was taken, and what was not

| F | Taken | Why |
|---|---|---|
| F1 | A live outline, re-detected continuously | This is the feature (AC-1, AC-2) |
| F2 | Detection asked for on a cadence the tracker owns | Simpler than a dispatcher thread; the tracker's `dueForDetection` gives the same back-pressure on the analysis executor, which is already single-threaded |
| F3 | The **5 dp** deadband; frames skipped between detections | Corner-for-corner the same tolerance, so the border behaves the way the owner has seen it behave |
| F4 | Even-odd dim, 2 dp stroke that does not scale, a tween between positions, a ~60 Hz redraw | The whole "calm border" contract (AC-2, AC-3) |
| F5 | A teal from this app's own palette | The competing border is teal; `MaterialTheme.colorScheme.primary` is a *dark* teal in light mode and would be lost over a photograph |
| F6 | Always on, no setting | AC-8; matching the competing behaviour |

Not taken: F1's curve arbitration (this app's crop model is a four-corner quad all the way to the
warp, so a fitted curve would have to be carried through the editor and the warp — a feature of its
own), and the amber out-of-frame state, which is scope in the PRD's §4 rather than an omission.

## 3. Where detection runs

The camera already had an analyser, `AutoCaptureAnalyzer`, measuring each preview frame for the
auto-capture gate. Two changes were needed and no third:

- **One grayscale frame, two consumers.** The analyser's luma conversion is reused: after the gate
  has been fed, the tracker is asked whether a detection is due, and only then is
  `DocDetector.detect(gray, ...)` called. The gate itself is untouched.
- **Analysis is always bound.** Previously the `ImageAnalysis` use case was created only when the
  automatic shutter was on. It is now always bound, because the overlay always needs frames, and
  because unbinding a use case to flip a toggle restarts the preview — which would be visible, and
  is AC-10. `autoCapture` was correspondingly removed from the preview `LaunchedEffect`'s keys, and
  the gate's `onFire` is now guarded by `if (autoCapture)`.

Values, and where each comes from:

| Value | Setting | Source |
|---|---|---|
| Working edge | 320 px | Measured here: median 15.3 ms per detection on a 16-core desktop JVM at this edge, versus 21.9 ms at 480 and 29.6 ms at 640, with 97–98 % confidence at all three. A phone is expected to be 2–4× slower; 320 keeps the cost near the throttle interval |
| Interval | 150 ms | ~7 detections/s: frequent enough to track a hand, sparse enough that detection is a few percent of the frame budget |
| Confidence gate | 0.45 | Just below the detector's own accept threshold (0.52) |
| Fallbacks | **off** | The fallbacks exist for a still photograph, where "a box around the text" beats nothing. Live, that box is a rectangle hugging the ink, which the user would read as the page edge — actively misleading. The overlay draws an edge-detection outline or nothing |

The tracker works in **view** space, not frame space: `accept` maps the quad through
`FrameGeometry.toView` before comparing corners. That is deliberate — the 5 dp deadband is then
exactly 5 dp on screen, which is the tolerance F3 measured. A deadband in frame pixels would drift
in real terms every time the preview's scale changed.

The outing of the live path — `Luma.grayFromPlane` decimates rather than averages — is a known
difference from the still path and is recorded in the README's known issues rather than papered over.

The plane is read through `Luma.sampleFrame`, which returns the sampled image and the `PreviewFrame`
describing *it*, as one value. They may not be separated: §4a records what separating them cost.

## 4. FrameGeometry — where the outline is drawn

Two transformations sit between a detected quad and the drawn one, and both are places where a
quarter-turn error hides quietly:

1. **The frame's rotation.** `ImageInfo.rotationDegrees` says how far the analysis buffer must be
   turned clockwise to be upright. Detection runs on the buffer as delivered, so the quad must be
   turned with it.
2. **The preview's layout.** `PreviewView` with `ScaleType.FILL_CENTER` scales the upright frame by
   the *larger* of the two ratios and centres it, cropping the overflow.

Both are `FrameGeometry`'s job (`core/imaging/FrameGeometry.kt`), framework-free and unit tested.
It exposes the mapping as a value (`FrameMap`: a scale and an offset) so the caller builds it once
per layout change instead of recomputing arithmetic inside the draw. `PreviewFrame(width, height,
rotationDegrees)` carries the frame; `orientedSize` and `orient` handle the turn — implemented by
repeating `Transforms.rotateQuad90`, so the preview agrees with the editor, which uses the same
function; `fillCenter` handles the layout; `toView` composes them.

Rotation is *rounded* to the nearest quarter turn rather than validated against 0/90/180/270. An
overlay drawn a quarter turn out for one frame is a spectacle, but it beats a border that is
silently absent because a device reported 270.5.

## 4a. The defect v1.6.1 fixes: which frame the quad is in

v1.6.0 drew the border off the page on the owner's very first shot. §8's first risk expected a
quarter-turn error; what the device found was an error in the frame's *size*.

**Symptom, from the owner's two photographs of the camera screen.** The border was drawn at a
fraction of the page's size, thrown to the right, with its right-hand edge running outside the
phone; the dimmed area covered the wrong part of the picture, which is the same error seen from the
other end.

**Cause.** `Luma.grayFromPlane` decimates — it reads every `step`-th pixel — so the `GrayImage`
handed to `DocDetector.detect` is `1/step` of the analysis frame: 2 at the 640x480 the analyser
requests, 5 at 1600x1200, 13 at 4032x3024. The detector answers in *that* image's pixels.
`AutoCaptureAnalyzer` reported the answer as `PreviewFrame(image.width, image.height, …)` — the
camera's size. Two consequences, both visible on the phone:

1. `fillCenter` scaled by `viewLongest / cameraLongest`, which is `1/step` of the true scale, so the
   border was drawn at `1/step` of its size.
2. `Transforms.rotateQuad90` reflects with `(srcHeight - 1 - y)`, so the camera's height instead of
   the sampled one displaced the quad sideways by `cameraHeight - sampledHeight` before scaling —
   the reason the error was not a neat shrink about the middle but a border thrown to one side.

**Measured on the JVM** (`LiveOutlineTest`'s fixture: a page photographed to fill a 640x480 frame,
rotation 90, a 1080x2400 view). The page's own corners are on screen at (-214, 300), (1361, 225),
(1286, 2175), (-289, 2100). Described by the sampled frame, the detected outline reproduces them to
within 5 px. Described by the camera's frame, the same outline is drawn at (611, 148), (1402, 110),
(1363, 1088), (573, 1050): half size, pushed up and to the right, its right-hand edge 322 px past a
1080 px screen.

**Fix.** The two facts are no longer separable. `Luma.sampleFrame` returns `Luma.SampledFrame` — the
sampled image and the `PreviewFrame` for it — so the analyser can only pass on the frame that was
searched. `LiveOutlineTest` pins both halves: the border lands on the page at all four rotations
(within 20 px, the detector's own error at a 320 px working edge), and the camera's size for a
sampled quad is more than 200 px out, asserted so that restoring the old bookkeeping fails loudly.
`CaptureTest` pins the sampling contract directly: a 640x480 plane at `targetEdge` 320 is described
as 320x240, not as 640x480.

## 5. OutlineTracker — what to draw now

The tracker (`core/imaging/OutlineTracker.kt`) is the whole of the "calm border" decision, and it is
one class rather than the three originally sketched (throttle, smoother, gate): they shared all
their state, and splitting them would have meant three objects to keep consistent instead of one
rule each.

- `dueForDetection(now)` — the interval. Every frame that returns false is a frame whose detection is
  skipped outright.
- `accept(detection, frame, viewWidth, viewHeight, now)` — a new answer. Dropped entirely if the view
  has no size yet (there is nowhere to draw, and drawing in the wrong place is worse than not
  drawing) or if the answer is missing or below the confidence gate. Otherwise: the first outline of
  a run appears where it is found; a later one within the deadband is ignored; anything further is
  tweened to, *from where the outline currently is*, so a second move arriving mid-tween continues
  the slide instead of jumping back to the first position.
- `sample(now)` — the outline to draw at this instant, `null` when there is none. Called once per
  frame by the screen, not once per detection, because the tween has to be evaluated per frame.

The hold is checked in `sample`, not in `accept`: a page that goes out of view produces *no*
detections rather than bad ones, so the case that must lift the border is the case where `accept` is
never called. An earlier version checked the hold only when no tween existed, which meant an outline
that went stale while a tween was still running was kept forever; the test
`the outline goes once nothing trustworthy is detected` caught it, and the check now runs first and
clears both the tween and the shown outline.

Defaults: `150 ms` interval, `0.45` confidence, `120 ms` tween, `400 ms` hold; the deadband is the
caller's, because it is a density — the screen passes `5.dp.toPx()`.

Concurrency is `@Synchronized` on all three public methods. Detection runs on the analysis executor
and drawing on the main thread, and the fields involved are a handful of references and quads, so a
lock is cheaper than the alternatives and much easier to be sure of.

## 6. Drawing

`FramingOverlay` (`ui/camera/FramingOverlay.kt`) is a `Canvas` that draws two things and returns
early when the outline is null:

- the shade, as F4 does it: one path with `PathFillType.EVEN_ODD` holding the full canvas and the
  outline, filled with black at `0.42` alpha, so only the page is undimmed;
- the border, `2.dp` wide in `ScanTealLight`, `StrokeJoin.Round`.

It receives view-space coordinates, so the composable does no arithmetic. It sits between the
`PreviewView` and the composition grid in the preview's `Box`, over the `AndroidView` but under the
controls — so it can never intercept a touch, which is AC-7.

## 7. Tests

35 tests cover the four pieces — 29 of them new — all plain JVM:

- `FrameGeometryTest` (11) — each of the four rotations against hand-computed expectations, a
  lopsided fixture so a no-op turn cannot pass, turns counted modulo a full turn, the fill-and-centre
  mapping for a square view, a matching-aspect view and an unmeasured view, and a case asserting that
  turning the *device* does not move the outline on screen.
- `OutlineTrackerTest` (13) — nothing drawn before a detection; first outline appears where found; the
  confidence gate, including exactly at the gate; the interval; the deadband; a tween sampled at its
  midpoint; a second move mid-tween; a zero-length tween; the hold, and its expiry; the unmeasured
  view; and a rotated frame. The clock is an argument, so none of these sleep.
- `DocDetectorTest` (3 new, 9 total) — the grayscale overload agrees with the colour one within 6 px
  (the overlay must be drawn where the crop will be taken), rejects an image too small to analyse,
  and scales work done below the working edge back into the caller's frame.
- `LiveOutlineTest` (2) — the live journey end to end on a camera-sized fixture: sampled, detected,
  and drawn at each of the four rotations, checked against the quad the page was photographed
  through; plus the size of the error a mismatched frame causes, which is §4a's defect.

Full suite: 208 tests in 22 classes, 0 failures.

## 8. Risks, and what is not verified

- **DEVICE — the rotation composition is unverified.** `FrameGeometry` is tested against the
  documented meaning of `rotationDegrees` and against `PreviewView.FILL_CENTER`'s definition, not
  against a real device's frames and a real preview layout. If a device reports something unusual
  for its front/back sensor, the border would appear rotated; the fix would be entirely inside
  `quarterTurns`. The device has since been exercised once and the error it found was not in the
  turn (§4a), but only the frames a test can construct are actually covered.
- **DEVICE — v1.6.1's fix is verified on the JVM, not on the phone.** The size of the frame the
  camera actually delivers — and therefore the sampling step the defect scaled by — is not known
  here, because no device was attached when this was written. The owner's first shot is the check.
- **A border can be drawn outside the screen, and is not clamped.** The preview fills the screen by
  cropping the frame, so a page that reaches the frame's sides gets a border whose side lies beyond
  the visible area. Left that way deliberately: the outline's job is to show what the crop will
  keep, and clamping it to the viewport would make the border say the page ends at the screen edge.
  Recorded in the README's known issues.
- **DEVICE — the visual result is unverified.** Contrast of `ScanTealLight` over a real page under
  real light, and whether 0.42 alpha reads as "dimmed" rather than "blacked out", are judgement calls
  made from a desk.
- **Cost on the phone is estimated, not measured.** 15.3 ms per detection at working edge 320 on a
  desktop JVM; a phone is assumed 2–4× slower, i.e. roughly 60 ms every 150 ms of a frame budget that
  is otherwise mostly idle.
- **The live frame is aliased relative to the still frame.** `Luma.grayFromPlane` skips pixels rather
  than averaging them, so the detector's input is the subsampled one, and a busy background can cost
  live confidence that the still path keeps. Recorded in the README; not fixed here.
- **Interaction with the shutter is reasoned, not observed.** Detection and the gate share the
  analyser and the executor, so a detection of tens of milliseconds delays the gate's next
  observation. With a dwell measured in hundreds of milliseconds this is expected to be immaterial,
  but it has not been watched on the phone.
