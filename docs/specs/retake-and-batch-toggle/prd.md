# PRD — Re-take in the review, append order, and Batch as a toggle

Version: v1.5.0. Owner-reported defects, plus one structural change he asked for alongside them.

## 0. Scope in one paragraph

Three things ship together: a **Re-take** button in the quick view that re-shoots a page with the
camera and puts the new shot in that page's place; a fix for **new captures landing in the middle**
of a document; and **Batch** becoming a toggle rather than a scan mode. The third is what makes
"one book spread, then stop" expressible, which the four-mode model could not say. The owner
grouped them deliberately: they all sit in the same capture flow, and the first two share a
consequence — both write a page at a position that is not simply "the end".

## 1. Problem statement

### S1 — A bad page cannot be fixed without moving it back

In a run, fixing a page means deleting it and shooting another. The new shot appends at the end of
the document, so on anything longer than a couple of pages the user is left to drag it back into
place — and on a run of twenty, the drag itself becomes the task. The review (shipped in v1.4.0)
already knows which page is on screen and already offers rotate and flip against it; re-taking is
the same kind of operation with the camera as its source.

### S2 — New captures land in the middle of the document

Reported by the owner against v1.4.0. The index for a new page was taken from `countPages` — the
number of rows — which is not the same question as which slots are in use. A document holding
indices 0, 1 and 2 that loses the page at 1 still has 2 in use, but a count reports two rows, so
the next capture was written to index 2: ahead of the page already sitting there. Any delete
leaves the document in exactly that state.

This was diagnosed during this round rather than reproduced from a report of the symptom, and it
is the reason the fix is in the data layer rather than in the camera: the camera was asking a
reasonable question and getting a plausible-looking wrong answer.

### S3 — Batch is a mode, so "one spread then stop" cannot be said

Up to v1.4.0 there were four modes: Single, Batch, Book, ID card. Batch meant "single pages,
continuously", and Book and ID card were always continuous. That makes the dimensions inseparable
in the UI: a user who wants to photograph one spread and go straight to the document has no way to
say so — choosing Book commits them to spread after spread until they press Done.

Whether the camera keeps going is not a property of what one shot becomes. The owner asked for the
toggle; the work is to split the two questions and to keep every existing user's behaviour.

## 2. User stories (prioritised)

### P0

1. As someone reviewing a run, I can re-take the page I am looking at, and the new shot takes that
   page's place instead of appearing at the end.
2. As someone whose capture went wrong, I am not left worse off if the re-take fails: the page I
   was trying to fix is still there.
3. As someone who deleted a page, the next capture I take goes to the end of the document.
4. As someone who wants both shapes and continued capture, I can choose a shape and separately
   say whether the camera keeps going.
5. As an existing user, the setting I had before this update still does what it did.

### P1

6. As someone who armed a re-take and changed their mind, I can cancel it, and nothing was written
   while it was armed.
7. As someone who armed a re-take and then changed the shape or the toggle, the armed re-take does
   not fire against the new shape.

## 3. Acceptance criteria

Each criterion is independently checkable. **[DEVICE-ONLY]** means it needs the rendered screen and
a physical camera: it cannot be verified in this environment and must be checked by a human.

### S1 — re-taking a page

**AC-1** — The review offers Re-take only where one shot means exactly one page and the run is
continuous: Single with Batch on. It is not offered for a book spread (one shot, two pages) or a
card (two shots, one page), nor for a run that stops after one unit.
*Check:* **INSPECT** `ScanSessionViewModel.canRetake` — `mode.isOnePagePerShot && batch && documentId != null`, and `isOnePagePerShot` is true for `SINGLE` alone (pinned by `ScanModeTest`). **[DEVICE-ONLY]** that the button appears in Single + Batch and not in Book, not in ID card, not with Batch off.

**AC-2** — Tapping Re-take closes the review, arms exactly one shot, and states on screen which
page the next shot will replace, with a way to cancel.
*Check:* **INSPECT** `retakeReviewPage` sets `retakeTarget`/`retakePosition` and clears `review`;
`CameraScreen` renders "Re-take page N" with a cancel action while `retakePosition` is non-null. **[DEVICE-ONLY]** the banner.

**AC-3** — The replacement occupies the slot of the page it replaces: its index is the old page's
index, and the order of the document is unchanged.
*Check:* **INSPECT** `ScanRepository.retakePage` passes `previous.index` into `DocumentStore.replacePage`. **[DEVICE-ONLY]** shoot a run of four, re-take page 2, and compare the document's order before and after.

**AC-4** — A capture taken after a re-take appends at the end, not beside the replaced page.
*Check:* **INSPECT** the append path derives its index from `nextPageIndex` (highest index in use, plus one), which does not move when a page is replaced in place. **[DEVICE-ONLY]** re-take page 2 of a three-page run, shoot another page, confirm it lands fourth.

