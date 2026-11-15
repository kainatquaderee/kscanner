# Design — Batch review, orientation honesty, and the top usability defects

- **Slug:** `batch-review-and-orientation`  •  **Repo:** `/home/kainatq/Downloads/kscanner`, tip `39f8eba`, pkg `com.kscanner`  •  **Target:** `v1.4.0` / `10400` (devops' job; untouched)
- **Author role:** System Architect. **No production code written by me.** I cannot build or run anything (no emulator, no `/dev/kvm`, shell denied). Every claim is either a citation to a line I read or is marked *proposal*.
- **Environment facts that constrain the design:** one test dependency, `junit:junit:4.13.2`; no Robolectric, no Compose test harness, no `androidTest` source set; `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:115-117`), so `android.*` returns defaults and no `android.graphics`/SQLite path is even in principle unit-testable.
- **Where this file lives:** the workspace is `/home/kainatq/Downloads`; this design is at `docs/specs/batch-review-and-orientation/design.md` beside the PRD copy. Tool permissions deny writes inside the repo checkout (`kscanner/docs/specs/**`), so the repo copy is the developer's to place — flagged, not silently skipped.

## 0. Method, and what counts as evidence here

Read: the PRD; `core/imaging/{Orientation,AutoCrop,Transforms}.kt`; the tester's `RealisticOrientationContractTest.kt` (whole file) and `RealisticPages.kt` (120-329); `AutoCropOrientationTest.kt`; `ui/capture/ScanSessionViewModel.kt`; `data/{ScanRepository,DocumentStore,DbHelper,Models,ScanMode}.kt`; `ui/components/Components.kt`; `ui/DocumentViewModel.kt`; `ui/camera/CameraScreen.kt`; `ui/viewer/ViewerScreen.kt`; `ui/document/DocumentScreen.kt`; `ui/library/LibraryScreen.kt` (to 209); `ui/settings/SettingsScreen.kt` (grep-confirmed anchors); `MainActivity.kt`; `app/build.gradle.kts`; `README.md`.

Three tiers of statement below, kept visibly apart: **[CITE]** a line I read; **[TESTER]** a measurement the tester reported from a run I cannot repeat; **proposal** — to be confirmed by running something. Not read: `StorageManager.kt`, `EditorScreen.kt` (past anchors), `EditorViewModel.kt`, `LibraryViewModel.kt`, `AutoCaptureAnalyzer`, `CaptureGate`; steps touching those say "read it first".

## 1. Findings

| # | Finding | Where |
| --- | --- | --- |
| F14 | **Root cause of S2, and the one that decides this design.** The flip cue is a **false negative on real prose**, not a false positive. `bands()` cuts the row profile at 30% of peak (`Orientation.kt:241`, called `:193`) and ascender/descender rows carry far less than 30% of the x-height ink mass, so the band **is the x-height run alone** (~9 px at the working edge) where vertical stems make ink near-uniform in y: the centroid falls on the band centre and the bias collapses to ≈0. The KDoc at `Orientation.kt:17-24` relies on ascenders and capitals stretching the band upward more than descenders stretch it down; the band-finder discards exactly that evidence before the arithmetic sees it. **[TESTER]** on realistic prose: 20 bands, per-band bias −0.033..+0.033, 5 positive / 7 negative, so the majority gate `agreeing * 2 < biases.size` (`:233`) fails and `flipBias` returns `Bias.NONE` — `flipDecided` false in **both** orientations, page left as captured. `FLIP_MARGIN = 0.02` at 9 px band height is 0.18 px of centroid against up to 0.30 px scatter, so the margin is inside the noise too — but the margin is **not** the binding defect and must not be the fix. | `Orientation.kt:185-254` |
| F15 | **The only existing evidence for the cue is manufactured.** `TestImages.asymmetricTextPage` is a trapezoid widening downward, so all 16 bands read +0.021..+0.071 unanimously and clear the margin 6.5×. It fabricates an intra-band gradient real type does not have, so every green test in `AutoCropOrientationTest.kt` resting on it is not evidence for the cue. The tester's 14-test suite (`RealisticOrientationContractTest.kt`) is the evidence; 9 red, 5 green. | `TestImages.kt:113-137`, `AutoCropOrientationTest.kt` |
| F3 | **H2 is a live hole.** `Bias.NONE = Bias(0.0, 0)` (`:174`) is indistinguishable from a measured zero, and `evidenceBands = min(pair[0].bias.bands, pair[1].bias.bands)` (`:146`) *relaxes* the margin to `SPARSE_FLIP_MARGIN` when one member abstains — so one member's absence lets the other member's reading decide a flip on its own. Plausible spurious-flip route on real pages. | `Orientation.kt:146-150, 174` |
| F2 | `flipBias`'s KDoc claims only agreeing bands are counted; `sum` (`:226-228`) sums every contributing band and `Bias(sum / biases.size, agreeing)` (`:234`) uses it, and that value is what meets the margin. Doc wrong, arithmetic fine → correct the doc, do not touch the mean. | `Orientation.kt:178-184, 224-234` |
| F5 | Dead code, confirmed by grep over `src/main`: `Estimate.confidence` (`:156-158`) is read nowhere; `Plan.flipDecided` (`AutoCrop.kt:114`) is read only by tests. | — |
| F16 | **The mirror-invariance result.** Within the surviving pair the candidates are always 180° apart (`:129-134`), row profiles are exact reversals, so `bands()`, the cut-relaxation loop and the sign counts are mirror-invariant; only the one-row edge skip (`:204`) and the `ink`/`minInk` test (`:222`) are asymmetric. A mask in which exactly one candidate abstains is therefore **contrived, possibly unconstructible**. AC-13's intent is satisfied at the decision seam instead (§2.1) — the abstention rule is a unit-tested pure function, which is stronger than a fixture that may not be buildable. | `Orientation.kt:129-150, 204, 222` |
| F8 | **Nav-away destroys the session.** `CameraScreen` initialises on `LaunchedEffect(Unit) { session.prepare(...); session.beginInto(documentId) }` (`:98-101`) and `beginInto` resets `documentId`, `pageCount`, `recent` (`ScanSessionViewModel.kt:87-93`). A review implemented as a new `NavHost` route would, on return, re-run that effect and wipe the run (breaks AC-6). Hence §2.5: overlay inside `CameraScreen`, plus a `prepareIfNeeded`. | `CameraScreen.kt:98-101` |
| F10 | The page row id already exists and is thrown away: `DocumentStore.insertPage` returns it (`:130, 143`); `ScanRepository.appendPages` discards it and returns only the document id (`:113-125, 134`). | — |
| F6 | **Rotation compounds** on every page whose "original" is the processed file: `appendPages` stores `originalPath = (originalFile ?: pageFile)` (`ScanRepository.kt:117`) and BOOK halves / ID-card sheets pass `originalPath = null` (`ScanSessionViewModel.kt:213, 222-227`), so `rotatePage` re-applies rotation to pixels that already carry it (`:267, 294, 297`). | — |
| F7 | **Stale decode.** `rememberFileImage` keys `produceState` on `(path, maxEdge)` only (`Components.kt:46`) while rotate rewrites the same path (`ScanRepository.kt:298-301`) and `DocumentViewModel.rotatePage` refreshes rows whose `processedPath` is unchanged (`DocumentViewModel.kt:92-101`). | — |
| F9 | `BuildConfig` is not generated (`build.gradle.kts:100-102` sets `compose = true` only), so AC-25 reads the version from `PackageManager` instead. | — |
| F11 | `DbHelper.VERSION = 1` with an empty `onUpgrade` (`:54-61`) and `PAGE_SELECT` an explicit column list (`DocumentStore.kt:264-268`) ⇒ AC-12 needs CREATE TABLE + `ALTER TABLE` + `PAGE_SELECT`. | — |
| F12 | BOOK's strip order: `recent = (thumbs.reversed() + recent).take(12)` (`ScanSessionViewModel.kt:244`) — one commit's pages are `[left, right]`, so `reversed()` swaps the halves out of reading order; newest-first already comes from prepending. | — |
| F1 | Batch and single capture call the same `AutoCrop.plan` with `workingEdge = 1080`; `aspectHint` is non-null only for `ID_CARD`. "Worse in batch" has no imaging explanation — it is the missing review (H5 supported). | `ScanSessionViewModel.kt:126-133`, `EditorViewModel.kt:179-184` |
| F13 | Test reach: JVM-only, framework-free seams only; nothing in `BitmapBridge`, SQLite or `android.graphics` is testable here. Every seam below is framework-free by construction. | `build.gradle.kts:115-117` |

