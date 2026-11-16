# KScanner

An Android document scanner: photograph a page, have its edges found automatically, correct
the crop to a true rectangle, clean up the look, stack pages into a document, recognise the
text on-device, and export a searchable PDF.

Written from scratch in Kotlin and Jetpack Compose. The imaging engine, the PDF writer and
the OCR text codec are plain JVM code: a single file, `BitmapBridge`, touches
`android.graphics` to decode and encode, and everything else runs without Android, so the
algorithms that ship are covered by the unit tests rather than by a device.

## Features

**Capture**
- CameraX preview with a 3×3 composition grid, torch toggle, and zoom chips at 1× and 2×,
  plus 4× where the camera's zoom range reaches it
- Three capture shapes, chosen from a chip row over the viewfinder, and a **Batch** toggle
  that sits beside them. What one shot becomes and whether the run goes on are two
  questions, so they are two controls:
  - **Single** — one shot, detected and straightened. With Batch off it is handed to the
    editor so the corners can be placed by hand; with Batch on it is appended and the camera
    stays ready
  - **Book** — photograph a two-page spread and get the two pages cut along the gutter
  - **ID card** — capture the front, then the back; both sides are laid out on one sheet
- **Batch** works with any of the three shapes: on, the camera keeps shooting into the same
  document until you press **Done**; off, it takes one unit and leaves
- Whenever a run keeps going, a strip of what has landed so far sits under the shutter.
  Tapping a page opens the **review**: step through what the run has committed so far, rotate
  one page a quarter turn, flip one over, re-shoot it with the camera so the replacement takes
  that page's place rather than landing at the end, and see — on the page itself — which pages
  the automatic orientation pass could not decide. A re-take is offered where one shot means
  one page, so it appears for Single with Batch on, and not for a spread or a card.
- Optional **automatic shutter**: the camera measures each preview frame and fires when the
  page is sharp, evenly lit and has stayed that way for several frames (a filling ring shows
  the wait). It is deliberately unhurried so it does not fire at a half-settled hand.
- Live capture advice — "Hold still", "Glare on the page", "Even out the light" — driven by
  the same measurements the pipeline uses, instead of a silent bad scan
- **Live framing outline**: the viewfinder draws the page border as it is found and dims
  everything outside it, so the shot can be lined up before the shutter is pressed. It is
  always on and has no setting, because it is the affordance rather than a mode. The border is
  *followed* rather than redrawn: corners move for changes larger than 5 dp, moves are tweened
  rather than snapped, and a page that goes missing keeps its outline for 400 ms before the pen
  is lifted — an outline that tracked every frame's noise would shimmer, and one that vanished
  whenever a line was briefly lost would flicker. Detection is asked for about seven times a
  second on the same small grayscale frame the automatic shutter measures, and the live path
  uses the line detector alone: the fallbacks exist for still photographs, where an outline
  round a block of text beats nothing, but live that box would read as the page edge
- Import existing photos from the gallery; each becomes a page and is auto-detected

**Edge detection and cropping**
- Line evidence is grouped into families around the *dominant* line direction rather than the
  image axes, so a page photographed at an angle is found as readily as a level one
- Candidates are ranked by how much real image gradient supports all four edges, then
  arbitrated against the pixels inside and outside them
- Corners are refit sub-pixel against the ridge of each edge
- Cascading fallbacks for hard frames: the brightest large region fitted with a rotated
  rectangle, then the text block itself
- Automatic orientation (upright / sideways / upside down) decided from the line axis and,
  conservatively, the ink distribution inside each text band — read from a *levelled* copy of
  the page, because a degree or two of residual tilt smears a text band by about its own
  height and quietly inverts the cue that tells a page from the same page turned over. Each
  band is now grown out from its x-height core to the valley that separates it from the next
  line, up to the point where the row profile is paper again, so the ascender and descender
  rows that carry the cue sit inside the band instead of being cut away by a fixed threshold —
  which is why a page that arrives upside down is turned back upright in far more cases than
  it used to be
