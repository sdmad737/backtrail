# Backtrail project instructions

This is a template to place in the actual Backtrail repository root. Preserve
existing repository instructions when adopting it.

## Product and evidence

- Read `docs/PRODUCT.txt` and `docs/CONTRACTS.txt` before implementation.
- Build one Android app. Reuse the chosen working starter; do not generate a
  competing project, architecture or dependency stack in your workstream.
- The runtime works offline on the demonstrated phone. No remote inference,
  telemetry, downloaded-at-use assets or silent network fallback in the demo.
- Model proposals do not directly mutate route progress. Validate their shape,
  allowed IDs and session/request/revision context. User controls confirm actions.
- Show unknown/failed states. Never turn cached/fake output into a claimed live
  model result. Preview fixtures must be clearly labelled and absent from the
  normal demo execution path.
- Compilation, emulator operation and real-device measurements are different
  evidence. State exactly what was run. Never invent timing or accuracy results.

## Ownership

The bootstrap owner maps these logical areas to real package paths and records
them in `docs/DECISIONS.txt` before parallel implementation.

- UI owner: `ui/`, including map rendering, camera interaction, comparison and
  assistant presentation. Use existing device/inference interfaces.
- Inference owner: `ai/`, model import/lifecycle, prompts, parsing, request
  serialization/cancellation and local model measurements.
- Device/integration owner: `trip/`, location service, sensors, local files,
  deterministic route/reminder state, composition root and integration.
- The integration owner alone edits shared contracts, dependency catalogs,
  root Gradle configuration, manifest, application entry points and shared
  product/decision documents unless an explicit task reassigns a file.
- These boundaries are coordination rules, not filesystem security. If work
  needs a shared change, report the precise addition to the integration owner
  and continue independent owned work. Do not silently fork an interface.
- Read-only parallel research/review is useful. Do not launch extra code-writing
  agents into another person's checkout or owned files.

## Delivery

- Work on your assigned branch and separate checkout/worktree. Do not switch
  branches underneath another active session.
- Build the smallest working slice. The first integration is one real photo ->
  local inference -> validated UI result -> explicit checkpoint confirmation.
- Keep changes scoped. Avoid unrelated refactors, formatting churn, framework
  migrations and dependencies that are not needed for the assigned slice.
- Use the repository's checked-in Gradle wrapper and documented commands. The
  bootstrap owner records the actual Android project path/build commands; do not
  assume this handoff folder itself is a buildable Android project.
- Run checks relevant to your changes. Route-state, stale-result and validation
  tests matter; trivial tests that repeat implementation do not.
- Before handoff, inspect `git diff --check`, changed-file scope and relevant
  build/tests. Report commit, what works, test commands/results, untested parts
  and any required shared change.
- Only the integration owner merges shared `main` and installs the integrated
  demo build. Coordinate individual phone testing slots to avoid overwrites.
- Keep weights, SDK paths, credentials, signing keys and generated build outputs
  out of commits. Never force-push shared history or reset others' work.
