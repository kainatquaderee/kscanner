# PRD — Live document framing over the preview

Version: v1.6.0, whose framing-overlay defect is corrected in v1.6.1 (see the design's §4a).
Requested by the owner, in these words: "how exactly does scanning work in camscanner … and make
something similar in kscanner".

## 0. Scope in one paragraph

The viewfinder draws the page's border as the detector finds it, and shades everything outside
that border, so the shot can be lined up before the shutter is pressed instead of being judged
afterwards on the crop screen. The outline is *followed* — it moves for real movement, ignores
tremor, and survives a frame or two of missing evidence — because a border that redrew itself
every frame would shimmer, and one that vanished whenever a line was lost would flicker. It is
always on, with no setting, and it costs the preview one detection every couple of hundred
milliseconds. Nothing about what a shot *becomes* changes: the crop, the orientation pass, the
retake and Batch all behave exactly as in v1.5.0.

## 1. Problem statement

### S1 — The first look at the crop is after the photograph is taken

Today the composition grid is the only help in the viewfinder. The user finds out that the page
was cut off, or that the detector picked the table instead of the sheet, only after the shutter
fires and the crop screen appears. In a long run that is a re-take per bad frame, and the re-take
itself is the subject of the previous release — which is a sign the flow was asking the user to
pay for feedback he should have had before the shot.

### S2 — The competing scanner answers this question live, so its absence reads as missing

CamScanner's viewfinder draws the detected border continuously, dimming everything outside it,
and its crop screen is already close to what was on screen while shooting. A scanner that finds
the page but will not say so until after the fact looks less capable than it is. The owner asked
for the behaviour directly, having seen it there.

### S3 — "Always on" is a decision, not a default

An overlay that can be switched off needs an explanation of when to switch it off, and there is
no such moment here: it never blocks the button, never changes what is captured, and disappears
by itself when no page is in view. Hiding it also costs the user something real — the detection
frames are the same ones the automatic shutter consumes, so the toggle would be a choice between
two views of one measurement rather than a saving.

## 2. What ships

1. A **framing outline** over the live preview: the page border in the accent colour, with the
   area outside it dimmed.
2. **Following** rather than mirroring: corners below a 5 dp movement are held, larger movements
   are eased into place over about 120 ms, and an outline that stops being confirmed is kept for
   400 ms before it is lifted.
3. **Detection on the preview stream** at roughly seven a second, on the same small grayscale
   frame the automatic shutter already measures, using the line detector alone.
4. **The overlay is the only visible change.** No new setting, no new button, no change to the
   camera's controls, and no change to the files a shot produces.

## 3. Acceptance criteria

- **AC-1** With a page in view and the camera open, a border is drawn around it, in the accents
  used elsewhere in the app, with the rest of the preview dimmed.
- **AC-2** The border tracks the page when the camera moves: movement of the page on screen is
  followed within a fraction of a second, without the border flickering between positions.
- **AC-3** Hand tremor and sensor noise do not make the border move: changes smaller than 5 dp at
  the current density leave it alone.
- **AC-4** A frame or two in which the page cannot be found does not make the border disappear;
  about 400 ms of failed detection does.
- **AC-5** An outline the detector is not reasonably confident about is not drawn: showing a
  guessed border is worse than showing none.
- **AC-6** The border lands on the preview where the page actually is, at any of the phone's
  rotations, and whether the preview is letterboxed or cropped to fill the screen.
- **AC-7** The overlay never blocks an interaction: the shutter, the torch, the zoom chips, the
  mode chips and the review strip all work exactly as before, and the overlay is not shown over
  the review or the editor.
- **AC-8** There is no setting for the overlay, and no way it changes what is captured: the
  stored page, its corners and its byte content are identical with and without a border on
  screen.
- **AC-9** The behaviour above is decided by plain JVM code — the frame-to-screen arithmetic and
  the "what should be drawn now" decision are unit tested, with no camera and no sleeping in the
  tests.
- **AC-10** The viewfinder does not get slower: showing the outline does not restart the preview,
  and turning the automatic shutter on or off does not restart it either.

## 4. Deliberately not in this release

- **The out-of-frame warning colour.** CamScanner turns the border amber when part of the page
  leaves the frame. It is a second piece of state to define and test, and the dimmed crop already
  communicates which part of the page is inside the shot.
- **Auto-capture on the framed outline.** The existing gate — sharpness, exposure, stillness —
  stays as it is; the outline is feedback, not a second trigger.
- **Curved-page detection.** CamScanner arbitrates between a straight quad and a fitted curve.
  This app's crop model is a four-corner quad throughout, so a curve would have to be carried
  from the preview through the editor to the warp, and that is a separate feature.
- **A confidence or quality indicator in the viewfinder.** The existing capture advice line
  already speaks when something is wrong.
