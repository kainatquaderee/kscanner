# PRD — Batch review, orientation honesty, and the top usability defects

- **Slug:** `batch-review-and-orientation`
- **Owner:** Product (this document defines *what* and *why*; the architect defines *how*)
- **Repo / tip at time of writing:** `/home/kainatq/Downloads/kscanner`, branch `main`, tip `39f8eba`, package `com.kscanner`
- **Scope:** one working day. Three founder-reported problems, plus a founder-chosen, evidence-driven list of defects.
- **Status:** draft for development. Every criterion is checkable by running something or by reading a named file; criteria that need a rendered screen are marked **[DEVICE-ONLY]**.

### Environment note (affects how criteria can be checked)

This project has **no emulator and no `/dev/kvm`** in the working environment, and the repo has **no `androidTest` source set** — only `app/src/main` and `app/src/test` (plain JVM JUnit). Therefore:

- Criteria that need a rendered screen cannot be automated here. They are marked **[DEVICE-ONLY]** and must be checked by a human on a physical phone. They must not be recorded as "passing" because a build succeeded.
- Criteria checkable by JVM unit test or by code inspection are labelled as such.

---

## 1. Problem statement

### S1 — In continuous modes you cannot see what you captured

Batch, book and ID-card scanning capture and commit without asking anything. The only feedback while scanning is a row of small anonymous images under the shutter (`CameraScreen.kt:382-400`: 46dp, `ContentScale.Crop`, `contentDescription = null`). Those images are not wired to a tap target — there is no `clickable`/`onClick` on them — so tapping does nothing. The user has no way to check what was captured while scanning, and that strip is the *only* view of a captured page in these modes.

**Why single capture is different (the asymmetry).** A single capture never commits: `CameraScreen.shoot()` routes it to the editor instead (`CameraScreen.kt:168-172` → `MainActivity.kt:115-117`), where the user sees the result in a full-screen crop stage and can act before saving. The continuous modes route the same shot into `ScanSessionViewModel.consume` (`:169`), which processes and commits, and only then reports a page count. By the time a user could look, the page is already on disk.

The strip is also thin on the data it carries: the session keeps up to 12 anonymous 220px bitmaps with no page id and no file path (`ScanSessionViewModel.kt:65`, `:238-244`), the page row id returned by the store is discarded (`ScanRepository.kt:113-125`), and `appendPages` returns only the `documentId` (`:134`). Nothing in the session can currently resolve a strip tile back to the page it represents.

### S2 — Pages come out upside down, and the manual fix does not work

Founder's report: *"the image most of the time gets inverted vertically (upside down), had to do manual rotation to fix those"*, and, asked for a pattern: **"worse in batch mode."**

Two distinct user problems live inside that sentence:

1. **The automatic orientation pass turns some pages the wrong way.** The 0-versus-180 decision lives in `core/imaging/Orientation.kt` and is consumed by `AutoCrop.plan` (`AutoCrop.kt:84-85, 112-124`). Recon produced hypotheses (listed in §5.1; they are hypotheses, not findings):
   - the flip margin may sit below the measurement's own noise floor (margin 0.02 band heights, `Orientation.kt:54`; the code's own comments describe per-band readings scattering 0.02–0.07 at `:74-76`);
   - `Bias.NONE` is `Bias(0.0, 0)` (`:174`) and is fed into the decision as a genuine reading (`:148`), so one candidate abstaining can itself decide the flip;
   - `Plan.flipDecided` is computed (`AutoCrop.kt:114`) and then consumed nowhere in the app — no persistence, no UI. `Models.Page` has no field for it (`Models.kt:17-33`). So a distinction the engine already knows how to make — "I decided" versus "I had no evidence" — never reaches the user.
2. **The manual workaround is very likely broken.** `rememberFileImage` (`Components.kt:45-54`) keys its `produceState` on `(path, maxEdge)`, while rotation rewrites the *same* file path (`ScanRepository.kt:298-301`) and `DocumentViewModel.rotatePage` refreshes by reloading rows whose `processedPath` is unchanged (`DocumentViewModel.kt:92-101`). If that holds on device, the user taps rotate, the file on disk changes, and the displayed bitmap never re-decodes. **This is treated below as a first-class requirement, not a footnote.**

