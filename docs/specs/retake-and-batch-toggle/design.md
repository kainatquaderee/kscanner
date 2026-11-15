# Design — Re-take in the review, append order, and Batch as a toggle

Accompanies code shipped in v1.5.0. The PRD's acceptance criteria are cited as AC-n.

## 0. Method, and what counts as evidence here

Three constraints shaped every decision below:

1. **The imaging core stays framework-free.** Plain `IntArray` / `GrayImage`, no `android.graphics`,
   no Compose, no OpenCV, so it runs in plain JVM tests. Anything that touches SQLite or a Composable
   is therefore outside the unit-testable surface (F5).
2. **Robolectric is not available offline** in this environment, so there is no way to test the
   database layer here. The data-layer changes are verified by inspection and on the device, and are
   *not* claimed to be test-covered.
3. **No device at the keyboard.** Every DEVICE item below is recorded as unverified by me, not as
   passing because the build is green.

## 1. Findings

**F1 — The append index was derived from a row count.** `appendPages` took its starting index from
`store.countPages(id)`. A count answers "how many rows are left", not "which slots are in use". Once
any page is deleted from the middle, the two answers disagree and the next capture overwrites the
slot of a page that is still there — the owner's "new capture lands in the middle". Confirmed by
reading both SQL statements; the fix is a second query, not a change to the camera.

**F2 — Four modes cannot express two independent questions.** `ScanMode` carried both "what one
shot becomes" and "does the camera keep going", through `isContinuous = this != SINGLE`. Book and ID
card were permanently continuous, so "a spread, and then stop" was unsayable (PRD S3).

**F3 — A re-take must be aimed at a row, not at a position.** The strip and the review both hold
page ids, and a page that moves still has to be replaceable. Aiming at an index would replace
whatever happens to sit there after a reorder; aiming at a row id replaces the page the user was
looking at. The replacement then *adopts* the old row's index, which is what keeps the document's
order intact (AC-3).

**F4 — Insert-then-delete as two writes can lose a page.** Two separate writes leave two rows
claiming one `page_index` if the process dies between them, and nothing in the UI can repair that.
`SQLiteDatabase.insert` also reports failure by returning `-1` rather than throwing, so a naive
"insert, then delete" would commit the delete of a page it failed to replace.

**F5 — The data layer is not unit-testable here.** No Robolectric in the offline cache, and
`SQLiteDatabase` under the JVM stubs returns defaults. Anything that had to be *test-decided* therefore
had to be pushed into pure code — which is why the migration decode is a companion function over a
`String?` and not logic inside the preferences read (F6).

**F6 — The migration had to be pure to be checkable.** Reading the old mode and deciding what Batch
means for it is the one part of the toggle that can silently change a user's behaviour. Expressed as
`ScanMode.decodeStored(raw): Pair<ScanMode, Boolean>` it becomes eight JVM assertions; expressed inline
in `SettingsStore.load` it would be unverifiable in this environment.

**F7 — First shots and re-shots were drifting apart.** The capture path decoded, planned, filtered and
rendered inline, so a re-shot would have needed a second copy of that sequence. A page produced by a
different path than its neighbours is a defect waiting to happen — differently cropped, differently
filtered. The sequence is now `measure(path, taken): Measured`, used by both.

## 2. The seams

### 2.1 Shape versus run (AC-9, AC-10, AC-11, AC-12, AC-13)

`ScanMode` is now shape only — `SINGLE`, `BOOK`, `ID_CARD` — with `isOnePagePerShot` (`SINGLE` alone)
and the existing `aspectHint`. `isContinuous` is gone.

The persisted toggle is `AppSettings.batch`, `KEY_BATCH = "batch"`. `SettingsStore` resolves the two
together:

| stored `scan_mode` | shape | Batch |
| --- | --- | --- |
| `BATCH` (≤1.4.0) | SINGLE | on |
| `BOOK` | BOOK | on |
| `ID_CARD` | ID_CARD | on |
| `SINGLE` | SINGLE | off |
| absent / empty / unknown | SINGLE | off |