- That cue has a limit, and the app reports it rather than guessing. On dense prose with
  little typographic asymmetry the reading sits too close to its own scatter to trust, at
  every affordable working resolution, and no threshold fixes that: a page in that state is
  left as captured, `AutoCrop.Plan.flipDecided` is false, `AutoCrop.Plan.flipReason` records
  that the pass could not tell, and the review marks the page and offers a one-tap flip
- Automatic deskew: the residual lean of the text is measured on the *cropped page* and
  rotated out, so a background table edge cannot vote on it
- Drag any of the four corners to fine-tune; the outline rotates with the image and refuses
  moves that would fold it
- Pinch to zoom and drag to pan both editor stages, with a double tap to toggle 2×. The crop
  preview is rendered at up to 2048 px and the filter preview at 1920 px, so magnifying reveals
  pixels instead of the blur of an enlargement; the zoom rules are plain arithmetic over screen
  coordinates in `core/imaging/PreviewViewport.kt`, unit tested without a device
- Perspective correction via a homography solved from the four corner correspondences

**Enhancement**
- Seven presets: Original, Magic Color, Enhance, Grayscale, B&W, Lighten, Shadow Free
- Shadow Free is flat-field colour correction: a blurred estimate of the lighting is divided
  out, so shadows and gradients vanish while text stays dark
- Strength slider that blends any preset back toward the original
- Manual brightness, contrast and sharpness adjustments
- Automatic preset choice from the page's own measurements (colour, exposure, shadow load)

**Text and export**
- On-device OCR with ML Kit — no network, nothing leaves the phone
- Searchable PDF with a genuine invisible text layer (`3 Tr` render mode), positioned from
  the recognised line bounding boxes, so the result can be searched and copied in any viewer
- A4, Letter or fit-to-image page geometry
- Copy the recognised text to the clipboard, or export it as a plain `.txt`
- Share single pages, whole documents, the PDF, or just the recognised text

**Library**
- Grid library with cover thumbnails, page counts, sizes and OCR indicators
- Search across document names *and* recognised text
- Sort by newest, oldest, name or size
- Multi-select: delete several documents at once, after a confirmation that says how many and
  what goes with them; rename applies to the one document selected
- Reorder pages, rotate pages, delete pages
- An import says what happened — "Imported 3 of 5 — 2 could not be read" — instead of losing
  the failures silently

**App and settings**
- The installed version is read from the package and shown in Settings, rather than typed
  into a label that then drifts
- Search and sort live in the view model, so the library shows the query and order that are
  actually filtering the list
- With the camera permission permanently denied, the screen offers a route into the app's
  system settings and re-checks the permission on return instead of dead-ending
- Back and page-step arrows use the auto-mirrored icons, so they point the right way in a
  right-to-left layout

Everything is stored in app-private storage. There is no analytics and no account. The only
platform permissions the built APK declares are `CAMERA`, `READ_MEDIA_IMAGES`, and
`READ_EXTERNAL_STORAGE` (capped at API 32 for pre-13 gallery access); the manifest also carries
`com.kscanner.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, an app-private, signature-level
permission added by `androidx.core` that no other app can hold.

ML Kit's transitive Play Services dependencies declare `INTERNET` and
`ACCESS_NETWORK_STATE` (`transport-backend-cct`, and `transport-runtime` for the latter); both
are explicitly removed in the manifest with `tools:node="remove"`, because the bundled text
recogniser runs entirely on device. Neither permission survives merging — Gradle's
`manifest-merger-*-report.txt` lists them as rejected from those libraries, and the merged
manifest contains no `INTERNET` at all. You can confirm this on any build:

```bash
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep uses-permission
```

## Building

Requirements: JDK 17 and an Android SDK with platform 35 and build-tools 35.0.0.

```bash
# point the build at your SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug          # build the APK
./gradlew :app:testDebugUnitTest      # run the unit tests
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Releases

