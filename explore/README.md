# Travis Explore

An interactive, entirely local browser showcase of the Travis product direction. It is separate from the native Android application and does not claim native capabilities.

## Run

From the repository root:

```sh
python3 -m http.server 4173 --directory explore --bind 127.0.0.1
```

Open http://127.0.0.1:4173. There are no package-install steps, remote fonts, API keys, analytics, fetches or external assets at runtime. A network connection is unnecessary once these files are present. The browser preview needs this local static server; the independently packaged native Android application does not.

Use a phone-sized browser viewport for the app, or a wide desktop viewport for its presentation frame. URL examples: `?scene=recording`, `?scene=ar`, `?scene=ar&dark&large`.

## A 90-second walkthrough

1. Start outing. Tap **Replay a step** several times; the route grows from accepted sample points.
2. **Save a place**. Add an exact note and optionally request a return reminder. The photograph is explicitly an NPS example, not a new capture.
3. For the full story, open Controls and **Load sample walk**. Ask **Where did I leave my flask?**
4. Open the source record, choose **Find in AR**, and drag the replay heading. The approximate cue moves relative to the saved target. The photo remains labelled **STILL CAMERA PREVIEW · NOT LIVE**.
5. **Resume retrace** preserves its recorded cursor. Step through the ordered route. A sample reminder appears after two good fixes near the bridge. Open the bell to inspect it.
6. Reach the sample trailhead and review the outing's photos.

## Live, simulated, and pending

- **Real in this browser:** interaction state, geometry calculations, source-photo rendering, exact typed notes, candidate-ID validation, mode synchronization, preserved retrace cursor, view switching, focus trapping, error and uncertainty states.
- **Replay:** route coordinates, position, fix accuracy, heading, elapsed walk time, camera image and reminder events. The route and terrain are illustrative and do not describe a navigable real place. The NPS photographs depict different locations.
- **Sample answers:** `SampleMemoryAdapter` uses deterministic fixture matching and a 650 ms simulated wait. It does not invoke Gemma. The native app's local Gemma results must be verified separately.
- **No browser permission requests:** no live GPS, camera, notifications or microphone. No world anchoring, automatic place recognition, safety judgment or spatial localization.
- **In-memory only:** Reload/reset clears Explore session changes. Native persistence and sensor adapters live in the Android implementation.
- **Offline maps:** the SVG terrain is an authored illustration, not a downloaded geographic map pack. Missing terrain shows the recorded route alone. Native offline map readiness is tracked separately.

## Integration seams

`model.js` contains `ReplayAdapter`, `SampleMemoryAdapter`, outing/landmark/retrace/reminder data and pure geometry. `app.js` renders the shared state; Map, AR and memory results use identical IDs. A Find target never changes `retraceIndex`. Unknown-quality saved locations cannot be used for Find. Weak or stale measurements suppress direction and arrival events. Only user replay steps advance position; there are no fake live timers.

The UI-only heading slider is a replay adapter input, not camera pose. A physical device must establish camera-view orientation, screen rotation, north reference and uncertainty before similar cues can be called live. Route progress, capture and model operations must remain independent in the native app.

## Experience checks

Controls expose weak GPS, stale fixes, no heading, missing terrain, camera denial, model error, dark appearance and larger text. Try “red tent” for a no-match answer. Notes use a scrollable sheet, 16 px form text and safe-area insets. The layout includes small portrait and landscape rules and reduced-motion support.

Run deterministic state checks with a current Node installation:

```sh
node explore/model.test.mjs
```

See `QA.md` for actual render checks. All photos and licensing information are in `assets/manifest.json`.