`decodeStored` is a pure companion function; `SettingsStore` calls it once into a `storedMode`
property declared **before** `MutableStateFlow(load())`, so `load()` sees it. An explicit
`KEY_BATCH` wins over the implied value, so a user who turns the toggle off after upgrading is not
overruled by the mode they inherited.

In the session, `batch` is mutable state initialised from `settings.current.batch` and set by
`prepare(initial, initialBatch)`. The single derived question the UI needs is:

```kotlin
val capturesToEditor: Boolean get() = mode == ScanMode.SINGLE && !batch
```

This replaces `isContinuous` at every call site: the strip, the Done button, and the decision to hand
the shot to the editor.

**Batch-off semantics.** The run ends *after the unit is committed*, not before it. `consume` takes
a callback and the camera passes:

```kotlin
session.consume(path) { if (!session.batch) session.documentId?.let(onFinished) }
```

The callback fires only when the commit added pages, which is what makes two cases right without
special-casing either: the front of an ID card adds no page, so Batch off does not end the run
half-way through a card; and a re-take adds no page, so it never ends a run (AC-14).

### 2.2 The append index (AC-8)

```sql
SELECT COALESCE(MAX(page_index) + 1, 0) AS n FROM pages WHERE document_id = ?
```

`appendPages` reads it once per commit and increments per page, so a spread (two pages from one shot)
still gets consecutive slots rather than two pages at the same index.

### 2.3 Re-take: identity, atomicity, and the armed shot (AC-1 … AC-7)

The arm is two pieces of session state — `retakeTarget: Long?` and `retakePosition: Int?` — and
`canRetake = mode.isOnePagePerShot && batch && documentId != null`. Arming writes no data:

```kotlin
fun retakeReviewPage() {
    val state = review ?: return
    val page = state.current ?: return
    if (!canRetake) return
    retakeTarget = page.id
    retakePosition = page.index + 1
    status = "Re-take: shoot the replacement for page ${page.index + 1}"
    review = null
}
```

`consume` **claims** the target before launching, so exactly one shot is a re-shot:

```kotlin
val target = retakeTarget
retakeTarget = null
retakePosition = null
```

`updateMode` and `updateBatch` clear the arm, because the page a re-take would replace is a property
of the run it was armed in. `beginInto` clears the same two fields *directly* rather than through
`cancelRetake`, so opening a new run cannot wipe the status line that `prepare` just set.

The write path is `ScanRepository.retakePage(pageId, replacement)`:

1. read the old row (it must still exist — otherwise nothing is written);
2. write the replacement's pixels to a new page file, and its original when `keepOriginals`;
3. `DocumentStore.replacePage(...)`, which inserts the new row and deletes the old **inside one
   transaction**, checking the insert's `-1` before the delete, and reusing `pageValues(...)` so the
   insert and the replacement cannot drift in their column list;
4. only then delete the old row's files (skipping the second delete when original and processed are
   the same file, which they are with `keepOriginals` off);
5. re-run OCR when it is on, and refresh the document thumbnail.

If step 3 throws, the transaction rolls back, the old page and its files survive, and the files
written in step 2 are deleted so a failed attempt leaves nothing behind.

On the session side the replacement reports **zero pages added**, which is what keeps it out of the
page count and out of the Batch-off ending logic. The strip holds row ids, and the row id changed, so
the tile is swapped in place:

```kotlin
recent = recent.map { tile ->
    if (tile.pageId == pageId) {
        tile.copy(pageId = newId, bitmap = thumbnail, flipDecided = replacement.flipDecided)
    } else {
        tile
    }
}
```

### 2.4 One render path for first shots and re-shots (F7)

`measure(path, taken)` returns a private `Measured(source, filter, plan, base)` with three exits:

- `wholeShot()` — the whole shot, cropped to the detected outline when there is one;
- `singlePage(path)` — the same, in the form a page is stored as;
- `spreadPages()` — the former `renderSpread`, moved in, still finding the gutter on the flattened
  spread.