Signed APKs are published on the [Releases page](https://github.com/kainatquaderee/kscanner/releases).
Each release is built from a tag. You can build the same version from that tag yourself:

```bash
git clone https://github.com/kainatquaderee/kscanner
cd kscanner
git checkout v1.5.0                       # the tag you want to build
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease -PversionName=1.5.0 -PversionCode=10500
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | sed -n 1p
```

`-PversionName` and `-PversionCode` are required: without them the build falls back to `1.0.0`
and `1`, which is not a released version. The APK is **not byte-identical** to the published
one — the release is signed with a key that is not in this repository, and the packaging step is
not reproducible byte for byte, so `sha256sum` will not match.

Every release is signed with APK Signature Scheme v2 and v3. The published download is named
after the version (`kscanner-1.5.0.apk`); confirm it really came from this project by comparing
signing certificates — the certificate, not a checksum of the file, is what Android compares
when it installs an update:

```bash
apksigner verify --print-certs kscanner-1.5.0.apk
# Signer #1 certificate SHA-256 digest: b98924a5ba364b67ac61837550e3d2845e3b1c05ac95a42bdeb95230638066fd
```

Android refuses to install an update signed with a different key, so if you previously
installed a build from another source, uninstall it first. See [SIGNING.md](SIGNING.md) for
how the keys are managed.

## Architecture

```
core/imaging    framework-free imaging engine (pixels in, pixels out)
  Geometry      Pt / Quad / Rect, convexity, area, normalised rotation
  Homography    DLT solve + bilinear resampling for perspective correction
  Transforms    rotate, scale, fit, arbitrary-angle rotation, quarter-turn quad mapping
  Blur          integral-image box blur, median and majority filters
  Threshold     Otsu, adaptive mean, illumination normalisation, range mapping
  Edges         Sobel gradients + Canny with hysteresis
  Hough         line accumulator and peak extraction
  DocLines      line evidence per scale, dominant-direction families, sub-pixel refit
  Segmentation  ink/bright masks, connected components, convex hull, min-area rect, profiles
  DocDetector   the multi-stage page outline search and its cascade of fallbacks
  Skew          projection-profile tilt search (coarse then fine)
  Orientation   line-axis decision plus a conservative upside-down cue, band extents grown
                from each line's core, and the record of when it declined to decide
  Quality       sharpness, exposure, glare, illumination ramp and capture advice
  AutoCrop      outline first, then orientation and skew measured inside it
  Spread        cut a book spread along its gutter into two pages
  Composite     lay two captures out on one sheet (the ID-card composite)
  CaptureGate   when the automatic shutter should fire
  Luma          a camera frame's strided luma plane as a GrayImage
  FrameGeometry where a detected outline lands on the preview's own pixels
  OutlineTracker which outline the live preview draws, and when it lifts
  Filters       the seven enhancement presets plus blend/adjust/unsharp
  ScanProcessor rotate -> crop -> deskew -> filter in one pass, full and preview variants
  BitmapBridge  the only file that touches android.graphics (decode/encode)

core/pdf        hand-written PDF 1.4 writer with an invisible OCR text layer
core/ocr        OCR line model, storage codec, ML Kit wrapper
core/io         the decode-cache key for a page file: path, length and modified time
data            SQLite store, file layout, settings, ScanRepository
ui              Compose screens (library, camera, editor, document, viewer, settings)
  capture       ScanSessionViewModel: drives the runs that keep the camera open
```

`ScanProcessor` is deliberately a pure function over pixel buffers. That is what lets the
crop and filter stages be tested on the JVM without an emulator, and it is why the PDF
writer is hand-rolled: `android.graphics.pdf.PdfDocument` cannot express an invisible text
layer.

## Tests

208 unit tests in 22 classes, all runnable on a plain JVM:

```bash
./gradlew :app:testDebugUnitTest
```

They cover the geometry and quad maths, homography accuracy, blur and threshold primitives,
Canny/Hough edge detection, end-to-end page detection on synthetic documents, the analysis
layers (skew, orientation, quality, segmentation and the automatic shutter), the spread
split and the ID-card composite, the filter presets, the OCR text codec's escaping
round-trips, and PDF structure — including xref byte offsets, declared stream lengths, page
counts and text-layer placement, and the editor's viewport zoom and pan arithmetic in
`core/imaging/PreviewViewport.kt`. The framing overlay is covered at both levels it can be
wrong: `core/imaging/FrameGeometry.kt` takes a detected outline to the pixels the preview
actually drew it in — for each of a camera's four rotations, and through the fill-and-crop the
preview uses — and `core/imaging/OutlineTracker.kt` is the decision of what to draw now, driven
against a clock the test owns: the throttle, the confidence gate, the deadband, the tween and
the hold, all without a camera and without sleeping. The detector is pinned from grayscale too,
since that is what the preview hands it, and the outline it returns there has to match the one
the still path returns. One test starts where the camera does — a camera-sized plane, sampled the
way the analyser samples it — and checks that the border lands on the page at each of the four
rotations a camera can report; it exists because the release that first drew the border had the
two ends of that journey disagreeing about which frame the outline was in, and drew it beside the
page instead of on it. The orientation work is pinned by tests at two levels: the
band extents and the 0-versus-180 decision as plain functions, and a contract that walks
realistic prose fixtures — uneven light, a shadowed half, rules, underlines, a stamp, a
signature — asserting that an upside-down page is either turned back upright or reported as
undecided, never left upside down while claiming to have decided. The settings migration that
turned the old **Batch** mode into a shape plus a toggle is pinned too: every stored value
decodes to the behaviour it had, and nothing readable decodes to a mode this version cannot
name. One test writes a real
multi-page PDF to `app/build/verification/` so it can be checked with external tooling:

```bash
pdfinfo app/build/verification/ocr-sample.pdf
pdftotext app/build/verification/ocr-sample.pdf -
pdftoppm -png app/build/verification/ocr-sample.pdf page
```

## Known issues

True of v1.6.1 as released, stated rather than hidden:

- **Folder grouping and bulk rename are not reachable in the UI.** The `documents` table has a
  `folder` column, `DocumentStore.listFolders` can read it and `ScanRepository.moveDocument`
  can set one, but nothing in the interface ever calls `moveDocument`, so the folder chips in
  the library never have a folder to show. Rename is offered only when exactly one document is
  selected (`LibraryScreen.kt:510`), so there is no bulk rename. Both are held back for a later
  release rather than half-exposed.
- **The editor's Detect action discards a manual rotation.** "Detect automatically" re-runs the
  whole automatic pass, and that pass assigns the rotation it decided
  (`EditorViewModel.kt:194`), so a quarter turn applied by hand before the tap is replaced by
  the detector's choice.
- **Rotating a page does not invalidate stored OCR line boxes.** `ScanRepository.rotateBy`
  rewrites the page image, its corners and its width and height, but leaves `ocr_text` and
  `ocr_lines` untouched — and the boxes are in the pixel coordinates of the image they were
  read from. The recognised text still reads correctly; a searchable PDF exported after a
  rotation can place its invisible text layer against pixels that have since moved. Running
  OCR on the page again stores fresh boxes.
- **`StorageManager.sweepCaptures` is not wired up.** Scratch captures under
  `cacheDir/captures` are written and never swept; the method exists and has no caller.
- **The live outline is detected on a subsampled frame.** The preview's luma plane is made small
  for detection by *skipping* pixels (`Luma.grayFromPlane`'s `step`), not by averaging them, so
  the detector sees a more aliased image live than it does on the still path, which resamples
  bilinearly. On a busy background that can cost the live outline the confidence the still path
  keeps, and it is why the live path declines the detector's fallbacks. Averaging would remove
  the difference and costs a second pass over the plane on every analysed frame.
- **A border can be drawn outside the screen.** The preview fills the screen by cropping the frame,
  so when the page reaches the frame's sides — which is what happens when it fills the view — the
  part of the border tracing those sides is drawn outside the visible area. The outline is still
  where the page is; on a tall phone it can read as two lines rather than a frame.

## Licence

MIT.
