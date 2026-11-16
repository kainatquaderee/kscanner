# PRD — The system bars were covering the app's own controls

Version: v1.6.2. Owner-reported defect, with the owner's decision on what the fix should be.

## 0. Scope in one paragraph

The app draws edge to edge, so the window's bottom edge is under the navigation bar and its top edge
is under the status bar. Three screens laid their own controls out against the window's edges rather
than against the space the system bars leave, so the system bar covered them and took their taps:
the document screen's action bar, both bars in the editor, and the camera's shutter and top row. This
round gives those three screens the insets they were missing. It does **not** hide the navigation
bar: the owner was offered that and chose to keep it visible, because the bar returns on a swipe
from the bottom edge and the padding is needed either way.

## 1. Problem statement

### S1 — Bottom controls sit under the navigation bar, and lose their taps

Reported by the owner against v1.6.1: *"android navigation bar gets overlap with many bottom buttons
of our app"*. Measured on his phone (OnePlus CPH2465, 1080x2400, density 450, three-button
navigation, API 36), where the system's navigation bar occupies `[0,2265][1080,2400]`:

| screen | control | drawn at | relative to the bar (starts 2265) |
| --- | --- | --- | --- |
| document | **Add page**, **Export PDF** | y 2276–2333 | entirely inside it |
| editor | straighten row | to y 2338 | bottom 73 px inside it |
| camera | shutter | to y 2321 | bottom 56 px inside it |

The bar is not merely painted over the buttons: it is the system's, drawn on top, and it receives
the touches. Pressing the shutter's lower half presses Home.

### S2 — The camera's top row sits under the status bar

The same omission at the other edge. The camera dodges the status bar with a hard-coded `56.dp` on its
mode row and leaves the icon row `12.dp` from the window's top — neither derived from the bar, which
here is 100 px — so the close, grid, auto-shutter and flash icons were drawn at y 68–136, the top third
of each inside the status bar's strip.

### S3 — Which behaviour: pad, or hide the bar?

The owner asked whether the navigation bar could simply be kept hidden. It can (`WindowInsetsController
.hide(navigationBars())`, revealed transiently by a swipe), but it is not what this round does, and
the reasoning is recorded in §4.1 so it is not re-litigated.

## 2. User stories (prioritised)

### P0

1. As someone using the app with three-button navigation, I can see and press every bottom control
   without the system's bar covering it or stealing the tap.
2. As someone using the camera, the top row is not under the clock, the battery icon or the cutout.
3. As someone using the app with gesture navigation, or in landscape, the same controls are clear of
   the system's gesture area and side bar.

### P1

4. As an existing user, nothing else about the interface moves: the same controls on the same
   screens, the same colours behind the system bar, the same behaviour when a keyboard opens.

## 3. Acceptance criteria

Each criterion is independently checkable. **[DEVICE-ONLY]** means it needs a rendered screen: it
cannot be verified by a JVM test in this environment.

