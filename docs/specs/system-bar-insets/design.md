# Design — System bar insets for the app's own controls

Accompanies the fix shipped in v1.6.2. The PRD's acceptance criteria are cited as AC-n.

## 0. Method, and what counts as evidence here

Two constraints shaped the work:

1. **This defect is layout, not logic.** No JVM test can see an inset, Robolectric is not in the
   offline Gradle cache, and there is no `androidTest` source set. The evidence is therefore
   measured on the owner's phone — `dumpsys window displays` for where the system's bars actually
   are, `uiautomator dump` for where our controls actually landed — and the unit suite is used only
   to show that nothing else broke (AC-9).
2. **A device was attached for this round**, unlike the previous two. So, unusually for these specs,
   the DEVICE rows below are *verified*, on one phone, in one configuration (portrait, three-button
   navigation, API 36). What was not exercised is listed in §5.

The system bar geometry was read from the running device rather than assumed: the status bar at
`[0,0][1080,100]`, the navigation bar at `[0,2265][1080,2400]` (135 px), density 450, screen
1080x2400.

## 1. Findings

**F1 — The app has drawn edge to edge since its first commit.** `MainActivity.kt:55` calls
`enableEdgeToEdge()`, and `compileSdk`/`targetSdk` are 35, both from `a9cf664`. `themes.xml` sets
`statusBarColor` and `navigationBarColor` to transparent, in both the light and the night theme. So
the window really does extend under both bars, and any composable that lays its children out against
the window's edges is laying them under a system bar. **This fault is original, not a regression**,
which is why it surfaced on the owner's new phone with three-button navigation rather than at a
release boundary.

**F2 — Three screens never asked for insets; the others got them from `Scaffold` for free.**
`LibraryScreen`, `SettingsScreen` and `ViewerScreen` pass their content through `Scaffold`, whose
`contentWindowInsets` defaults to the system bars and whose `TopAppBar` applies `statusBars` through
`TopAppBarDefaults.windowInsets` (both parameters confirmed present in the material3 1.3.1 AAR). Their
content, FAB and dialogs therefore sit inside the bars. `DocumentScreen` puts its own bar in
`Scaffold`'s `bottomBar` slot, `EditorScreen` puts two bars in its content, and `CameraScreen` has no
`Scaffold` at all — and none of the three applied an inset to those bars, so all three overlapped the
system's. That is the difference that made the fault look like "a few screens misbehaving".

**F3 — `navigationBarsPadding()` and `statusBarsPadding()` were already the right tool.** They read
the live insets, so they follow the mode the user has chosen: a 135 px bar with three buttons, a much
thinner gesture area, and a side bar in landscape. They also compose with `Scaffold`'s own padding
without double-counting, because insets are consumed as they are applied. No new abstraction was
needed, and none was introduced.

**F4 — The camera dodged the status bar with a guess, and only for the mode row.** The mode row was
pushed down with `padding(top = 56.dp)` as a stand-in for the status bar, while the icon row sat
`12.dp` from the window's top. Neither is derived from the bar: 56 dp is 158 px at 450 dpi, the status
bar here is 100 px, and the icons were measured at y 68–136 — inside it. Replacing the number with the
real inset is what AC-4 needed; keeping the number and adding padding would have moved the chips
twice.

**F5 — Padding must go *inside* the surface, not around it.** Applied outside, the bar's own
`Surface` would stop above the navigation bar and leave the window's background showing through the
strip behind the system buttons — a colour band instead of a control overlap. Applied inside, the
surface still runs to the window's edge and only its children move up (AC-6). This is the whole
reason the three edits are one line each rather than a wrapper composable.

**F6 — The screens that were already correct had to be left alone.** Any scheme applied at the
activity level — `setDecorFitsSystemWindows`, or padding the whole `NavHost` — would have shifted the
library, settings and viewer too, and would have fought `Scaffold`. The fix is deliberately
per-composable.

## 2. The fix, as built

| file | place | modifier | why |
| --- | --- | --- | --- |
| `ui/document/DocumentScreen.kt:232` | bottom bar's `Row`, inside its `Surface` | `navigationBarsPadding()` | **Add page** / **Export PDF** were at y 2276–2333, inside the bar |
| `ui/editor/EditorScreen.kt:468` | crop bar's `Column`, inside its `Surface` | `navigationBarsPadding()` | rotate/detect/reset row and **Next** |
| `ui/editor/EditorScreen.kt:535` | filter bar's `Column`, inside its `Surface` | `navigationBarsPadding()` | straighten row and undo, which ran to y 2338 |
| `ui/camera/CameraScreen.kt:440` | bottom controls `Column` | `navigationBarsPadding()` | the shutter ended at y 2321, 56 px inside the bar |
| `ui/camera/CameraScreen.kt:367` | new top `Column` wrapping the icon `Row` and the mode `FlowRow` | `statusBarsPadding()`, with the old `padding(top = 56.dp)` removed | icons were at y 68–136, inside the status bar |