**AC-5** — Nothing is written until the shot arrives. Leaving the camera, or cancelling, leaves the
page and its files exactly as they were.
*Check:* **INSPECT** — arming only sets two fields; the write happens in `consume`. **[DEVICE-ONLY]** arm a re-take, cancel, and confirm the page is unchanged.

**AC-6** — The armed re-take is cleared after one shot, and by any change to the shape or to the
toggle.
*Check:* **INSPECT** `consume` claims and clears `retakeTarget` before launching; `updateMode` and
`updateBatch` call `cancelRetake`; `beginInto` clears both fields directly.

**AC-7** — A failed re-take leaves the original page, and its files, intact.
*Check:* **INSPECT** the row swap is one transaction (`DocumentStore.replacePage`), the insert's
result is checked before the delete, and the old files are deleted only after the swap returns.
This is **not unit-testable here** (see F5).

### S2 — append order

**AC-8** — A capture taken after a page has been deleted is written after the last page in use, and
no two pages hold the same index.
*Check:* **INSPECT** `appendPages` uses `store.nextPageIndex(id)` and not `store.countPages(id)`. **[DEVICE-ONLY]** capture three pages, delete the second, capture a fourth; the fourth is last and nothing is overwritten. Not unit-testable here (F5).

### S3 — shape and run

**AC-9** — There are exactly three shapes, and Batch is a separate persisted boolean.
*Check:* **JVM** `ScanModeTest` asserts `ScanMode.entries` and that only `SINGLE` is one page per shot; **INSPECT** `AppSettings.batch` + `KEY_BATCH`.

**AC-10** — A stored setting left by an older version keeps its behaviour: `BATCH` becomes Single
with Batch on; `BOOK` and `ID_CARD` keep Batch on; `SINGLE`, an absent value, or an unreadable one
becomes Single with Batch off.
*Check:* **JVM** `ScanModeTest` covers each of the five cases, including the empty string and an
unknown name.

**AC-11** — Once the user has chosen the toggle explicitly, that choice outweighs whatever the old
mode implied.
*Check:* **INSPECT** `SettingsStore` reads `getBoolean(KEY_BATCH, storedMode.second)`.

**AC-12** — The page strip and the Done button appear only for runs that keep going; a single shot
to the editor carries neither.
*Check:* **INSPECT** both are gated on `capturesToEditor` (`mode == SINGLE && !batch`). **[DEVICE-ONLY]** that Single with Batch off shows no strip and no Done.

**AC-13** — Settings offers the toggle beside the start mode, and the camera opens with it.
*Check:* **INSPECT** the `SwitchRow` in `SettingsScreen` and `MainActivity` passing
`initialBatch = settings.batch`.

**AC-14** — Turning Batch off ends the run only when the run actually committed pages: the front of
an ID card does not end it, and a re-take never ends it.
*Check:* **INSPECT** `session.consume(path) { if (!session.batch) session.documentId?.let(onFinished) }` — the callback fires only when the commit added pages, and a replacement reports zero added.

## 4. Non-goals (explicitly out of scope for this round)

1. **180°, vertical flip and mirror buttons in the review.** Requested, then retracted by the owner
   after testing the existing flip button: *"keep the flip button as it is … no need for 180\* again
   as it is already good enough"*, *"no need for mirror too"*. `flipPage` stays `rotateBy(pageId,
   180)` and `Transforms.flipHorizontal` is untouched. Recorded here so it is not re-litigated.
2. **Re-take for Book and ID card.** "Replace this page with one shot" has no single answer for a
   spread or a card. The delete-and-re-shoot route still works there.
3. **Extracting the ~150 user-facing strings into `strings.xml`.** Zero user-visible payoff.
4. **Status/navigation bar icon appearance following the system dark mode** instead of the in-app
   theme setting. A real defect, unverifiable without a device.
5. **Cosmetic radius/padding harmonisation; TalkBack support for the crop canvas; dead-code
   removal.**

## 5. Open questions for the owner

1. **The Batch toggle's accessible state.** The chip changes colour and icon to show on/off, but
   carries no `stateDescription`, so TalkBack reads "Batch" without saying whether it is on. Worth
   fixing, not fixed in this round because it cannot be verified without the device.
2. **A re-take re-runs OCR** on the replaced page when automatic OCR is on. It is the honest thing
   to do (the pixels changed), but on a slow page it is also a pause. The alternative — keeping the
   text that described the old image — would be wrong.

## 6. Success measures (checkable by a person)

- On a run of four pages, re-taking page two leaves the document in the same order, and a fifth
  capture lands fifth.
- Deleting a page from a run and capturing again puts the new page last.
- Upgrading with the old Batch mode set opens the camera in Single with Batch on, and the hint
  under the shutter says "Page after page".
- Choosing Book with Batch off takes one spread and lands in the document screen.