**AC-1** — The document screen's bottom bar clears the navigation bar.
*Check:* **DEVICE-ONLY** on the owner's phone (nav bar top edge 2265, measured from `dumpsys window
displays`): the **Add page** and **Export PDF** labels sit at y 2141–2198, 67 px clear. Before the
change they were at 2276–2333.

**AC-2** — Both editor bars clear it, in both stages.
*Check:* **DEVICE-ONLY** the crop stage's rotate/detect/reset icons sit at y 2000–2068 with **Next** at
2005–2062, and the straighten row at 2146–2203. Before, the straighten row ran to 2338.

**AC-3** — The camera's shutter clears it.
*Check:* **DEVICE-ONLY** the capture icon sits at y 2037–2121; before, the shutter ended at 2321.
Pressing **Capture** in the middle of the button takes a shot (verified by taking one).

**AC-4** — The camera's top row clears the status bar.
*Check:* **DEVICE-ONLY** the four icons sit at y 168–236 (status bar 0–100); the mode row and Batch
start at y 348. Before, the icons were at 68–136.

**AC-5** — The navigation bar stays visible and the app stays edge to edge.
*Check:* **INSPECT** `MainActivity.kt:55` still calls `enableEdgeToEdge()`; no `WindowInsetsController`,
`hide(navigationBars())` or `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` appears anywhere in `app/src`.

**AC-6** — The bars still paint their surface to the bottom of the window, so the strip behind the
system buttons is not a colour gap.
*Check:* **INSPECT** the padding is applied *inside* each `Surface` — `DocumentScreen.kt:232` on the
`Row` within the bar's `Surface`, `EditorScreen.kt:468` and `:535` on the `Column` within each
`Surface`. **[DEVICE-ONLY]** that the strip below the buttons is the surface colour in the
screenshots.

**AC-7** — The screens that already worked are untouched.
*Check:* **INSPECT** `LibraryScreen`, `SettingsScreen` and `ViewerScreen` use `Scaffold` and take its
`padding`; no edit in this round. **DEVICE-ONLY** their controls are where they were.

**AC-8** — No other behaviour changes: no control added, removed, renamed or reordered, and no
setting appears or disappears.
*Check:* **INSPECT** the code diff — it adds three imports, four `navigationBarsPadding()` and one
`statusBarsPadding()`, and wraps the camera's top row and mode row in one `Column` so the status-bar
inset applies to both.

**AC-9** — The unit suite is unaffected.
*Check:* `./gradlew :app:testDebugUnitTest` — 208 tests in 22 classes, 0 failures.

**AC-10** — Gesture navigation, landscape's side bar and the keyboard are handled by the same insets
rather than by a second mechanism.
*Check:* **INSPECT** the modifiers are `navigationBarsPadding()` / `statusBarsPadding()`, which read
the live insets and apply to whichever sides the bars occupy. **[DEVICE-ONLY]** not exercised: the
phone is set to three-button navigation, and rotation and the keyboard were not tested.

## 4. Non-goals (explicitly out of scope for this round)

1. **Hiding the navigation bar**, in the camera or anywhere else (owner's decision, below).
2. **Leaving edge to edge** to get the padding for free. Target SDK 35 requires it, and the app has
   drawn edge to edge since its first commit (`a9cf664`).
3. **A shared inset wrapper for every screen.** The screens are not shaped alike — five route their
   content through `Scaffold` and the camera does not, and the three that were wrong are wrong in
   three different places — so one wrapper would not fit them, and the three edits that do fit are one
   modifier each. Revisit if a fourth custom bar appears.
4. **The status/navigation bar icon appearance following the in-app theme** rather than the system's
   dark mode. A real defect, previously recorded, still unfixed.
5. **Extracting the ~150 user-facing strings into `strings.xml`; radii and padding harmonisation;
   dead-code removal.**

### 4.1 The owner's decision on the navigation bar

Offered three behaviours, the owner chose **"Keep it — padding is the fix"**. The bar is the system's
Back/Home/Recents affordance; hiding it removes it until a swipe, contradicts platform guidance, and
does not remove the need for padding — the bar returns on a swipe from the bottom edge and would then
cover the buttons. So the padding is the fix in every variant, and the variant that keeps the
system's affordance is the one shipped.

## 5. Open questions for the owner

1. **The camera's chips moved down 145 px.** Fixing the top row against the real status bar and
   dropping the hard-coded 56 dp offset pushes the mode row from y 203–248 to 348–393, which is
   viewfinder the preview no longer shows under controls. If that reads as too much, the alternative
   is to let the mode row float over the preview (it already has a scrim) rather than sit in a column
   below the icons.

## 6. Success measures (checkable by a person)

- On the owner's phone, with three-button navigation, every bottom button on the document, editor and
  camera screens is visibly clear of the bar, and pressing it does what it says.
- The camera's close icon is below the clock and the cutout rather than behind them.
- The strip behind the navigation bar is the screen's surface colour, not a band of another colour.
- The library, settings and viewer look exactly as they did in v1.6.1.