Three imports were added. The camera's top row and mode row were wrapped in one `Column` so the
status-bar inset could be applied once, replacing the hard-coded `56.dp`; the preview itself stays
full bleed behind both bars, which is the point of edge to edge — only the strips that carry controls
are inset.

## 3. Measurements, before and after

Same phone, same session, `uiautomator dump` leaf bounds (the icon or the label, not the full button
box), nav bar top edge **2265**, status bar bottom edge **100**:

| screen | before | after | clear by |
| --- | --- | --- | --- |
| document: Add page / Export PDF | 2276–2333 | 2141–2198 | 67 px |
| editor: straighten row | to 2338 | 2146–2203 | 62 px |
| editor: rotate / detect / reset, Next | — | 2000–2068, 2005–2062 | 197 px |
| camera: capture icon | to 2256 (button to 2321) | 2037–2121 | 144 px |
| camera: close / grid / timer / flash | 68–136 | 168–236 | 68 px below the bar |
| camera: Single / Book / ID Card / Batch | 203–248 | 348–393 | — |

The camera's chips moved furthest because they were positioned with the `56.dp` guess (F4) and now
follow the icon row. That is viewfinder area given up to the controls; PRD §5.1 records it as the one
taste question this fix raises.

## 4. AC traceability

| AC | where it is satisfied | evidence |
| --- | --- | --- |
| AC-1 | `DocumentScreen.kt:232` | measured: 2141–2198, screenshots `doc-after` |
| AC-2 | `EditorScreen.kt:468`, `:535` | measured: 2000–2068 / 2005–2062 and 2146–2203, `editor-after` |
| AC-3 | `CameraScreen.kt:440` | measured: 2037–2121; **Capture** pressed and a page was taken |
| AC-4 | `CameraScreen.kt:367` | measured: 168–236; `camera-after` |
| AC-5 | `MainActivity.kt:55` unchanged | `grep` for `WindowInsetsController` / `hide(navigationBars` / `BEHAVIOR_` in `app/src`: no matches |
| AC-6 | padding inside each `Surface` | screenshot shows surface colour to the window's bottom edge |
| AC-7 | no edit to those three screens | `git diff --stat -- app/src`: 3 files, all three named in §2 |
| AC-8 | the same | diff adds 3 imports, 4 `navigationBarsPadding()` uses and 1 `statusBarsPadding()`, and wraps the camera's top row and mode row in one `Column` |
| AC-9 | — | `:app:testDebugUnitTest`: 208 tests, 22 classes, 0 failures |
| AC-10 | the modifiers themselves | inspection only; see §5 |

## 5. UNVERIFIED on this machine

| Not verified | Why | Who must confirm |
| --- | --- | --- |
| Gesture navigation's thinner inset | the phone is set to three-button navigation | owner, if he switches |
| Landscape, and the navigation bar on the side | rotation not exercised | owner |
| Display cutout in landscape | the device reports the cutout only on the top edge in portrait | owner |
| Any device other than the owner's | one phone, one API level, one density | owner |

**Not claimed:** that other phones' bar heights are what this phone reports (they are read from the
live insets, so they need not be); that a green build means any DEVICE row passes.

## 6. Risks

- **A screen that adds a custom bar later will inherit the same fault.** Nothing enforces the
  inset; it is a convention in three files. Recorded rather than solved, because a linter or wrapper
  would touch more code than the defect justifies today (PRD §4.3).
- **Insets are consumed in composition order.** If one of these bars were nested inside another
  inset-applying parent later, the padding would not double, but the outer parent would stop
  consuming it — which is correct here and would need re-reading if the layout changes.
- **The camera's icons still sit over a live preview**, as they did before; only their strip moved
  below the status bar. Contrast against a bright page was already scene-dependent.
- **The status/navigation bar icon colours still follow the system's dark mode**, not the in-app
  theme setting. On a light preview under a dark status bar the clock can be hard to read. It is a
  pre-existing defect and is not touched here.
