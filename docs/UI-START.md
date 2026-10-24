# Start UI work on Travis

The working foundation is the Kotlin/Compose Android app in `app/`. The separate
`explore/` browser demo lets you iterate on product presentation immediately,
without Android tools, a phone or model weights. UI work does not require a Gemma
download. Your GitHub account must have access to this private repository.

## Clone on your own laptop

```sh
git clone https://github.com/sdmad737/backtrail.git travis
cd travis
git switch -c codex/ui
git merge-base --is-ancestor a58a501 HEAD
```

The last command must exit successfully: it checks that you have the prepared
app, rather than the old handoff. Open this folder in Codex and paste:

> Read `prompts/01-ui.txt` completely and execute it as Travis's UI/product owner.
> Start with `docs/UI-START.md`, inspect the existing app and preserve its contracts.
> Run the current interface, choose one coherent improvement to Map → Save → Ask →
> Find in AR → Resume retrace, implement it in the owned UI files, and inspect the
> rendered result. Keep sample behavior labelled and report the commit and checks.

For an existing clone, preserve uncommitted work, fetch `origin`, and create your
UI branch from `origin/main` in a separate checkout. Keep an existing `codex/ui`
branch if it already contains your work; integrate the baseline without resetting it.

## See the product immediately

From the repository root:

```sh
python3 -m http.server 4173 --directory explore --bind 127.0.0.1
```

Open [localhost:4173](http://127.0.0.1:4173), choose **Load sample walk**, then try
Save → Ask → Find in AR → Resume retrace. Controls expose larger text, dark
appearance, weak GPS, missing maps and model failure. This is a browser showcase
with replayed movement, still-camera previews and sample answers. It is not a
native iPhone build or a live model/sensor test. See [Explore](../explore/README.md).

## Build and inspect the native app

Install/configure JDK 17 and Android SDK Platform 36, Build-Tools 35.0.0 and
Platform-Tools on your machine. Android Studio's SDK Manager can install the SDK
components. Set `JAVA_HOME` to your JDK 17 and `ANDROID_HOME` to your SDK, or let
Android Studio write the ignored `local.properties`. Open the repository root as
the Android project. The lead's `.tooling/env.sh` is local and is not in Git.

The first build needs internet to populate your own Gradle cache:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The APK supports ARM64 Android devices/emulators. Use your own ARM64 emulator for
UI work, select **Explore demo** in the app, and keep its sample labels visible.
The lead coordinates all installations on the shared physical Pixel. After the
dependencies are cached, the same Gradle command accepts `--offline`.

## Where to edit

| Surface | Owned files |
| --- | --- |
| Screen structure, sheets, controls and assistant | `app/src/main/java/com/backtrail/app/ui/TravisScreen.kt` |
| Colors, typography and visual components | `app/src/main/java/com/backtrail/app/ui/TravisVisuals.kt` |
| Geographic map presentation | `app/src/main/java/com/backtrail/app/ui/OfflineTrailMap.kt` |
| Browser product showcase | `explore/` |

Use `TravisUiState` and `TravisAction` as the existing interface. Shared contracts,
the ViewModel, entry point, dependencies, GPS/sensors and model execution remain
with their assigned owners. Propose precise shared changes to the lead. Full
ownership and acceptance checks are in [the UI role prompt](../prompts/01-ui.txt).

Before handoff, run the Gradle checks for native changes, `node explore/model.test.mjs`
for changed Explore behavior, and `git diff --check`. Inspect affected rendered
screens with keyboard and larger text. Commit on `codex/ui`, push that branch and
send its commit plus screenshots/check results to the lead for integration.
