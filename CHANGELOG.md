# Changelog

What each release changed, newest first. This is the short account of it; the full notes for a
release live in [`docs/releases/`](docs/releases/) and are published with the APK on the
[Releases page](https://github.com/[CUSTOM_REDACTED]/kscanner/releases).

Versions before v1.4.0 — v1.0.0 through v1.3.0 — shipped before each release kept notes in the
repository. Their APKs are on the Releases page; no summary of them is given here rather than an
invented one.

## v1.6.3 — 2026-11-16

**New**

- **Edit a page you already have.** The viewer's action row gains **Edit**, which acts on the page
  being shown and opens the editor on its look stage with that page's own filter, strength and
  crop. Saving rewrites the page in place — same position, same file — instead of appending a
  second copy.
- **Apply a page's look to the rest of the document.** Beside **Save page**, with the count named up
  front, progress per page, a way to stop between pages, and a report of what was applied. The page
  being edited is never among the targets.
- **Turn a page over in one action**, in the viewer and in the page sheet, instead of pressing
  rotate twice.
- **A single page shot in Book mode stays one page.** The split now requires a narrow dark run,
  dark on every row of its height, with brighter paper immediately either side; otherwise the shot
  is kept whole. Book mode also draws a dashed centre guide.
- A page now carries its look — preset, strength, brightness, contrast, sharpness, deskew angle —
  so a rotation or a turn-over keeps all of it.

**Fixed**

- The live framing outline no longer jumps when the detector swaps between two descriptions of the
  same page; the shortlist is weighed against the outline on screen instead of the winner taken as
  a move.
- The live framing outline follows the page instead of stepping after it: detection runs ten times a
  second and each move now takes longer than the gap between detections, so the border never comes
  to rest while the page is moving.
- **Detect** keeps a rotation applied by hand, instead of turning the page back.
- Pages in the viewer can be swiped again: the page's pinch-and-pan detector had been consuming
  every drag before the pager could see it.
- The **Batch** chip and the mode chips announce their state to TalkBack instead of relying on
  colour.
- The batch review overlay is dismissed with a cross, not an arrow pointing at a screen that does
  not exist.

## v1.6.2 — 2026-11-16

- Fixed: the bottom controls sat under the system navigation bar, which took their taps — press the
  shutter and Home answered, press **Export PDF** and nothing happened. They are now above it, as
  are the camera's top row. Nothing else moved.
- [Full notes](docs/releases/v1.6.2.md)

## v1.6.1 — 2026-11-16

- Fixed: the framing border introduced in v1.6.0 was drawn against the wrong frame — a fraction of
  the page's size, off to one side, with its right-hand edge outside the screen. The frame the
  border is drawn in is now the frame that was actually searched.
- [Full notes](docs/releases/v1.6.1.md)

## v1.6.0 — 2026-11-16

- New: the camera draws the border it has found around the page while you are still pointing at it,
  and dims everything outside it, so what will be kept is what you see before the shutter.
- The outline is a memory of what has been detected: it glides rather than snaps, ignores shifts
  smaller than five pixels, waits about a third of a second before giving up, and would rather show
  nothing than guess.
- [Full notes](docs/releases/v1.6.0.md)

## v1.5.0 — 2026-11-16

- New: **Re-take** a page in place from the review — the new shot takes the old page's position
  instead of appending at the end.
- Fixed: new captures landed in the middle of a document after a delete, because the next index
  came from how many rows a document held rather than which slots were in use.
- **Batch** is a toggle beside the three capture shapes rather than a fourth shape, so "a book
  spread, captured once" is expressible. Anyone who used the old mode gets Single with the toggle
  on.
- [Full notes](docs/releases/v1.5.0.md)

## v1.4.0 — 2026-11-15

- New: review a run before it is committed — tap a page in the strip to step through the whole run
  and rotate or flip a page without leaving the flow.
- Fixed: the upside-down cue was reading its row profile only down to 30% of its peak, cutting off
  the ascenders and descenders the cue depends on, so many photographed pages were left turned
  over. Pages it still cannot decide are labelled rather than guessed at.
- Also: one-tap page flip, delete confirmations that name what is going, import results reported in
  the library, the app version shown in Settings, and backup switched off so scans never leave the
  device.
- [Full notes](docs/releases/v1.4.0.md)