**Why it feels worse in batch.** In single capture the user meets the auto result before it is saved and can rotate in the editor; in batch the page is committed immediately after processing (`ScanSessionViewModel.kt:147-162` → `commit` → `repository.appendPages`) with no review step — and the review that would expose it does not exist (S1). The batch user finds out later, in the document, where the fix may not visibly work.

### S3 — "Polish more", scoped to the highest-impact defects

The founder chose an evidence-driven list over taste-based visual work. Verified with file:line:

1. **Bulk delete is unrecoverable and silent.** `LibraryScreen.kt:163-166` deletes the selection with no confirmation, no undo, and no `snackbarHost` on that screen's `Scaffold` (`:118-198`; by contrast `DocumentScreen.kt:120`, `EditorScreen.kt:108` and `ViewerScreen.kt:94` all install one). Files are really deleted (`ScanRepository.kt:52-55` → `StorageManager.kt:57-58`, `deleteRecursively`), so this is permanent data loss with no feedback.
2. **Deleting the last page silently deletes the document.** `DocumentScreen.kt:379-388` deletes a page with no confirmation; `ScanRepository.kt:323-327` deletes the whole document when no pages remain, and `DocumentScreen.kt:386` pops the screen. A user tidying up loses a named document and the screen without warning.
3. **Rotate appears to do nothing.** Same root cause as S2; counted once (AC-9/AC-10).
4. **Import failures vanish.** `LibraryScreen.kt:106-113` acts only on a non-null id; `ScanRepository.kt:174-209` counts failures and returns `null` only when *every* image failed, discarding partial-failure detail entirely. Partial failure loses pages silently; total failure says nothing at all.
5. **After permanent camera denial, the screen is a dead end.** `CameraScreen.kt:111-129` requests on entry, and `PermissionScreen` (`:555-570`) wires "Allow camera" to `permissionLauncher.launch(...)` unconditionally, with no route to the app's system settings anywhere in the app. The body text at `:557` tells the user they can grant it in Settings — a route the UI does not provide.
6. **Search/sort UI state is not the state that filters the list.** `LibraryScreen.kt:94-101` holds `searchOpen`, `query` and `sort` in local `remember`, while `LibraryViewModel` keeps its own `query`/`sort` (`LibraryViewModel.kt:34-49`) and filters on those. Returning to the library can therefore show a filtered list with no visible filter — indistinguishable from data loss.
7. **Delete is confirmed for a document but not for a page.** `DocumentScreen.kt:312-325` confirms document deletion; `:379-388` does not confirm single-page deletion.
8. **Capture-screen chips are below the touch minimum.** `ModeChip` and `ZoomChip` (`CameraScreen.kt:493-535`) are built from a 15dp icon / label text plus 6dp vertical padding, with no minimum touch target — roughly 28–30dp tall by inspection, against the 48dp platform minimum, on the app's primary screen.
9. **The footer states the wrong version.** `SettingsScreen.kt:215` hardcodes `"KScanner 1.0 · ..."`, while `build.gradle.kts:53-54` takes `versionName` from CI (`:40-43`) with a local fallback of `"1.0.0"`. A wrong number presented to users as fact.
10. **`SwitchRow`'s label is inert while `LinkRow`'s row is tappable.** `SettingsScreen.kt:279-303` makes only the `Switch` interactive; `LinkRow` (`:243-268`) makes the whole row tappable, and its comment (`:253`) claims it matches "the vertical rhythm of SwitchRow above" — a parity that does not exist.
11. **Deprecated back icon drift.** `Icons.Filled.ArrowBack` is used in `SettingsScreen.kt:60`, `DocumentScreen.kt:141`, `EditorScreen.kt:124`, `ViewerScreen.kt:105`, while the project already uses the AutoMirrored form elsewhere (`CameraScreen.kt:44`).