**Judgement (one paragraph, where I disagree with the brief).** I keep the noise-scaled margin out of the critical path. The brief's H1-style retune is the *second* defect, and the tester's own numbers say so: the signal is destroyed upstream of the margin, and 0.02-vs-0.03 is a rounding detail beside a bias that is 0.00 in both orientations. So §2.1 changes the band extent (the fix), adds the abstention rule (the safety), and carries the noise-scaled margin only as a bounded floor-raiser with an explicit kill criterion — if it re-introduces the false negative on the tester's prose fixtures, the developer drops it and ships the base margins. And I do not chase a mask-level fixture for AC-13 (F16): the rule is pinned as a pure function instead.

## 2. The seams

A seam = a named function with a fixed signature whose inputs and outputs a plain-JVM test can construct and assert, and which the production path actually calls. Framework-free by construction (`java.io` and `java.io.File` are fine; `android.*` and Compose are not).

### 2.1 The orientation decision (S2 core: AC-11, AC-13, AC-14, AC-16, AC-17)

**File:** `app/src/main/java/com/kscanner/core/imaging/Orientation.kt` (stays framework-free).

Three changes, in the order the tests need them.

**(a) The band extent — the fix.** The band must carry the ascender and descender rows, because the KDoc at `:17-24` names exactly that asymmetry as the cue and the 30% cut at `:241` removes it.

Rule, replacing the direct `bands(profile, cutFraction)` call at `:193`: **find the cores at 30% of peak as today, then grow each core outward to the inter-line valley.** Growth stops on the first row that is not lower than the row inside it (the descent has ended — that is what separates a line from the paper) or at `SKIRT_CUT · peak` of profiled ink, and never crosses the midpoint to the neighbouring core.

```kotlin
/** Row range of one line of print, ascenders and descenders included. */
// (an IntRange, as `bands` already returns)

/** Cut that finds a line's x-height core, as today. */
private const val CORE_CUT = 0.30

/** Rows below this fraction of the peak are paper, not line: the growth floor. */
private const val SKIRT_CUT = 0.03

/**
 * One row range per line of print: the [CORE_CUT] cores grown outward to the
 * valley that separates them, so the ascenders and descenders the cue reads
 * (see the class KDoc) are inside the band rather than cut away by it.
 */
internal fun textBands(
    profile: IntArray,
    coreCut: Double = CORE_CUT,
    skirt: Double = SKIRT_CUT,
): List<IntRange>
```

One-line justification: the ascenders and descenders *are* the skirt — they sit below any single cut that also isolates the x-height, and the descent into the inter-line gap is itself the boundary, so no third threshold and no per-font tuning is needed. The valley rule is what the brief's "skirt merged into each run" and "measuring against inter-line valleys" amount to once you refuse to pick between them: merge while descending, stop at the valley.

**Risk, stated up front.** On tight leading the two lines' skirts meet: the midpoint cap then splits them, so a band can swallow a sliver of its neighbour's descender and the centroid shifts by a fraction of the skirt. That is a bias on the *mean*, not a destroyed sign, so the majority gate and the margin still stand under it — but the developer measures it on `RealisticPages.prosePageUnderUnevenLight()` (the photographed fixture) before tuning anything, and if the sign flips on any well-lit fixture the midpoint cap becomes a hard cap at half the *core* pitch instead.

`bands(profile, fraction)` (`:238-254`) stays as it is and becomes the core-finder; `textBands` is the only band source `flipBias` may use. `internal` rather than private so the JVM test can call it with a hand-built `IntArray` — no image, no mask, no fixture.

**(b) The abstention rule — the safety (AC-13).** `Bias` gains "measured", so "no reading" is a different thing from "a reading of 0.0":