`prepareAndStore` switches on the shape and `replacePage` asks for `singlePage`, so a re-shot is
measured, filtered and cropped by the same rules as the page it replaces. It is also passed the mode
at the time of the shot rather than reading the session's current mode, so a shape change mid-capture
cannot reinterpret a shot already in flight.

### 2.5 The UI (AC-2, AC-12, AC-13)

- One `FlowRow` holds the three shape chips and the Batch chip. Batch is deliberately *not* styled as
  a mode chip: modes light up white, Batch uses the theme's primary colour and a check icon when on,
  so it reads as a switch rather than a fourth shape.
- `runHint(mode, batch)` says "Page after page" for Single with Batch on, and otherwise falls back to
  the shape's own hint — the editor hint would be a lie there.
- The strip and Done are gated on `!capturesToEditor`.
- While armed, a pill states "Re-take page N" with a cancel icon; after the shot, `status` reports
  "Page replaced".
- The review's Re-take sits in the bottom control row between the previous and next arrows, and
  appears only when `canRetake`; rotate and flip stay in the top bar where they were.

## 3. AC traceability

`JVM` = decided by a plain-JVM test. `INSPECT` = verifiable by reading the cited seam. `DEVICE` = needs
the owner's phone; **not** recorded as passing because a build succeeded.

| AC | Lands in | Check |
| --- | --- | --- |
| 1 Re-take offered only for one-page, continuous runs | `ScanSessionViewModel.canRetake`, `ScanMode.isOnePagePerShot`, `CameraScreen` review row | JVM (`isOnePagePerShot` for `SINGLE` alone); INSPECT `canRetake`; DEVICE that the button is absent in Book, ID card and Batch off |
| 2 Arming closes the review and states the target | `retakeReviewPage`, `CameraScreen` banner | INSPECT; DEVICE |
| 3 The replacement takes the old slot | `ScanRepository.retakePage` → `DocumentStore.replacePage(index = previous.index)` | INSPECT; DEVICE (order before/after) |
| 4 Later captures append at the end | `appendPages` → `nextPageIndex`, incremented per page | INSPECT; DEVICE |
| 5 Nothing written until the shot | `retakeReviewPage` sets fields only; write in `consume` | INSPECT; DEVICE (arm, cancel, page unchanged) |
| 6 The arm is one shot and is cleared by mode/Batch | `consume` claims the target; `updateMode`/`updateBatch` call `cancelRetake`; `beginInto` nulls the fields | INSPECT |
| 7 A failed re-take loses nothing | `DocumentStore.replacePage` transaction + `-1` check; file deletes after the swap; orphan cleanup in `retakePage` | INSPECT only (F5) |
| 8 A capture after a delete is last and unique | `DocumentStore.nextPageIndex` | INSPECT; DEVICE (F5: not unit-testable) |
| 9 Three shapes, Batch separate | `ScanMode`, `AppSettings.batch`, `KEY_BATCH` | JVM (`ScanModeTest`); INSPECT |
| 10 Legacy settings keep their behaviour | `ScanMode.decodeStored` | JVM — five stored values incl. empty and unknown |
| 11 Explicit Batch beats the implied value | `SettingsStore.load` → `getBoolean(KEY_BATCH, storedMode.second)` | INSPECT |
| 12 Strip/Done only for runs that keep going | `capturesToEditor` at both call sites | INSPECT; DEVICE |
| 13 Settings offers it; the camera opens with it | `SettingsScreen` `SwitchRow`; `MainActivity` `initialBatch` | INSPECT; DEVICE |
| 14 Batch off ends the run only after real pages | `consume`'s `onPagesAdded` callback; replacement returns 0 | INSPECT; DEVICE (ID card front does not end it) |

## 4. Ordered steps, as built

Each step left the tree compiling and the suite green.

0. **Baseline.** `./gradlew :app:testDebugUnitTest --offline --rerun` — 169 tests in 18 classes before
   any edit.