---

## 2. User stories (prioritised)

Priority is by user-visible impact against cost, for one working day. **P0 = must ship this round.**

### P0

- **US-1** *(S1)* As someone scanning a stack of pages in batch/book/ID-card mode, I want to open a page I just captured and look at it properly, so that I know the shot is usable before the run ends.
- **US-2** *(S1)* As a batch scanner, I want to reach *every* page of the document I am building, not just the last dozen thumbnails, so that a bad page early in a long run can still be found.
- **US-3** *(S1 + S2)* As a batch scanner who notices a page is upside down, I want to turn it the right way up from the review and have that stick, so that I do not have to remember to fix it later.
- **US-4** *(S2)* As anyone whose page came out upside down, I want the rotate action to visibly change the page immediately, so that I can trust the fix and stop doubting my own eyes.
- **US-5** *(S2)* As a user, I want the app to tell me when it could not tell which way up a page was, so that "the app guessed wrong" becomes "the app asked me to check".
- **US-6** *(S3.1)* As a user deleting several documents at once, I want to be asked first and told afterwards what happened, so that I cannot lose a library by one mis-tap.
- **US-7** *(S3.2)* As a user deleting the last page of a document, I want to be warned that the whole document goes with it, so that a tidy-up does not silently destroy a named document.
- **US-8** *(S3.4)* As a user importing images from the gallery, I want to know when some or all of them failed, so that missing pages are explained instead of being discovered weeks later.

### P1

- **US-9** *(S3.5)* As a user who denied camera access permanently, I want a working route to the app's settings, so that the screen's own instructions are true.
- **US-10** *(S3.6)* As a user returning to the library, I want to see *why* the list is filtered, so that a filtered view never looks like lost data.
- **US-11** *(S3.8)* As a user on a phone, I want the mode and zoom controls to be as easy to hit as the shutter, so that switching mode one-handed is not a lottery.

### P2

- **US-12** *(S3.9)* As a user, I want the version shown in the app to be the version I am running.
- **US-13** *(S3.10)* As a user, I want tapping the label of a setting to work as well as tapping the switch.
- **US-14** *(S3.11)* As a maintainer, I want no deprecated icon usage, so that build warnings mean something.

---

## 3. Acceptance criteria

Each criterion is independently checkable. **[DEVICE-ONLY]** marks criteria that require a rendered screen and a physical phone: **these cannot be verified in this environment** and must be checked by a human.

### S1 — seeing what was captured

**AC-1** — In any continuous mode (BATCH, BOOK, ID_CARD), tapping a thumbnail in the strip under the shutter opens a larger view of that page.
*Check: **[DEVICE-ONLY]**. Capture three pages in batch mode, tap the second thumbnail; a larger view of that page appears. Repeating with a single captured page must not crash.*

**AC-2** — The review shows the page **as committed to the document** — the same rendered pixels that will go into the exported PDF — not the pre-crop frame.
*Check: **[DEVICE-ONLY]** — compare the review against the same page in the document screen; the two must look identical. *Code inspection:* the review resolves its image from the stored page (page record / processed file), not from the session's anonymous thumbnail list.

**AC-3** — The review identifies the page by its position in the document (e.g. "Page 3 of 7"), and that position is the one the page really holds.
*Check: **[DEVICE-ONLY]**. Capture 3 pages, open the review on the newest, and compare the stated position with the page grid after tapping Done.*

**AC-4** — The review can move to the previous and next page of the document under construction, and its position indicator updates with each move.
*Check: **[DEVICE-ONLY]**.*

**AC-5** — Every page of the document under construction is reachable from the review, including pages older than the twelve the strip keeps.
*Check: **[DEVICE-ONLY]**. Capture 14 pages in batch mode, open the review, and step back to page 1. *Code inspection:* the review's source of pages is not truncated by the strip's 12-entry cap.

