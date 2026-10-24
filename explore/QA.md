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

A long exact note was entered and saved using the capture form. The integration owner subsequently opened the actual software keyboard in iPhone SE Simulator Safari and found the Ask heading clipping under the status bar and an unused bottom strip. See the keyboard follow-up below. Landscape and reduced-motion CSS are present but have not been visually validated in this workstream.

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

## iPhone software-keyboard follow-up

The first actual iPhone SE Simulator keyboard check found that updating only the frame height was insufficient: Safari also pans its visual viewport while the input is focused. `qa/iphone-se-keyboard-before.png` records the clipped Ask heading and blank strip above the keyboard.

The fix pins the mobile frame to both `visualViewport.height` and `visualViewport.offsetTop`, updates on viewport resize and scroll, removes duplicate document scrolling, and lets the modal own vertical scrolling within the visible area. JavaScript syntax and diff checks passed. The integration owner repeated the interaction in iPhone SE Simulator Safari with its software keyboard visible. The full Ask heading, close button and focused long query remain visible; the blank strip is gone, and the memory list scrolls within the sheet. I inspected the resulting `qa/iphone-se-keyboard-after.png` and confirmed the visible layout corrections. This is an actual simulator software-keyboard check, not browser viewport emulation, and does not establish physical-device or native iOS capability.

Before/after evidence: `qa/iphone-se-keyboard-before.png` and `qa/iphone-se-keyboard-after.png`. No further interface changes were made for this evidence update.
