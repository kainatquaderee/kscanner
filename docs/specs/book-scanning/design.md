# Design — Book scanning

Accompanies v1.6.3. Records what the competing scanner does for books (§1), what was taken and
what was not (§2), the defect this fixes (§3) and how the fix is built (§4). The PRD's acceptance
criteria are cited as AC-n.

## 0. Method

As in `docs/specs/document-framing/design.md` §0: the competing scanner was read from decompiled
sources, not from memory. Every file and line cited in §1 was opened in this environment. The tree
is CamScanner 7.28.5, decompiled to `/tmp/cs/out1` (49,993 `.java` files) from the standalone APK
`/tmp/cs/com.intsig.camscanner.apk`.

One constraint carries over unchanged: **the imaging core stays framework-free** — `IntArray` /
`GrayImage`, no `android.graphics`, no Compose, no OpenCV — so the decision layer runs in plain JVM
tests. Only drawing is Compose.

## 1. What the competing scanner actually does

Book scanning there is two separate mechanisms: a *capture* mechanism that decides when to shoot,
and an *image* mechanism that turns one shot of a spread into two flat pages. They share no code.

### 1a. Capture

**B1 — Book mode is a capture mode with its own scene.** `CaptureMode.BOOK_SPLITTER`
(`capture/CaptureMode.java`, ordinal 20, label `R.string.cs_542_renew_106`). Its scene is
`capture/book/BookCaptureScene.java` (151,656 bytes — the largest capture scene in the app),
alongside `BookRectanglesView`, `BookSplitterPreview`, `BookThumbMarginAnimatorManager` and the
`booksplitter/` package.

**B2 — The signature feature: the shutter fires when the page is turned.**
`ScannerUtils.detectBookTurnPage(byte[], w, h, sessionId)` (`scanner/ScannerUtils.java:297-303`)
allocates `int[36]` and `int[9]`, and calls
`PageScanner.DetectTurnPage(bArr, i, i2, i3, iArr, iArr2)` (`nativelib/PageScanner.java:76`). The
36 ints are nine points and the nine ints are nine classes; the return value is the decision. It
comes back as `BookDetectTurnPageResult(pos, classes, detectResult)`, whose only predicate is
`doCapture() = detectResult == 1` (`scanner/entity/BookDetectTurnPageResult.java:75-77`). So the
"did the page turn" answer is a **native classifier over the preview frame**, not a frame
difference and not a timer.

**B3 — Session-scoped engine state, and the detection is off the main thread.**
`BookAutoTurnManager.m40787oO8o()` allocates a session id from `SessionIdPoolManager` and calls
`PageScanner.InitDetectFrameTurnPage(10)` exactly once, guarded by `f25107OO0o0`
(`capture/book/BookAutoTurnManager.java:184-200`); `ReleaseBookTurnPage` gives it back. The
detector call runs on a `Executors.newFixedThreadPool(1)` coroutine scope and only the decision is
hopped to `Dispatchers.Main` (`BookAutoTurnManager$onPreview$1.java:158-190`). Slow answers are
logged as anomalies: `detectCost > 500` ms.

**B4 — Auto-capture is gated, and metered by a daily cap.** `m40804oo()`
(`BookAutoTurnManager.java:332`) is
`f25118808 >= 2 && PreferenceHelper.O08000() >= 2 && PreferenceHelper.m1073048() <= 0 && !f25108Oooo8o0`
— enough frames seen, enough stable samples, a per-day counter not exhausted, and no cancel in
flight. `m40805o0OOo0()` (`:341`) increments that counter, and `BookCaptureDailyLimitDialog` exists
to tell the user it is spent.

### 1b. Image

**B5 — One shot becomes two pages, and the engine decides whether it is a spread at all.**
`EnginePlugin$bookSplitImage$1` (`flutter/plugin/basic/EnginePlugin$bookSplitImage$1.java`, from
`EnginePlugin.kt:366`) is the whole pipeline:

```
decodeImageS(rawPath)
int[16] borders
retNum = PageScanner.ScanImgPNew(imgPtr, borders, 2)      // :185
pages  = (retNum == 1) ? 2 : 1                            // :187
for each page i:
    border8 = copyOfRange(borders, i*8, 8)                 // :191-193
    out     = createEmptyImageS(0,0,0)                     // :195
    DewarpImgP(imgPtr, border8, outPtr, 0,0,false,true,false)  // :202
    encodeImageS(out, ".../book_split_<ts>_A.jpg", 90)     // :205-207, second page "_B"
```

`ScanImgPNew(long, int[], int)` is `nativelib/PageScanner.java:154`. Sixteen ints is **two 8-int
quads**, and the third argument `2` means "look for two pages"; `BooksplitterUtils.splitBorders`
passes `z ? 2 : 0` (`booksplitter/Util/BooksplitterUtils.java:280`), so `0` means "one". This is
the detail that matters most: **the split is entered only when the engine reports it found two
pages.** A single page photographed in book mode stays one page.

**B6 — Dewarp both crops and flattens; curvature is a first-class input.**
`PageScanner.DewarpImgP(long src, int[] border, long dst, int w, int h, boolean, boolean, boolean)`
(`nativelib/PageScanner.java:94`) takes the page's own quad and an arbitrary output size
(`GetDewarpImageWH`, `:116`, is what computes a sane one). Flattening a curved book page is a
separate family of natives that take *curves* rather than corners:
`DetectCurveBorder` (`:70`), `findCandidateCurves` (`:176`), `SplineFitAdjustCurves` (`:156`),
`adjustCurveRect` (`:162`), and `CurveCropDewarpImagePos{,2,File,File2,Ptr,Ptr2,PtrInplace,
PtrInplace2}` (`:50-64`). The eight-fold explosion of that last family is the shape of a hot path
that has been re-parameterised many times.