**AC-6** — Leaving the review returns to the camera with the session intact: mode, page count, the "Done · N pages" button and the strip are all as they were, with no recapture and no loss.
*Check: **[DEVICE-ONLY]**. Capture 2 pages, open and close the review, confirm the button still reads 2 pages and that Done still lands in a document holding 2 pages.*

**AC-7** — Rotating a page from the review visibly changes the page without leaving the review, and the change persists into the document.
*Check: **[DEVICE-ONLY]**. In the review, rotate once: the image turns immediately. Close, tap Done, open the document: the page is rotated there too.*

**AC-8** — A page whose automatic flip was *not* decided is distinguishable in the product from one that was, so the user can be prompted to check it.
*Check:* code inspection — the "flip decided" distinction produced by the plan (`AutoCrop.kt:114`) is carried out of the capture path and is reachable by the UI, rather than dropped as it is today. *[DEVICE-ONLY]* confirmation that the prompt appears for an undecided page (e.g. a symmetric page such as a table or a page of bars) and not for a decided one.

### S2 — orientation

**AC-9** — Rotating a page on the document screen updates the thumbnail **without leaving the screen**, and rotating it four times returns it to its original orientation.
*Check: **[DEVICE-ONLY]**. Long-press a page → "Rotate right": the thumbnail visibly turns. Three more times: it is back where it started. *Code inspection:* the image cache key changes when the file at a path is rewritten, so a same-path rewrite cannot be served from a stale decode.

**AC-10** — Rotating a page in the viewer updates the displayed page without leaving the viewer, and the change is visible in the document grid afterwards.
*Check: **[DEVICE-ONLY]**. Open the viewer, tap rotate: the page visibly turns in place. Back, re-open the document: the same page shows rotated. *Code inspection:* the same stale-decode hazard as AC-9 is absent on this path.

**AC-11** — Rotation state accumulates correctly: four successive quarter turns record a full 360° and the rendered result matches the original, and rotating an already-rotated page renders from the original capture rather than compounding re-encodes.
*Check:* JVM unit test at the imaging/transform layer, asserting that four successive quarter turns of a synthetic image and its outline return the original pixels and geometry. That layer is framework-free by design, so it is unit-testable without a device. *[DEVICE-ONLY]* one pass confirming the on-device result after four taps.

**AC-12** — The orientation decision is persisted with the page, so a page's measured outcome survives the capture session.
*Check:* code inspection — a stored home for the flag exists (a column or equivalent) and the value written at commit is read back on the document/review path. Today `Models.Page` (`Models.kt:17-33`) has no such field.

**AC-13** — The flip decision never turns on a candidate that abstained. Where one of the two 0/180 candidates produced no usable evidence (`Bias.NONE`), the outcome is "undecided", never a flip justified by the absent reading counting as 0.0.
*Check:* JVM unit test on `Orientation.estimateInk` (which accepts a mask directly, `Orientation.kt:109`) with a constructed mask whose mirrored direction yields no bands; assert `flipDecided == false` and `rotationDegrees == 0`. This test also **confirms or refutes hypothesis H2** in §5.1.

**AC-14** — Ink that is not prose cannot flip a page: a page carrying only a long thin rule, only a table grid, or only a speck of ink below the engine's floor is left as captured, in both directions.
*Check:* JVM unit test using the existing synthetic fixture helpers plus new ones; assert "left as captured, undecided" for each, and that turning the fixture 180° gives the same answer. This test **confirms or refutes hypothesis H1** in §5.1.

**AC-15** — The suite's fixtures are no longer "upright by construction". At least one fixture uses realistic letterforms with an asymmetric baseline (not a shape drawn narrower at the top by design, as `TestImages.kt:113-137` does), at least one uses uneven lighting across the page, at least one uses non-glyph ink (rules, underlines, grid lines, stamps) alongside text, and at least one exercises detection failure (no outline found, or an outline that does not match the page).
*Check:* JVM unit test — the new fixtures exist and are referenced by assertions. The invariant asserted is the one the existing suite already states (`AutoCropOrientationTest.kt:16-20`): *the cue may decline to decide, but it must never decide wrong.*