1. **`ScanMode`.** Drop `BATCH` and `isContinuous`; add `isOnePagePerShot` and `decodeStored`. New
   `app/src/test/java/com/kscanner/data/ScanModeTest.kt` (8 cases, JUnit4 — `kotlin.test` is not on the
   test classpath).
   Check: `:app:testDebugUnitTest --offline --rerun`.
2. **`SettingsStore`.** `AppSettings.batch`, `KEY_BATCH`, and `storedMode` declared before the
   `MutableStateFlow`.
3. **`DocumentStore.nextPageIndex`**, and `appendPages` switched onto it. Check: INSPECT + DEVICE.
4. **`DocumentStore.replacePage`** + `pageValues`, then **`ScanRepository.retakePage`**.
5. **Session: Batch.** `batch`, `capturesToEditor`, `updateBatch`, `prepare(initial, initialBatch)`,
   `prepareIfNeeded(..., initialBatch, ...)`.
6. **Session: re-take.** `retakeTarget`/`retakePosition`, `canRetake`, `retakeReviewPage`,
   `cancelRetake`, the `consume` branch, `replacePage` reporting zero.
7. **Session: `measure`/`Measured`**, so both paths share one render.
8. **`CameraScreen`.** `FlowRow` with the Batch chip, `runHint`, the `capturesToEditor` gates, the
   armed banner, and Re-take in `ReviewOverlay`.
9. **`SettingsScreen` / `SettingsViewModel` / `MainActivity`.**
10. **Docs** — README, this spec, `docs/releases/v1.5.0.md`. Check: every claim re-read against the
    code, since the release notes are published.

Full suite after step 9: **177 tests, 19 classes, 0 failures, 0 errors, 0 skipped**, counted from
`app/build/test-results/testDebugUnitTest/*.xml` after a `--rerun` — not from the summary line.

## 5. UNVERIFIED on this machine (and what stands in)

| Not verifiable here | Evidence that stands in | Who must confirm |
| --- | --- | --- |
| That a capture after a delete lands at the end (AC-8) | the SQL, and `appendPages` using it | owner's phone — SQLite has no JVM test path here (F5) |
| That a re-take keeps the page's position (AC-3, AC-4) | `previous.index` reaches `replacePage`; `nextPageIndex` ignores replaced rows | owner's phone |
| The transaction actually rolls back on a failed insert (AC-7) | inspection of `beginTransaction`/`setTransactionSuccessful`/`endTransaction` and the `-1` check | not exercised by any test; a device run would need an artificial failure |
| The armed banner, the Batch chip's look, the review's Re-take placement | inspection of the composables in §2.5 | owner's phone |
| That the owner's stored mode decodes as expected | `decodeStored` is pinned for every value it can receive | owner's phone: open the camera and read the hint; open Settings and read the switch |
| That Batch off leaves for the document screen after the unit | `consume`'s callback and the add-count rule | owner's phone |
| That TalkBack announces the Batch chip's state | — nothing: the chip has no `stateDescription` (PRD §5.1) | owner's decision whether to add it |

**Not claimed:** that a green build means any DEVICE row passes; that the data-layer changes are
covered by tests; that re-take works for shapes it is not offered for.

## 6. Risks, and the questions that are not mine to answer

- **A crash between the file write and the transaction** leaves an unreferenced page file. Nothing
  sweeps it: `sweepCaptures` is still uncalled and only looks at `cacheDir/captures`. Small, and the
  same class of leak the append path already has.
- **`MAX(page_index) + 1` is a fresh query, not a stored counter.** That is what makes it correct
  after a delete; it also means two concurrent appends into one document would both read the same
  answer. The camera is a single `consume` at a time (`busy`), so this is a note rather than a defect
  today, but the guard lives in the UI, not in the store.
- **The re-take's OCR re-run** is a visible pause on slow pages (PRD §5.2).
- **`canRetake` depends on `batch`, not on whether the document is open.** The `documentId != null`
  clause is what encodes the latter; if a future screen opens the review without a document, this is
  the line that has to change.