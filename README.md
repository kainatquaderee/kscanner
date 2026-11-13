# KScanner

An Android document scanner: photograph a page, have its edges found automatically, correct
the crop to a true rectangle, clean up the look, stack pages into a document, recognise the
text on-device, and export a searchable PDF.

Written from scratch in Kotlin and Jetpack Compose. The entire imaging pipeline, the PDF
writer and the OCR text codec are plain JVM code with no Android dependencies, so the exact
algorithms that ship are the ones covered by the unit tests.

## Features

**Capture**
- CameraX preview with 3×3 composition grid, torch toggle and 1×/2×/4× zoom
- Four capture modes, chosen from a chip row over the viewfinder:
  - **Single** — one shot handed to the editor for manual corner adjustment
  - **Batch** — shoot pages back to back; each is detected, straightened and appended
  - **Book** — photograph a two-page spread and get the two pages cut along the gutter
  - **ID card** — capture the front, then the back; both sides are laid out on one sheet
- Optional **automatic shutter**: the camera measures each preview frame and fires when the
  page is sharp, evenly lit and has stayed that way for several frames (a filling ring shows
  the wait). It is deliberately unhurried so it does not fire at a half-settled hand.
- Live capture advice — "Hold still", "Glare on the page", "Even out the light" — driven by
  the same measurements the pipeline uses, instead of a silent bad scan
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
  conservatively, the ink distribution inside each text band
- Automatic deskew: the residual lean of the text is measured on the *cropped page* and
  rotated out, so a background table edge cannot vote on it
- Drag any of the four corners to fine-tune; the outline rotates with the image and refuses
  moves that would fold it
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
- Sort by date, name or size; group into folders
- Multi-select for bulk rename and delete
- Reorder pages, rotate pages, delete pages

Everything is stored in app-private storage. There is no analytics and no account. The only
permissions the built APK declares are `CAMERA`, `READ_MEDIA_IMAGES`, and
`READ_EXTERNAL_STORAGE` (capped at API 32 for pre-13 gallery access).

ML Kit's transitive Play Services dependencies declare `INTERNET` and
`ACCESS_NETWORK_STATE`; both are explicitly removed in the manifest with
`tools:node="remove"`, because the bundled text recogniser runs entirely on device. You can
confirm this on any build:

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
Each one is built from a tag, so you can reproduce it locally:

```bash
git clone https://github.com/kainatquaderee/kscanner
cd kscanner
git checkout v1.0.0
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk
```

Every release is signed with APK Signature Scheme v2 and v3. Check that a downloaded APK
really came from this project by comparing certificate digests:

```bash
apksigner verify --print-certs app-release.apk
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
  Orientation   line-axis decision plus a conservative upside-down cue
  Quality       sharpness, exposure, glare, illumination ramp and capture advice
  AutoCrop      outline first, then orientation and skew measured inside it
  Spread        cut a book spread along its gutter into two pages
  Composite     lay two captures out on one sheet (the ID-card composite)
  CaptureGate   when the automatic shutter should fire
  Luma          a camera frame's strided luma plane as a GrayImage
  Filters       the seven enhancement presets plus blend/adjust/unsharp
  ScanProcessor rotate -> crop -> deskew -> filter in one pass, full and preview variants
  BitmapBridge  the only file that touches android.graphics (decode/encode)

core/pdf        hand-written PDF 1.4 writer with an invisible OCR text layer
core/ocr        OCR line model, storage codec, ML Kit wrapper
data            SQLite store, file layout, settings, ScanRepository
ui              Compose screens (library, camera, editor, document, viewer, settings)
  capture       ScanSessionViewModel: drives batch, book and ID-card capture
```

`ScanProcessor` is deliberately a pure function over pixel buffers. That is what lets the
crop and filter stages be tested on the JVM without an emulator, and it is why the PDF
writer is hand-rolled: `android.graphics.pdf.PdfDocument` cannot express an invisible text
layer.

## Tests

99 unit tests, all runnable on a plain JVM:

```bash
./gradlew :app:testDebugUnitTest
```

They cover the geometry and quad maths, homography accuracy, blur and threshold primitives,
Canny/Hough edge detection, end-to-end page detection on synthetic documents, the analysis
layers (skew, orientation, quality, segmentation and the automatic shutter), the spread
split and the ID-card composite, the filter presets, the OCR text codec's escaping
round-trips, and PDF structure — including xref byte offsets, declared stream lengths, page
counts and text-layer placement. One test writes a real multi-page PDF to
`app/build/verification/` so it can be checked with external tooling:

```bash
pdfinfo app/build/verification/ocr-sample.pdf
pdftotext app/build/verification/ocr-sample.pdf -
pdftoppm -png app/build/verification/ocr-sample.pdf page
```

## Licence

MIT.
