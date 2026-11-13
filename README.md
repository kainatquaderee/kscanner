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
- Multi-page scanning into one document without leaving the camera flow
- Import existing photos from the gallery; each becomes a page and is auto-detected

**Edge detection and cropping**
- Canny edges → Hough line accumulation → quadrilateral search, scoring candidates by how
  much real image gradient supports each of their four edges
- Fallback path that finds the brightest large region, for low-contrast paper
- Drag any of the four corners to fine-tune; the outline rotates with the image and
  refuses moves that would fold it
- Perspective correction via a homography solved from the four corner correspondences

**Enhancement**
- Six presets: Original, Magic Color, Enhance, Grayscale, B&W, Lighten
- Strength slider that blends any preset back toward the original
- Manual brightness, contrast and sharpness adjustments
- Illumination normalisation so uneven lighting and shadows flatten out

**Text and export**
- On-device OCR with ML Kit — no network, nothing leaves the phone
- Searchable PDF with a genuine invisible text layer (`3 Tr` render mode), positioned from
  the recognised line bounding boxes, so the result can be searched and copied in any viewer
- A4, Letter or fit-to-image page geometry
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

## Architecture

```
core/imaging    framework-free imaging engine (pixels in, pixels out)
  Geometry      Pt / Quad / Rect, convexity, area, normalised rotation
  Homography    DLT solve + bilinear resampling for perspective correction
  Transforms    rotate, scale, fit, the single rotate90 pixel mapping
  Blur          integral-image box blur, median and majority filters
  Threshold     Otsu, adaptive mean, illumination normalisation, range mapping
  Edges         Sobel gradients + Canny with hysteresis
  Hough         line accumulator and peak extraction
  DocDetector   edge- and brightness-based page outline search
  Filters       the six enhancement presets plus blend/adjust/unsharp
  ScanProcessor rotate -> crop -> filter in one pass, full and preview variants
  BitmapBridge  the only file that touches android.graphics (decode/encode)

core/pdf        hand-written PDF 1.4 writer with an invisible OCR text layer
core/ocr        OCR line model, storage codec, ML Kit wrapper
data            SQLite store, file layout, settings, ScanRepository
ui              Compose screens (library, camera, editor, document, viewer, settings)
```

`ScanProcessor` is deliberately a pure function over pixel buffers. That is what lets the
crop and filter stages be tested on the JVM without an emulator, and it is why the PDF
writer is hand-rolled: `android.graphics.pdf.PdfDocument` cannot express an invisible text
layer.

## Tests

73 unit tests, all runnable on a plain JVM:

```bash
./gradlew :app:testDebugUnitTest
```

They cover the geometry and quad maths, homography accuracy, blur and threshold primitives,
Canny/Hough edge detection, end-to-end page detection on synthetic documents, the filter
presets, the OCR text codec's escaping round-trips, and PDF structure — including xref byte
offsets, declared stream lengths, page counts and text-layer placement. One test writes a
real multi-page PDF to `app/build/verification/` so it can be checked with external tooling:

```bash
pdfinfo app/build/verification/ocr-sample.pdf
pdftotext app/build/verification/ocr-sample.pdf -
pdftoppm -png app/build/verification/ocr-sample.pdf page
```

## Licence

MIT.