**B7 — Shading is removed per page, on the engine's own session.**
After the warp, `BookTrimAndEnhanceCallable.call()` (`BooksplitterUtils.java:96-160`) uses
`BookUseSuperFilterHelper`, which drives `ScannerUtils.enhanceImageS`:
`BooksplitterUtils.init` → `getImageStructPointer` → `deshadowImagePtr2` → `releaseModel`
(`scanner/ScannerUtils.java:1413-1441`). That is a *model-based* deshadow — a page-curvature
illumination model, not a local contrast stretch — plus `DeBlurUtils` when motion blur is
suspected (`:1419-1421`).

**B8 — Each page is turned upright on its own.** `getDocDirectionRotation(imageStructPointer,
0.5f)` (`BooksplitterUtils.java:107`) returns the turn that puts the text upright, and it is stored
as `360 - rotation` (`:110-114`). The two halves of one spread can therefore end up with different
rotations, which is correct: a left page and a right page are often printed the same way but
photographed converging at the gutter.

### 1c. Affordance

**B9 — A dashed centre guide is drawn over the preview.** `BookSplitterPreview.onDraw`
(`booksplitter/view/BookSplitterPreview.java:75-105`) draws one line at `width / 2` (or
`height / 2` in the wider-screen state) running from margin to margin, with
`DashPathEffect(float[]{10dp, 10dp})`, a 6 dp stroke and `R.color.main_title_color`
(`:62-67`). A second path draws four corner brackets (`:107-127`). `setMiddleLineShown` toggles it.
Its purpose is alignment: the guide shows where the gutter should sit while the user frames.

## 2. What was taken, and what was not

| B | Taken | Why |
|---|---|---|
| B5 | **The spread decision before the split** | This is the defect in this app (§3). Our engine already returns two pages from one shot; what it never does is refuse |
| B9 | A dashed centre guide in Book mode | Cheap, and it is the affordance that makes gutter alignment possible at all |
| B2/B4 | Not in this change | A native turn-page classifier cannot be reproduced here, and a heuristic stand-in has a false-positive rate that would fire the shutter at the wrong moment. Offered to the owner as its own decision |
| B6/B7 | Not in this change | Curvature-aware dewarp and a model-based deshadow are both genuine features of their own; carried as out of scope in the PRD |
| B8 | Already present | `AutoCrop.plan` decides rotation per page, and each half is processed separately |

## 3. The defect this fixes

`ScanMode.BOOK`'s hint promised "one spread becomes two pages", and the code delivered "every shot
becomes two pages" — the hint described the exception and the code implemented the rule. (The hint
now reads "Spreads split in two, single pages stay whole", which is what this change makes true.)
`ScanSessionViewModel.spreadPages()`
(`ui/capture/ScanSessionViewModel.kt:346-370`) warps the shot, finds the darkest column
(`Spread.findSeam`), cuts the quad at that fraction and commits **both halves unconditionally**:

```kotlin
val seam = flattened?.let { Spread.findSeam(it.toGray()) } ?: 0.5
val split = Spread.split(spread, seam)
return listOf(split.left, split.right).map { ... }
```

`Spread.looksLikeSpread` (`core/imaging/Spread.kt:85-94`) is the function written for exactly this
decision, it has two tests (`ScanModesTest.kt:40`, `:47`), and **it is never called from the
shipping path** — `grep -rn 'looksLikeSpread' app/src` finds only the declaration and the tests.

The consequence is visible and not recoverable in the app. Photograph a single page in Book mode
and it is stored as two half-pages, each with the text cut vertically down its middle. The editor
has no split or join action (`grep -rni 'split' app/src/main/java/com/kscanner/ui` matches only
`ScanSessionViewModel.kt:359`), so the only cure is to delete both pages and re-shoot. The
competing scanner does not have this failure: `retNum` from `ScanImgPNew` is what decides (§1a,
B5).

## 4. How the fix is built

1. **`Spread.decide`** — a single entry point in the imaging core that answers *is this a spread,
   and if so where is the gutter*, from a flattened `GrayImage`. It wraps `findSeam` and replaces
   `looksLikeSpread`'s single-threshold test with the evidence a real spread actually carries:
   the cut is a narrow dark band, it runs the full height of the sheet row by row, and the paper
   immediately on each side of it — measured separately, so a shadow on one half cannot pass —
   is brighter than the band. When the
   answer is not confidently "spread", the answer is "one page" — the same default the competing
   engine's `retNum != 1` produces.
2. **`spreadPages()` commits one page when the decision says one page**, and names the committed
   pages so the strip reads correctly (a single page is page *n*; a spread is pages *n* and *n+1*,
   which the existing code already handles).
3. **A dashed centre guide in Book mode only**, drawn in the preview layer beside the framing
   overlay: 1 dp dashes at 45 % white, from the top inset to the bottom inset, at the horizontal
   centre of the preview — matching B9's placement without borrowing its colour, which is this
   app's own teal-on-dark problem.
4. **Tests**, in the imaging core where they can run in a JVM: a synthetic spread with an
   off-centre gutter decides "spread" and returns that gutter; flat paper, a single text page, and
   a page whose centre column is dark because of a text line decide "one page"; a spread photographed
   at the extremes of the accepted gutter range still decides "spread".

The threshold values are not invented: they are fitted in test against `TestImages.spreadWithGutter`
and `RealisticPages`, and the fitted numbers are recorded in the KDoc of `Spread.decide`.