**AC-16** — At least one fixture is genuinely *ambiguous* about the flip, and the suite pins the required behaviour on it: either the correct turn, or "undecided and left as captured" — never a wrong turn.
*Check:* JVM unit test; the fixture is named in the test and the assertion is one of those two outcomes.

**AC-17** — No fixture can pass by measuring nothing: every fixture that asserts a decided turn also asserts that the fixture is measurable (band count / ink fraction above the engine's floors), mirroring the detector-corner-error guard already used at `AutoCropOrientationTest.kt:180-184`.
*Check:* JVM unit test.

*Test command for AC-11 and AC-13 – AC-17:* `./gradlew :app:testDebugUnitTest` (JVM, no device required). Android-bitmap paths are not JVM-testable, which is why these criteria sit at the framework-free imaging layer.

### S3 — the defect list

**AC-18** — Bulk delete asks first, stating how many documents are about to be deleted, and the delete happens only on confirmation.
*Check: **[DEVICE-ONLY]**. Long-press to select two documents, tap the trash icon: a confirmation naming the count appears; Cancel leaves both documents in place; Delete removes exactly those two. *Code inspection:* the selection bar's delete path (`LibraryScreen.kt:163-166`) is behind a confirmation.

**AC-19** — After a bulk delete the library reports the outcome, and the library screen has a place to report it.
*Check:* code inspection — `LibraryScreen`'s `Scaffold` (`:118-198`) installs a snackbar host, as `DocumentScreen.kt:120`, `EditorScreen.kt:108` and `ViewerScreen.kt:94` already do. *[DEVICE-ONLY]* the message actually appears after deleting, naming what was removed.

**AC-20** — Deleting the last page of a document warns that the document itself will be removed, and the user is not silently dropped back to the library.
*Check: **[DEVICE-ONLY]**. Delete the only page of a one-page document: the warning names the document consequence; after confirming, the return to the library is explained, not silent. *Code inspection:* the page-delete path (`DocumentScreen.kt:379-388`) is behind a confirmation, consistent with document deletion at `:312-325`.

**AC-21** — Importing gallery images reports failures: when some images fail, the user is told how many of how many were imported; when all fail, the user is told nothing was imported.
*Check: **[DEVICE-ONLY]** with a mixed selection (include at least one undecodable/unsupported file). *Code inspection:* the failure count `ScanRepository.importImages` already computes (`:174-209`) reaches the UI instead of being discarded, and the "all failed" case (`:209`) is distinguishable from success.

**AC-22** — When camera permission has been permanently denied, the screen's primary action takes the user to the app's system settings page rather than re-requesting a permission the system will no longer prompt for; after granting there and returning, the camera preview starts without restarting the app.
*Check: **[DEVICE-ONLY]**. Deny twice (permanently), reopen the camera screen, tap the primary action: the app's settings page opens; grant, press back: the preview appears. *Code inspection:* a settings route exists for the denied state (`CameraScreen.kt:122-129, 555-570`) and the permission state is re-read on return.

**AC-23** — Returning to the library never shows a filtered list without a visible explanation: if the persisted query/sort/folder is still filtering, the search field can be opened and shows the term; a "hidden filter" state is not reachable.
*Check: **[DEVICE-ONLY]**. Search for a term, open a document, press back: the list is either unfiltered or visibly showing the term. *Code inspection:* the search box's visibility and displayed text derive from the same state the view model filters on (`LibraryViewModel.kt:34-49`), not from screen-local state alone (`LibraryScreen.kt:94-101`).

**AC-24** — The mode chips and the zoom chips on the capture screen each present a touch target of at least 48dp in both dimensions.
*Check: **[DEVICE-ONLY]**, measured with developer options → "Show layout bounds" or the Layout Inspector on `CameraScreen.kt:493-535`. *Code inspection:* a minimum touch target is declared for both composables (neither declares one today).

**AC-25** — The version shown in Settings is the version of the running build.
*Check:* code inspection — `SettingsScreen.kt:215` does not hardcode a version; it reads the build's version string, whose single source is `build.gradle.kts:53-54` (overridden by CI at `:40-43`). *[DEVICE-ONLY]* the footer number matches the installed build's version.

**AC-26** — Tapping the label of a setting with a switch toggles that switch, matching the row-wide target of `LinkRow`.
*Check: **[DEVICE-ONLY]**. Tap the words "Recognise text automatically": the switch toggles. *Code inspection:* `SwitchRow` (`SettingsScreen.kt:279-303`) has a row-wide target, and the comment at `:253` claiming parity with `SwitchRow` is either made true or corrected.

**AC-27** — No deprecated `ArrowBack` usage remains.
*Check:* code inspection / build output — `Icons.Filled.ArrowBack` is not imported or used in `SettingsScreen.kt`, `DocumentScreen.kt`, `EditorScreen.kt`, `ViewerScreen.kt`; the AutoMirrored form is used, consistent with `CameraScreen.kt:44`. `./gradlew :app:compileDebugKotlin` emits no deprecation notice for these.

---

## 4. Non-goals (explicitly out of scope for this round)

The following were decided out of scope before this PRD. They are **not** work items; they are recorded with their reason so nobody re-litigates them mid-round.

1. **Extracting the ~150 user-facing strings into `strings.xml`.** Touches 13 files, zero user-visible payoff.
2. **Status/navigation bar icon appearance following system dark mode instead of the in-app theme setting** (`MainActivity.kt:55`, `themes.xml`). A real defect, but unverifiable without a device.
3. **The folder feature.** The folder chips (`LibraryScreen.kt:225-246`) are unreachable because nothing ever assigns a folder, while `README.md:70` claims "group into folders". This needs a product decision from the founder, so it is an **open question** (§5.2), not a requirement.
4. **Cosmetic radius/padding harmonisation** (14dp vs 16dp corners, e.g. `DocumentScreen.kt:440` vs `LibraryScreen.kt:354`). Imperceptible.
5. **TalkBack support for the crop canvas; dead-code removal.**

Also **not** in scope, noticed while verifying and flagged rather than silently changed: `README.md:71` claims "Multi-select for bulk rename and delete", but rename is only offered when exactly one document is selected (`LibraryScreen.kt:461-463`). That is a documentation/feature mismatch for the founder to rule on, listed in §5.2 rather than added as work.

---

## 5. Hypotheses, open questions, and how this round is judged

### 5.1 Hypotheses to confirm or refute (not findings)

These come from reading the code and are **not** established facts. AC-13 and AC-14 are written so that shipping the fix confirms or refutes them as a side effect.

- **H1 — the flip margin may sit inside the measurement's own noise.** `FLIP_MARGIN = 0.02` (`Orientation.kt:54`) against the code's own note that individual band readings scatter 0.02–0.07 of band height (`:74-76`), with `SPARSE_FLIP_MARGIN = 0.03` (`:62`) for two bands. If true, scatter of the same size as the signal is deciding flips.
- **H2 — an abstaining candidate may decide the flip.** `Bias.NONE` is `Bias(0.0, 0)` (`:174`) and participates in the comparison at `:148`, so a candidate with no evidence contributes a 0.0 reading and a genuine +0.03 on the other side clears the 0.02 margin. If true, pages with no measurable flip evidence can be turned over.
- **H3 — the decision is made and thrown away.** `Plan.flipDecided` is computed and propagated (`AutoCrop.kt:114`) but consumed nowhere in the app; `Models.Page` has no field for it. *This one is verified as a code fact* (the only consumers are the tests); what AC-8 addresses is the user-visible consequence.
- **H4 — rotation may not repaint.** `rememberFileImage` keys on path only (`Components.kt:45-54`) while rotation rewrites the same path (`ScanRepository.kt:298-301`), and `DocumentViewModel.rotatePage` refreshes rows whose `processedPath` is unchanged (`DocumentViewModel.kt:92-101`). If true, the founder's manual workaround is itself broken. AC-9 and AC-10 exist to settle it; AC-9's code-inspection clause makes the fix durable either way.
- **H5 — "worse in batch" may be a UI asymmetry, not a different imaging path.** Batch and single capture share `AutoCrop.plan` but differ in when the user can act: the batch path commits immediately (`ScanSessionViewModel.kt:147-162`), while single capture goes to the editor. If true, AC-1 – AC-8 address the *experience* and AC-13 – AC-17 address the *wrongness*.

### 5.2 Open questions for the founder

1. **Folders (blocks a scoping decision).** `README.md:70` advertises folders; the chips are unreachable because nothing assigns a folder. Which do you want: remove folders from the app and the README, or make folders real (assign, move, rename, delete)? No work is proposed here until this is answered.
2. **Bulk rename.** `README.md:71` says rename works on a multi-selection, but `LibraryScreen.kt:461-463` offers rename only when exactly one document is selected. Should bulk rename exist, or should the README be corrected?
3. **Undo, or confirm-only, for destructive deletes?** This PRD requires a confirmation and a report. A true undo (a snackbar with "Undo") would need deleted rows and files to survive until the snackbar expires — a larger change. Is confirm + report enough for this round?
4. **Should the review also exist for single capture before saving?** The editor already shows the result, so S1 does not require it — confirm we leave that path alone.
5. **Upside-down pages (only a human with a phone can answer).** Which page types go wrong: printed prose, handwriting, tables/forms, receipts, photos-within-the-page, book spreads? Is it deterministic for the same page photographed twice, or does it vary run to run? Does it depend on lighting (side-lit, dim, flash) or on the page filling the frame versus sitting on a dark table? When it goes wrong, is the page turned 180° (upside down) or mirrored? Does it happen in BOOK mode as much as BATCH, and with the automatic shutter on as well as off?
6. **Has manual rotation ever visibly worked for you?** Your sentence implies it did the job, but H4 says the display may not refresh. Did you see the page turn when you tapped rotate, or did you have to leave the screen and come back?
7. **Do you want a one-tap "this page is upside down" flip in the editor as well**, or is the review inside the capture flow enough?
8. **Should the review prompt the user after every capture**, or stay something the user opens deliberately (which is how the request reads: "by pressing the image")? This PRD assumes "deliberately" and never blocks a capture.
9. **Is the version footer allowed to read the build's version automatically**, and is the currently shipped version the one this round's brief calls v1.3.0? I could not verify a release tag from this environment: `versionName` is supplied by CI (`build.gradle.kts:40-43`) with a local fallback of `"1.0.0"` (`:54`).
10. **Should failed gallery imports be retryable** from the report, or is a message enough for this round?

### 5.3 Success measures (checkable by a person)

A person with the phone — and the founder — can check each of these in a few minutes:

- **S1:** Scan 14 pages in batch mode. Open any page from the strip, confirm the page shown matches the page in the finished document, and confirm page 1 is reachable. *(Before: tapping a thumbnail does nothing at all.)*
- **S2:** Photograph a stack of pages. For any page the app leaves upside down, the app either states it could not decide, or the user can flip it in the review with one tap and see it flip. *(Before: no statement, no in-flow fix.)*
- **S2:** On the document screen, tap rotate. The thumbnail visibly turns on the spot, and four taps return the page to its original orientation. *(Before: expected to look like nothing happened — AC-9 settles it.)*
- **S3:** Select two documents and tap delete: asked first, told afterwards, and nothing else disappears. Delete the only page of a one-page document: warned first. Import 5 images where 1 is unreadable: told 4 of 5 imported. Deny camera permanently: the screen's primary action reaches the app's settings. Search, open a document, come back: the filter is either visible or gone.
- **Regression guard:** `./gradlew :app:testDebugUnitTest` passes, and the new orientation fixtures from AC-15 – AC-17 are present and cannot pass by measuring nothing.
- **Founder's own verdict:** the two sentences that opened this round — "batch mode dont let user to see what it took" and "had to do manual rotation to fix those" — are no longer true as written.