```kotlin
private class Bias(val value: Double, val bands: Int, val samples: Int, val sumSquares: Double) {
    /** False ⇒ this candidate has no reading at all, not a reading of zero. */
    val measured: Boolean get() = bands >= 1
    companion object { val NONE = Bias(0.0, 0, 0, 0.0) }
}

/** The 0-versus-180 decision, pure and whole: no mask, no profile, no image. */
data class FlipEvidence(val mean: Double, val bands: Int, val samples: Int, val sumSquares: Double) {
    val measured: Boolean get() = bands >= 1
    companion object { val NONE = FlipEvidence(0.0, 0, 0, 0.0) }
}

enum class FlipReason { FLIPPED, KEPT, ONE_SIDE_ABSTAINED, BELOW_NOISE }

data class FlipDecision(
    val flip: Boolean,          // true ⇒ turn the frame 180°
    val decided: Boolean,       // false ⇒ no evidence; leave as captured
    val reason: FlipReason,
    val gap: Double,            // turned.mean - asCaptured.mean
    val margin: Double,         // the margin `gap` had to clear
    val standardError: Double,  // from the readings' own scatter
)

fun decideFlip(asCaptured: FlipEvidence, turned: FlipEvidence): FlipDecision
```

Exact rule, in this order (a change to any line is a behaviour change the tester sees):

1. `if (!asCaptured.measured || !turned.measured)` → `flip = false`, `decided = false`, `reason = ONE_SIDE_ABSTAINED`, `margin = bandMargin()`. **An abstaining candidate can never decide the flip and never relax the margin** — this is the whole of AC-13, and it is where `Bias.NONE` stops being a measurable zero.
2. `bandMargin = if (min(asCaptured.bands, turned.bands) >= 3) FLIP_MARGIN else SPARSE_FLIP_MARGIN` (0.02 / 0.03, values unchanged — they are the floor).
3. `sigma(e) = if (e.samples >= 2) sqrt(e.sumSquares / (e.samples - 1)) else BAND_SIGMA_FLOOR`; `se(e) = sigma(e) / sqrt(e.samples)`; `standardError = sqrt(se(asCaptured)² + se(turned)²)`.
4. `margin = max(bandMargin, min(NOISE_SIGMAS * standardError, MAX_NOISE_MARGIN))`.
5. `gap = turned.mean - asCaptured.mean`; `decided = abs(gap) > margin` (strict `>`, as today); `flip = decided && gap > margin`.
6. `reason = when { !decided && oneSideAbstained -> ONE_SIDE_ABSTAINED; !decided -> BELOW_NOISE; flip -> FLIPPED; else -> KEPT }`.

```kotlin
/** Band readings scatter 0.02-0.07 of band height (see MIN_AGREEING_BANDS); 0.05 is that
 *  scatter when a candidate has too few readings to measure its own. */
private const val BAND_SIGMA_FLOOR = 0.05
/** Two sigma: a gap the readings' own scatter would produce by chance is not evidence. */
private const val NOISE_SIGMAS = 2.0
/** Above the largest displacement any fixture produces (0.07), a margin can never be
 *  cleared, which is the same as abstaining; the cap keeps the guard from degenerating. */
private const val MAX_NOISE_MARGIN = 0.06
```

**Kill criterion for step 4 (this is a gate, not advice).** If, after (a) and (b), any contract test that asserts `flipDecided == true` abstains (`real prose carries enough evidence for the flip cue to decide`, `RealisticOrientationContractTest.kt:124-134`, plus `AutoCropOrientationTest.kt:186, 215`), `margin = bandMargin` alone ships and the noise guard is deleted. The false negative is the defect being fixed; a guard that re-creates it is a regression however principled. Developer reports the measured `(gap, se, margin)` per fixture before touching `NOISE_SIGMAS` or `MAX_NOISE_MARGIN`.

**(c) Wiring, and the rest of the decision left alone.**

- `flipBias` (`:185-235`) uses `textBands`, and accumulates `samples` / `sumSquares` over exactly the readings it already sums (`:199-217`). Its KDoc loses the false "only bands that agree are counted" claim (F2); the mean arithmetic is untouched.
- Replace `:146-150` with:
  ```kotlin
  val decision = decideFlip(pair[0].bias.evidence(), pair[1].bias.evidence())
  val chosen = if (decision.flip) pair[1] else pair[0]
  val flipDecided = decision.decided
  ```
  `chosen` turns the page over **only** when the cue decided to; an undecided page is left as captured — the invariant `AutoCropOrientationTest.kt:77-83` already asserts.
- `Estimate` (`:29-49`) gains `val flipReason: FlipReason = FlipReason.ONE_SIDE_ABSTAINED` as its last field; `AutoCrop.plan` (`AutoCrop.kt:63-125`) keeps its signature and its `Plan.flipDecided` (`:45`) and gains `val flipReason: FlipReason` (`AutoCrop.kt:112-124`). `UPRIGHT` (`:51`) is unchanged, so `AutoCropOrientationTest.kt:311-319` is untouched. Marked *droppable*: if the day runs short, `flipReason` is the first thing to cut — `flipDecided` alone carries AC-8.
- **Not changed:** `FLIP_MARGIN`/`SPARSE_FLIP_MARGIN` values, `MIN_AGREEING_BANDS`, `MIN_FLIP_INK*`, `MIN_FLIP_INK_FRACTION`, the candidate scoring, `Estimate.confidence` (F5 — dead, out of scope).

**What the tester gets, and what happens to the 14 tests.**

| Test | Before | After |
| --- | --- | --- |
| `Orientation.textBands` unit test (new, `app/src/test/java/com/kscanner/core/imaging/OrientationBandsTest.kt`) — synthetic `IntArray` profile: an x-height block with a narrow ascender row above and a descender row below, next line two rows down | — | band `first` reaches the ascender row, `last` the descender row, and the band stops at the valley before the next core |
| `decideFlip` unit test (new, `OrientationDecisionTest.kt`) | — | five rows, no fixtures: `(measured, NONE)` → `decided=false, flip=false, ONE_SIDE_ABSTAINED`; `(NONE, measured 0.06)` → same; `(−0.05//16 samples, +0.05)` → `FLIPPED`; `(+0.021, −0.021)` → `KEPT`; `(0.0, 0.0)` → `BELOW_NOISE`. Row 1 is **red against today's code**: today one member abstaining flips the page (F3) |
| `RealisticOrientationContractTest` — 9 red (the 5 prose-corrected ones, `proseWithRules/Underlines/Stamp/Signature`, the photographed page, `real prose carries enough evidence`) | red | green |
| same suite — 5 green (`an upright page of real prose is not flipped`, `…under uneven light is not flipped`, `a page with no dominant line direction is not rotated`, `a page carrying only a solid block decides nothing`, `a brief note offering no evidence decides nothing`) | green | **must stay green.** The first two assert `rotationDegrees == 0` in the upright direction, which the wider band must now earn rather than pass by abstaining; the last three assert `flipDecided == false` / either-outcome and are the guard that the widening does not manufacture evidence where a symmetric page has none. That green set is inferred from the assertions, not from a run — the developer's first run prints the real counts |
| `AutoCropOrientationTest` (legacy, trapezoid fixtures) | green | must stay green (they are the regression guard for the quarter-turn/skew half of the estimate) — but per F15 they are **not evidence** for the cue, and their `wantDecided = true` cases at `:186, :215` are the kill-criterion watchpoint |

