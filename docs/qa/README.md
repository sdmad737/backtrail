# Native Android evidence

These are API 36 ARM64 Pixel 7 **emulator** screenshots, not physical-device proof.

- `android-final-map.png`: final APK, bundled USGS raster map and explicitly replayed sample route.
- `android-large-dark.png`: 130% Android font scale and dark appearance.
- `android-keyboard-scrolled.png`: actual emulator software keyboard; capture sheet scrolls to its save control. Preview is the emulator virtual camera.
- `android-missing-map.png`: deliberate Explore missing-map condition, recorded trace retained.
- `android-weak-gps.png`: deliberate Explore weak-GPS condition; AR direction and confirmation are suppressed. The final build subsequently simplified the unavailable-distance wording shown in this screenshot.

The raw capture and relaunch records stay in ignored `local-data/qa/`, including the saved virtual-camera JPEG metadata. The final APK SHA-256 and build results are in `../build-receipt.json`. Model inference was tested on the Mac, not in this emulator. Physical camera/GPS/heading, screen-locked recording and notification delivery require the Pixel acceptance run in `../SETUP.md`.
