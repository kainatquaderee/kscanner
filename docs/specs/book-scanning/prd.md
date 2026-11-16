# PRD — Book scanning

Fixes a defect in `ScanMode.BOOK` and adds the affordance that makes the mode usable on a real
book. The evidence behind it is in `design.md`; the competing scanner's mechanism is `design.md` §1.

## 1. The problem

A user chooses Book, points the phone at a single page, and gets **two pages, both cut down the
middle**. Nothing in the app can put them back: the editor has no split or join. The user deletes
the pages and re-shoots, having learned that Book mode is only safe on a spread — which is not what
"Book" implies, because most of a book is one page at a time.

The app already contains the test for this (`Spread.looksLikeSpread`) and never calls it.

## 2. Scope

In:

* Book mode decides, per shot, whether the shot is a spread, and splits only then.
* A dashed centre guide is drawn in Book mode so the gutter can be aligned before shooting.

Out, and carried rather than forgotten:

* Auto-capture when the page is turned (the competing scanner's native `DetectTurnPage`). A
  heuristic stand-in would fire the shutter at the wrong moment; it is the owner's call.
* Curvature-aware dewarping and model-based deshadowing (`DetectCurveBorder`,
  `SplineFitAdjustCurves`, `deshadowImagePtr2`). A straight homography per half remains the model.
* A manual split or join action in the editor.
* Detecting which of the two halves is the left page from the text.

## 3. User stories

* As a user scanning a book, I want a shot of one page to stay one page, so that Book mode is safe
  to use for the whole book and not just for the double spreads.
* As a user framing a spread, I want to see where the gutter should sit while I frame, so that I
  can capture it straight and not discover it was crooked afterwards.

## 4. Acceptance criteria

* **AC-1** A shot of one page in Book mode is stored as one page, uncut.
* **AC-2** A shot of a spread in Book mode is still stored as two pages, cut at the gutter.
* **AC-3** The cut of a spread lands on the gutter, not the geometric middle, when the gutter is off
  centre by up to 20 % of the width.
* **AC-4** Both halves of a split spread keep the spread's perspective: each is corrected by its own
  homography, and the two are committed in reading order (left, then right) under consecutive page
  numbers.
* **AC-5** In Book mode only, a dashed guide is drawn down the horizontal centre of the preview.
  Choosing another mode removes it.
* **AC-6** The guide never intercepts a touch: tapping or pinching through it reaches the camera
  controls as before.
* **AC-7** The decision layer is framework-free and covered by JVM tests; no test needs a device.
* **AC-8** The full suite stays at 0 failures, and every existing spread, retake and batch
  behaviour is unchanged.

## 5. Risks

* **A missed split is the worse error.** A spread left as one page cannot be split in-app either.
  The decision therefore splits unless the evidence says otherwise, and the evidence is the gutter
  itself rather than a guess at content.
* **A text column can look like a gutter.** A single page with a dense column of text down its
  middle darkens the centre band. AC-1's tests include exactly that case.
* **The guide can be mistaken for a control.** It is a line, not a handle, and is drawn under the
  camera controls (AC-6).