### 2.2 Carrying the flip outcome out of the imaging layer (AC-8, AC-12)

`Plan.flipDecided` is computed and dropped (F5). Two hops, both small.

**Repository / DB.** `ScanRepository.RenderedPage` gains `val flipDecided: Boolean? = null` (null = no plan ran); `appendPages` passes it to the store. `DocumentStore.insertPage(... , flipDecided: Boolean? = null, now: Long = ...)` — new parameter last before `now`, the existing return type `Long` unchanged (`:130, 143`). `PAGE_SELECT` (`:264-268`) gains `flip_decided`; `toPage()` maps it through a new `internal fun Cursor.intOrNull(column: String): Int?` beside the existing cursor helpers (`DbHelper.kt:66-78`). `DbHelper.VERSION = 2` (`:60`) and:

```kotlin
override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) db.execSQL("ALTER TABLE pages ADD COLUMN flip_decided INTEGER")  // null = unknown
}
```

`onCreate`'s `pages` table (`:29-48`) gains `flip_decided INTEGER`. Nullable, so no backfill: a null reads back as "unknown" and is never shown as a prompt — a page that predates the flag must not be accused of being undecided. `Models.Page` (`:17-33`) gains `val flipDecided: Boolean?` with that contract in its KDoc.

**Why it matters for S2 meeting S1:** the whole point of the flag is that the app can say *"I could not tell"* instead of being wrong silently, and the only place a user can act on it in flow is the review (§2.4). A flag with no consumer is the defect AC-8 describes; this design gives it exactly one consumer.

### 2.3 Page identity — the contract change that makes a strip tile tappable (AC-1..AC-5)

`insertPage` already returns the row id (F10); only `appendPages`'s return type changes.

```kotlin
suspend fun appendPages(
    documentId: Long?,
    pages: List<RenderedPage>,
    documentName: String? = null,
): AppendResult                    // was: Long

class AppendResult(val documentId: Long, val pageIds: List<Long>)   // in order, same order as `pages`
```

`saveCapture` (`:143-167`) keeps `: Long` and returns `appendPages(...).documentId`, so `importImages` (`:205`) and the editor's save path are untouched. `ScanSessionViewModel.commit` (`:229-246`) is the only caller that needs the ids. Nothing else in the repo consumes the return value (read of `ScanRepository.kt`).

### 2.4 The review: state in the session, overlay in the camera (AC-1..AC-8)

**Disk-backed, not the in-memory strip.** The strip holds ≤12 anonymous 220 px bitmaps with no page id (`ScanSessionViewModel.kt:65, 238-244`): it cannot reach page 1 of a 14-page run (AC-5) and cannot prove it shows what was committed (AC-2). The review reads `repository.pages(documentId)` — the same rows `DocumentScreen` reads — so AC-2 and AC-5 hold by construction, and `rememberFileImage(page.processedPath, …)` renders the very file that goes to the PDF. Cost: one decode per page opened, on a screen that already decodes thumbnails.

**Overlay, not a new nav route** — because of F8.

```kotlin
/** A strip tile: the id is what makes a tap able to open the page it shows. */
data class StripTile(val pageId: Long, val documentId: Long, val bitmap: ImageBitmap, val flipDecided: Boolean?)

/** The review, or null when closed. */
data class ReviewState(val pages: List<Page>, val index: Int) {
    val current: Page? get() = pages.getOrNull(index)
    val position: String get() = "${index + 1} of ${pages.size}"
}

var review by mutableStateOf<ReviewState?>(null); private set

fun openReview(pageId: Long)              // repository.pages(documentId); select that id; no-op if not found
fun closeReview()                         // review = null; nothing else is touched
fun stepReview(delta: Int)                // index.coerceIn(0, pages.lastIndex)
fun rotateReviewPage(clockwise: Boolean)  // repository.rotatePage(...); refresh the row in `review.pages`
fun flipReviewPage()                      // repository.flipPage(...); same refresh
```

`recent` becomes `List<StripTile>`, filled in `commit` from `AppendResult.pageIds` (reading order) and `RenderedPage.flipDecided`; the 12-cap becomes `STRIP_CAP` and no longer truncates anything the review uses; `.reversed()` is dropped (F12). Strip order: newest commit first, halves of one spread in reading order.

**Camera screen changes** (`CameraScreen.kt`):

- Strip tile: `Modifier.size(48.dp, 64.dp)` (meets the 48 dp minimum in one dimension, portrait aspect), `ContentScale.Fit` rather than `Crop` (`:393` shows the whole page, not a hidden crop), `clip(RoundedCornerShape(6.dp))`, `contentDescription = "Page N of M"` (`:392` passes `null` today), `Modifier.clickable(onClickLabel = "Open page N of M") { session.openReview(tile.pageId) }`.
- Review overlay: full-bleed `Box`, `background(Color.Black)`, page through `rememberFileImage(page.processedPath, maxEdge = 1800, revision = page.renderRevision)` with `ContentScale.Fit`; top row `IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Close review")` + `review.position` text (AC-3/AC-4); `Icons.Outlined.RotateRight` and a flip control (AC-7); previous/next `IconButton`s (AC-4); and, when `page.flipDecided == false`, a persistent chip above the bottom controls — *"Upside down? The automatic pass could not tell"* — wired to `flipReviewPage()` (AC-8). Patterns reused, nothing invented: the full-bleed viewer of `ViewerScreen` (`:167-171`) and its position text (`:99`).
- Init effect hardened: `LaunchedEffect(Unit) { session.prepareIfNeeded(initialMode, documentId) }`, with `prepareIfNeeded` initialising only an uninitialised session (`beginInto` idempotent for the same `documentId`). Removes the F8 hazard for any future navigation, not just this one.
- Honesty on the rotate paths, as `DocumentViewModel.rotatePage` already does (`DocumentViewModel.kt:96-98`): a repository `false` sets `session.error`, which `CameraScreen.kt:453` renders. A silent failed rotate is the defect in miniature.

