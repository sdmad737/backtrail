# Explore verification — 2026-10-24

This is browser layout and interaction evidence on the development Mac. It is not evidence of Android/iPhone native capability, real camera pose, GNSS, background recording or offline phone inference.

## Rendered and inspected

Using the Codex in-app Chromium browser against the localhost static server:

| CSS viewport | Views and conditions inspected |
| --- | --- |
| 390 × 844 | Ready, recording, source memory, retrieval loading, Find in AR, heading slider, retrace |
| 360 × 800 | Dark appearance, larger text, long note/capture, weak GPS, missing terrain, no match, model error |
| 320 × 568 | Compact AR HUD; fixed direction cue overlap with the Map/AR switch |
| 1280 × 900 | Full desktop presentation frame and phone preview |

Fixes from inspection: full-width primary actions, explicit dark-mode button text color, map route fit, moving small-screen heading controls, compact horizontal AR cue on short screens, dynamic heading scale, and preserving route access during model errors. The browser console had no warning/error entries at the final inspection.

`qa/` contains screenshots of the desktop presentation, iPhone-sized recording/AR, small-screen AR, and Android-sized no-match state. These images are viewport checks, not screenshots from physical devices.

A long exact note was entered and saved using the capture form. Real software-keyboard inspection must be recorded separately by the integration owner on iPhone Simulator/Android Emulator or hardware. The preview listens to `visualViewport` resize and scrolls long forms; that implementation is not a claim that a real mobile keyboard was tested here. Landscape and reduced-motion CSS are present but have not been visually validated in this workstream.

## State checks that passed

Run `node explore/model.test.mjs`:

- Selecting another memory and changing Map/AR view preserves the retrace cursor.
- Retracing follows the ordered route to the start.
- Weak location prevents replay advancement and arrival alerts.
- Arrival notifications emit once after repeated acceptable fixes; later fixes do not repeat them.
- Heading wraparound handles 359° → 1°.
- Stale query results and IDs outside the saved catalog cannot replace valid state.
- Natural-language fixture retrieval returns the source record; unrelated query returns no match.
- Saving with unknown location does not permit Find guidance.

`node --check explore/app.js` and `git diff --check` also passed. No external runtime package is required.

## Additional integration-owner inspection

The integration owner also opened this same server in iPhone SE Simulator Safari and inspected Ready and AR. Reported screenshots are in the root workspace at `local-data/qa/iphone-se-explore.png` and `local-data/qa/iphone-se-ar.png`. This is simulator browser evidence, not a native iOS implementation or physical camera test.

## Device work still required

Native camera capture and orientation, portrait/landscape north reference, real movement, original-image local Gemma interpretation, real offline retrieval, screen-locked location, OS notifications, persistent app relaunch and a server-free cold phone launch belong to the native integration checklist. This browser intentionally requests none of those permissions.