### 2.5 Rotation that visibly works (AC-9, AC-10, AC-11)

**The key.** Adopting the tester's seam, in a new framework-free file `app/src/main/java/com/kscanner/core/io/ImageState.kt`:

```kotlin
/**
 * Identity of the bytes at [path] for a decode cache. Changes when the file behind an
 * unchanged path is rewritten; equal [File.length] and [File.lastModified] with a
 * different [File.absolutePath] must not collide, hence the path is part of the key.
 */
internal fun imageStateKey(path: String?): Any? =
    path?.takeIf { it.isNotBlank() }?.let { p ->
        val f = File(p)
        Triple(f.absolutePath, f.length(), f.lastModified())
    }
```

```kotlin
@Composable
fun rememberFileImage(path: String?, maxEdge: Int = 1200, revision: Any? = null): ImageBitmap? {
    val key = remember(path, revision) { imageStateKey(path) }
    val state = produceState<ImageBitmap?>(null, key, maxEdge, revision) { /* decode unchanged */ }
    return state.value
}
```

`Thumbnail` (`Components.kt:81-111`) forwards `revision`. **Residual hazard, stated not hidden:** `length + lastModified` is not a content hash — a rewrite inside the same mtime tick with an identical length is invisible on filesystems with 1 s mtime granularity. That is why every rewrite path also passes a `revision` that moves in the database: `Page.renderRevision` (`rotation * 1000 + filterId`, written by `updatePageResult` on rotate `:307` and `setFilter` on reprocess `:247`), and `document.updatedAt` for library covers (`refreshThumbnail` regenerates at a fixed path `:341-361` and `refreshDocumentStats` bumps `updated_at`, `DocumentStore.kt:103-110`). Call sites: `DocumentScreen.kt:445-452`, `ViewerScreen.kt:238` (`ZoomablePage(path, revision)`, from `:169`), the library cover thumbnail, and the review overlay of §2.4.

**The compounding fix (F6).** In `ScanRepository`:

```kotlin
/** Rotates a page by [degrees] (90 | 180 | 270) clockwise from its current state. */
suspend fun rotateBy(pageId: Long, degrees: Int): Boolean
/** Kept for existing callers. */
suspend fun rotatePage(pageId: Long, clockwise: Boolean = true): Boolean = rotateBy(pageId, if (clockwise) 90 else 270)
/** One-tap 180 for a page the pass could not decide. */
suspend fun flipPage(pageId: Long): Boolean = rotateBy(pageId, 180)

/** Guarantees `page.originalPath` holds pixels with no rotation applied in them.
 *  A page whose original IS its processed file (BOOK halves, ID-card sheets, or
 *  keepOriginals off) first gets its current pixels frozen into a new original, so a
 *  re-render can never apply a stored rotation twice. */
private suspend fun ensureReprocessableOriginal(page: Page): Page?
```

Returns the page unchanged when `originalPath != processedPath`; otherwise writes `storage.newPageFile(page.documentId, "original")` from the current processed file and returns a copy with `originalPath = newFile`, `rotation = 0`, `corners = null`. Both `rotateBy` and `reprocessPage` call it first. Cost: one file copy per such page, once. Net user effect: a rotate on a BOOK/ID page turns the page once, from frozen pixels, and every later rotate or filter change is relative to that — four taps return the page to its start, which is AC-11's on-device half.

**AC-11's JVM half.** Four quarter turns of a synthetic `RgbaImage` and of its `Quad` return the original pixels and geometry: `Transforms.rotate90/rotate180/rotateQuad90` (`Transforms.kt:84-116, 211-216`) already carry that arithmetic and there is an existing `TransformsTest.kt`; the new assertion is the four-turn round trip on an asymmetric image plus its quad, which pins the layer the rotation now delegates to.

### 2.6 The S3 seams (each step reads its file first)

| Item | Change | AC |
| --- | --- | --- |
| Library bulk delete | `LibraryScreen` gains `remember { SnackbarHostState() }` + `snackbarHost` on its `Scaffold` (`:118`), copying `DocumentScreen.kt:120`; the selection bar's `onDelete` (`:163-166`) sets `confirmDelete = selection.size` and the delete moves behind an `AlertDialog` whose text names the count (`DocumentScreen.kt:312-325` as the model). `LibraryViewModel.delete` must report how many documents went | 18, 19 |
| Last-page delete | `DocumentScreen.kt:379-388` gets the same confirmation; when `viewModel.pages.size == 1` the text adds that the document itself goes (`ScanRepository.kt:323-327`), and the confirm calls the existing `onDeleted` callback, so the pop is explained rather than silent | 20 |
| Import failures | `importImages` (`:170-210`) stops collapsing failure to `null`: `class ImportResult(val documentId: Long?, val imported: Int, val failed: Int)`; `LibraryScreen`'s picker callback (`:106-113`) opens the document when `imported > 0` and reports through the snackbar: "Imported 4 of 5 — 1 could not be read", "Nothing could be imported from those 5 images" | 21 |
| Permanent denial | `PermissionScreen` (`:538-572`) gains `onOpenSettings` and a second primary action when denied: `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))`; permission state re-read on resume (a `LifecycleEventObserver`, or a `LaunchedEffect` keyed on lifecycle state) so the preview starts on return. `shouldShowRequestPermissionRationale` decides which action is primary — read `CameraScreen.kt:1-129` first, the existing `permissionRequested` flag (`:109`) is not that question | 22 |
| Library filter state | `LibraryViewModel.query`/`sort` become the single source; `LibraryScreen`'s local `remember { mutableStateOf }` (`:94-97`) becomes derived values, so the box cannot disagree with the filter | 23 |
| Chips | `ModeChip` (`:493-518`), `ZoomChip` (`:521-535`): `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)` on the `Surface` — visual sizes and padding unchanged | 24 |
| Version footer | `SettingsScreen.kt:215` stops hardcoding `"KScanner 1.0"`. No `BuildConfig` (F9), so `PackageManager`: `if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).versionName`, inside `runCatching { … }.getOrNull()`. No dependency, no build change, and it reports the version actually installed | 25 |
| `SwitchRow` | `SettingsScreen.kt:280-303` gets a row-wide `Modifier.clickable(role = Role.Switch)` mirroring `LinkRow` (`:244-268`), keeping the `Switch`'s own `onCheckedChange` (not two independent handlers); the comment at `:253` then becomes true | 26 |
| Back icon | `Icons.Filled.ArrowBack` → `Icons.AutoMirrored.Filled.ArrowBack` at `SettingsScreen.kt:21, 60`, `DocumentScreen.kt:141`, `EditorScreen.kt:124`, `ViewerScreen.kt:105`, consistent with `CameraScreen.kt:44` | 27 |

**Rotate counted once.** The PRD lists "rotate appears to do nothing" as S3.3 *and* as S2. It is one defect with one root cause (F7 + F6), designed in §2.5, and it appears in the traceability table once — as AC-9/AC-10/AC-11.

## 3. AC traceability

`JVM` = a plain-JVM unit test in this repo can decide it. `INSPECT` = code inspection, verifiable by reading the cited seam. `DEVICE` = needs the founder's phone; **not** to be recorded as passing because a build succeeded. Steps are §4's.

| AC | Lands in | Step | Check |
| --- | --- | --- | --- |
| 1 tap a strip thumbnail opens a larger view | §2.4 tile `clickable` → `openReview(pageId)` | 8 | DEVICE; INSPECT the `clickable`+`onClickLabel` on the tile |
| 2 review shows the committed page | §2.4 `repository.pages(documentId)` + `rememberFileImage(page.processedPath)` | 8 | DEVICE (compare with the document screen); INSPECT: source is the stored row, not `recent` |
| 3 "Page N of M" is the true position | §2.4 `ReviewState.position` from the page's index in `pages` | 8 | DEVICE; INSPECT |
| 4 prev/next + indicator updates | §2.4 `stepReview` | 8 | DEVICE |
| 5 every page reachable past the 12-cap | §2.4 disk-backed source; `STRIP_CAP` never truncates `ReviewState` | 8 | DEVICE (14 pages → step to 1); INSPECT |
| 6 leaving the review keeps the session | §2.4 overlay in `CameraScreen` + `prepareIfNeeded` (F8) | 8 | DEVICE; INSPECT `prepareIfNeeded` |
| 7 rotate in the review sticks and persists | §2.4 `rotateReviewPage` → §2.5 `rotateBy` | 7, 8 | DEVICE |
| 8 undecided flip is distinguishable | §2.1 `decideFlip` → §2.2 persisted `Page.flipDecided` → §2.4 chip | 3, 5, 8 | JVM at the `decideFlip` seam; INSPECT the value reaching the UI; DEVICE that the chip appears for a symmetric page and not for a decided one |
| 9 document-screen rotate repaints, 4 taps restore | §2.5 `imageStateKey` + `renderRevision` + `ensureReprocessableOriginal` | 6, 7 | JVM for the key (rewrite a temp file ⇒ key changes; untouched ⇒ stable); INSPECT the call sites pass `revision`; DEVICE for the visible repaint |
| 10 viewer rotate repaints | §2.5 same, `ViewerScreen.kt:238` | 6 | JVM for the key; DEVICE for the repaint |
| 11 four quarter turns restore pixels and geometry; no compounding | §2.5 `ensureReprocessableOriginal`, `Transforms` round trip | 7 | JVM (4×`rotate90` on a synthetic `RgbaImage` + `Quad`) — *not* the repository path, which is Android-only; DEVICE for one 4-tap pass |
| 12 the decision is persisted | §2.2 column + `Page.flipDecided` | 5 | INSPECT (column, `onUpgrade`, `PAGE_SELECT`, write at commit, read at review). SQLite is not unit-testable here (F13) |
| 13 a flip never fires on an abstaining candidate | §2.1 (1) + `FlipEvidence.measured` | 3 | JVM `OrientationDecisionTest` — red against today's code. **The mask-level clause is not satisfied by a fixture and I say why (F16)**; the rule is pinned as a pure function instead |
| 14 non-prose ink cannot flip | §2.1 band extent + margin + `MIN_FLIP_INK*` unchanged; `proseWithRules/Underlines/Stamp/Signature` fixtures | 1, 2 | JVM — the tester's 4 fixtures + `solidBlockPage`. Risk: a rule's mass inside a wider band (see §6) |
| 15 fixtures are no longer upright by construction | tester's `RealisticPages` (uneven light, rules, underlines, stamp, signature, photograph, `detectOutline=false`) | 0 | JVM: already present and referenced by assertions. Detection-failure fixture: **partly open** — `photograph()` covers "outline that does not match"; a "no outline found" case is `detectOutline = false`, used throughout |
| 16 an ambiguous fixture is pinned to "correct or undecided" | `tablePage`, `squareTextBlock`, `sparseTextBlock` | 0, 2 | JVM (already written; must stay green) |
| 17 no fixture passes by measuring nothing | `real prose carries enough evidence…` asserts `flipDecided` on both orientations | 2 | JVM |
| 18 bulk delete confirms with a count | §2.6 row 1 | 9 | INSPECT; DEVICE |
| 19 bulk delete reports afterwards | §2.6 row 1 snackbar host | 9 | INSPECT (`Scaffold`'s `snackbarHost`); DEVICE for the text |
| 20 last-page delete warns | §2.6 row 2 | 9 | INSPECT; DEVICE |
| 21 import failures are reported | §2.6 row 3 `ImportResult` | 9 | INSPECT (`failed` reaches the UI, "all failed" distinguishable); DEVICE |
| 22 permanent denial reaches Settings and recovers | §2.6 row 4 | 9 | INSPECT; DEVICE |
| 23 no hidden filter | §2.6 row 5 | 9 | INSPECT; DEVICE |
| 24 chips are ≥48 dp | §2.6 row 6 | 9 | INSPECT; DEVICE (layout bounds) |
| 25 version footer is the running build | §2.6 row 7 | 9 | INSPECT (no hardcoded version; `PackageManager`); DEVICE |
| 26 a switch row's label toggles it | §2.6 row 8 | 9 | INSPECT; DEVICE |
| 27 no deprecated `ArrowBack` | §2.6 row 9 | 9 | INSPECT + `./gradlew :app:compileDebugKotlin` shows no deprecation for those files |

**S1/S2 do not have a JVM-testable end; three of their ACs do.** AC-8, AC-11 and AC-13-17 sit on framework-free seams and are test-decided; AC-1-7, 9, 10, 12 are DEVICE or INSPECT. That split is the honest one for this repo, not a preference.

## 4. Ordered steps

Each step is small, leaves the tree compiling, and has one exact check. `DEVICE` items are listed per step and at §5. Run the imaging steps in order — step 2's outcome decides steps 3-4's margin constants.

**0. Baseline, before any edit.** `./gradlew :app:testDebugUnitTest --tests "com.kscanner.core.imaging.RealisticOrientationContractTest"` — record the actual red/green counts (expected 9/5, but that split is inferred here, not measured). Also `git status --short app/src/test` to confirm `RealisticPages.kt` and `RealisticOrientationContractTest.kt` are present and untracked. If the counts differ, the design's §2.1 gates are still the right ones; the *set* they apply to is what changes.

**1. `textBands`.** `Orientation.kt`: add `CORE_CUT`, `SKIRT_CUT`, `textBands(profile, coreCut, skirt)` (§2.1a); `bands()` untouched and reused as the core-finder. New `app/src/test/java/com/kscanner/core/imaging/OrientationBandsTest.kt`: a hand-built `IntArray` profile (x-height block + a 2-row ascender shoulder above + a 2-row descender skirt below, a second core 12 rows down) asserting the returned range covers the ascender and descender rows and stops before the second core; and a tight-leading profile asserting no two bands overlap.
Check: `./gradlew :app:testDebugUnitTest --tests "com.kscanner.core.imaging.OrientationBandsTest"`.

**2. Wire the bands, and read the calibration.** `flipBias` calls `textBands` instead of `bands(profile, cutFraction)`; keep the cut-relaxation loop only if the cores still number <3 (proposal: drop it, since a wider band is no longer the thing that was starving the count — but the developer keeps it if a fixture needs it, and says so). Fix the KDoc (F2).
Check: `./gradlew :app:testDebugUnitTest --tests "com.kscanner.core.imaging.RealisticOrientationContractTest"` → all green; then `./gradlew :app:testDebugUnitTest` → `AutoCropOrientationTest` still green. If any test that requires `flipDecided == true` (`RealisticOrientationContractTest.kt:130-131`, `AutoCropOrientationTest.kt:186, 215`) is still red, **stop and report the measured `(gap, se)` per fixture** before changing any constant.

**3. The decision seam.** `FlipEvidence`, `FlipReason`, `FlipDecision`, `decideFlip`, `Bias.measured` + `samples`/`sumSquares`; replace `:146-150` with the three lines of §2.1c. New `OrientationDecisionTest.kt` with the five rows of §2.1's table — row 1 is red before this step and green after.
Check: `./gradlew :app:testDebugUnitTest --tests "com.kscanner.core.imaging.OrientationDecisionTest"`, then the two suites from step 2.

**4. Let the reason travel (droppable).** `Estimate.flipReason`, `AutoCrop.Plan.flipReason` (`AutoCrop.kt:112-124`).
Check: `./gradlew :app:compileDebugKotlin` and the full unit suite (no assertion changes expected).

**5. Persist the flag (AC-12).** `DbHelper.VERSION = 2`, `onUpgrade`, the `onCreate` column, `Cursor.intOrNull`, `PAGE_SELECT`, `toPage`, `Page.flipDecided`, `insertPage(…, flipDecided)`, `RenderedPage.flipDecided`, and the commit path passing `plan.flipDecided`.
Check: `./gradlew :app:compileDebugKotlin`. **No unit test can reach this** (SQLite returns defaults, F13) — the check is the compile plus the §4.5 inspection list.

**6. `AppendResult`.** `appendPages` returns `AppendResult`; `saveCapture` returns `.documentId`; `ScanSessionViewModel.commit` reads `.documentId`/`.pageIds`.
Check: `./gradlew :app:compileDebugKotlin` (every caller light up as a compile error or a clean build — there are no other consumers).

**7. The decode key.** `core/io/ImageState.kt` with `imageStateKey`; `rememberFileImage(path, maxEdge, revision)`; `Thumbnail` forwards `revision`; call sites pass it (`DocumentScreen.kt:445-452`, `ViewerScreen.kt:169/238`, library cover, review overlay); `Page.renderRevision` in `Models.kt`.
Check: `./gradlew :app:testDebugUnitTest --tests "com.kscanner.core.io.ImageStateTest"` (temp file: stable while untouched, different after a rewrite, 0/null for blank, two files with equal `length`+`mtime` differ).

**8. Rotation that cannot compound.** `ensureReprocessableOriginal`, `rotateBy`, `rotatePage` delegating, `flipPage`; `DocumentViewModel.rotatePage` switches to `rotateBy`.
Check: `./gradlew :app:compileDebugKotlin`; DEVICE (four taps on a BOOK page return it to its start and stay crisp).

**9. The review (S1 + S2's visible half).** Session: `StripTile`, `ReviewState`, `review`, `openReview`/`closeReview`/`stepReview`/`rotateReviewPage`/`flipReviewPage`, `recent` order/cap, `prepareIfNeeded`. Camera: tile sizing/`contentDescription`/`clickable`, the overlay, the undecided chip, error surfacing (`CameraScreen.kt:453`).
Check: `./gradlew :app:compileDebugKotlin`. DEVICE: AC-1-7, and the AC-6 sequence (capture 2, open, close, "Done · 2 pages" still true, Done lands in a 2-page document).

**10. S3, nine items** (§2.6). `LibraryScreen` + `LibraryViewModel`: snackbar host, delete confirmation with a count, `ImportResult` reporting. `DocumentScreen`: page-delete confirmation. `CameraScreen`: Settings route + re-read on resume, 48 dp chips. `SettingsScreen`: version from `PackageManager`, row-wide `SwitchRow`, `AutoMirrored` icons.
Check: `./gradlew :app:compileDebugKotlin` with no deprecation notice for AC-27's files, then `./gradlew :app:testDebugUnitTest` (no regressions). DEVICE: AC-18-26.

**11. Close-out.** `./gradlew :app:testDebugUnitTest` fully green; `./gradlew :app:assembleDebug` builds. Then the founder's phone pass (§5).

## 5. UNVERIFIED on this machine (and what stands in)

| Not verifiable here | Evidence that stands in | Who must confirm |
| --- | --- | --- |
| That the cue now decides correctly on a **real photograph** rather than a rendered page | `RealisticOrientationContractTest` runs the whole shipped pass (`AutoCrop.plan` with detection) on a modelled capture: page at an angle on a dark surface, bilinear resampling, gradient lamp light and a soft shadow (`RealisticPages.photograph/gradientLighting/shadowFromBottom`). That is a model, not a sensor | founder's phone |
| That a rotated page **visibly re-decodes** (AC-9, AC-10) | `imageStateTest` proves the key changes when the bytes change and is stable when they do not — *not* that a bitmap repaints: `BitmapFactory.decodeFile` returns null under `isReturnDefaultValues` and there is no Compose test harness | founder's phone (tap rotate; the PRD's own open question 6 asks whether it ever worked) |
| The SQLite upgrade path (AC-12) and every read-back | compile plus inspection of `VERSION = 2`, `onUpgrade`, `PAGE_SELECT`, `toPage`. Android stubs return defaults in JVM tests | device/emulator run of an install-over-previous |
| Overlay layout, strip tile hit area, chip and dialog text, TalkBack order, gesture behaviour | inspection of the composables cited in §2.4/§2.6 | founder's phone |
| The version footer's actual string, the Settings intent, the permission re-read on resume (AC-22, AC-25) | inspection of the `PackageManager` call and the lifecycle observer | founder's phone |
| AC-24's *measured* 48 dp | inspection of the declared `sizeIn` | device (layout bounds) |
| That BOOK pages stop compounding on a device (F6) | the `ensureReprocessableOriginal` contract plus inspection of the two `originalPath = null` call sites | founder's phone |
| The **5 green / 9 red** split of the tester's suite | inferred from the assertions, not from a run | step 0 |
| Tight leading across real fonts: whether the skirt merges two lines on a page with dense typesetting | the valley rule is asserted on a synthetic profile in step 1 | step 2's measured report, then a real page |
| `length + lastModified` as a byte identity (mtime granularity) | the `revision` that every rewrite path also moves (F7) | acknowledged residual, see §6 |

**Not claimed anywhere in this design:** that a green build means any DEVICE item passes; that any test in `AutoCropOrientationTest.kt` that leans on the trapezoid fixture is evidence for the flip cue (F15); that code no test reaches is "covered".

## 6. Risks, and the questions that are not mine to answer

**Risks**

| # | Risk | Mitigation in this design | Fallback if it bites |
| --- | --- | --- | --- |
| R1 | Widening the band merges two lines on tight leading, moving the centroid onto inter-line structure | growth stops at the first non-descending row (the valley) and never crosses the neighbouring core's midpoint | cap growth at half the core pitch; measured in step 2 |
| R2 | The noise-scaled margin re-creates the false negative it was meant to bound | explicit kill criterion in §2.1: `margin = bandMargin` alone ships if any required-decided fixture abstains | delete step 4 of `decideFlip`; keep base margins |
| R3 | Non-glyph ink inside a wider band (a rule above the text, an underline, a stamp) votes on the flip | the four ink fixtures (`proseWithRules/Underlines/Stamp/Signature`) assert the upside-down page still comes back; the majority gate and margin stand | raise `SKIRT_CUT` toward 0.05, or exclude rows with a near-flat horizontal ink profile |
| R4 | The legacy trapezoid fixtures change reading (they read a manufactured gradient that the wider band will recompute) | they must stay green; they are the regression guard for the quarter-turn and skew halves | if their `wantDecided` cases abstain, the margin is the suspect, not the band rule — report before touching either |
| R5 | A v1 install's `ALTER TABLE` path is unexercised | idempotent single statement, nullable column, no backfill | — (real risk is only that it is unverified, §5) |
| R6 | Same-second, same-length rewrite invisible to `imageStateKey` | every rewrite path also moves a DB `revision` | extend the key with a monotonic write counter |
| R7 | Decoding full pages in the review on a 40-page run | one decode per page opened, 1800 px, on a screen that already decodes thumbnails | reuse the thumbnail decode and render on demand |
| R8 | `internal fun` visibility from the app module's unit tests | Kotlin associates the test compilation with main, so `internal` is visible | drop `internal` (one word) — the test is the point, not the visibility |
| R9 | AC-15's "detection failure (no outline found)" fixture is only partly covered: `detectOutline = false` is the free pass, not a detector that fails on a page it should have found | the photographed fixtures do exercise the detector end-to-end | add a fixture whose quad misses the sheet, if the day allows |
| R10 | This design file sits in the workspace, not in the repo checkout: the sandbox denies writes under `kscanner/docs/specs/**` | §0's path note | the developer copies it in beside `prd.md` |

**Founder's-phone questions, unanswered (all from PRD §5.2 items 5-8, and none of them changes the seams above — they change the fixtures and the tuning):** which page types go wrong (prose / handwriting / tables / receipts / photos-in-page / book spreads); deterministic or run-to-run variable; lighting and framing dependence; **180° or mirrored** when it goes wrong (a mirrored page is a different bug and this design does not address it); BOOK as much as BATCH, auto-shutter on and off; **"has manual rotation ever visibly worked for you?"** — if it did, F7 is wrong and step 7 needs revisiting; whether the review should prompt after every capture or stay deliberate (this design assumes deliberate, per the request's own wording); whether a one-tap flip is also wanted in the editor.

**Not designed, flagged:** the folder feature and bulk rename (PRD non-goals/§5.2.1-2), `Estimate.confidence`'s dead code (F5), and the status-bar icon colour. `README.md:70-71` currently claims folders and bulk rename; if the founder rules those out, that is a one-line README fix, not this round.

STATUS: DESIGN_READY
