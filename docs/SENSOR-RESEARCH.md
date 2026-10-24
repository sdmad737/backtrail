# Offline walking, reminders and Gemma: feasibility notes

Primary-source research checked October 23, 2026 Pacific. Recommendations below are proposed behavior, not implemented or measured Backtrail capabilities. The current starter has no route recorder or reminder implementation.

## Feasible product promise

Record a walk on the phone, save meaningful photo checkpoints, and later show the recorded path and relevant memories. During an explicitly active return session, local code can suggest a nearby checkpoint or reminder; the user confirms progress. “Works without cellular service” is a reasonable target. “Always knows your location” is not.

GPS satellites broadcast one-way reference signals, and Android's `GPS_PROVIDER` determines position from GNSS satellites. **Inference:** cellular service and a satellite fix are separate conditions; loss of cell reception does not inherently mean loss of positioning. Satellite visibility, the receiver and local obstructions still matter. [GPS.gov FAQ](https://www.gps.gov/faq), [Android GNSS provider](https://developer.android.com/reference/android/location/LocationManager#GPS_PROVIDER)

GPS.gov describes typical smartphone open-sky accuracy around 4.9 m, worsening near buildings, bridges and trees; this is general guidance, not a Pixel or Backtrail measurement. Do not use it as a hard checkpoint radius or promise. [GPS.gov accuracy](https://www.gps.gov/gps-accuracy-0)

Store each fix's timestamp and reported accuracy. Android's horizontal accuracy is an estimated 68th-percentile radius, not a guaranteed boundary. Monotonic elapsed-realtime timestamps support stale-fix checks within one boot. Its movement bearing is distinct from the phone's orientation. [Android Location reference](https://developer.android.com/reference/android/location/Location#getAccuracy())

**Recommendation:** show usable / approximate / stale / unavailable positioning, visibly mark gaps, and avoid snapping an uncertain fix to a plausible-looking path. With no usable fix, preserve saved photos, notes and the last-known position, with its age. Do not extrapolate a claimed live route through a GNSS outage.

## Pocket operation and screen-off recording

A foreground location service can retain location access after Home or screen-off. Declare the `location` service type, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION` permissions for the current target, and obtain foreground location permission before starting it. Start the walk from a visible activity; background startup is restricted by while-in-use permissions. Request precise location when needed, but support an honest degraded state if only approximate access is granted. An active, user-started foreground session is different from requesting always-on background tracking. [Location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions), [location service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#location)

Ordinary background location is throttled to only a few updates per hour on Android 8+. A periodic worker is therefore not a navigation recorder. [Background location limits](https://developer.android.com/about/versions/oreo/background-location-limits)

Doze defers jobs and ordinary alarms when the device is unplugged, stationary and screen-off. Walking, a stationary break and explicit Doze tests are different cases. A foreground service is not an immortal process: Android lets users stop it. Persist the route incrementally and show interruption/restart state; never imply a stopped session is still recording. [Doze behavior](https://developer.android.com/training/monitoring-device-state/doze-standby), [user-stop behavior](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)

**Recommendation:** screen off and phone in pocket is the intended low-interaction recording mode; photos require taking the phone out. Keep camera and Gemma off between requested checkpoints. Test the actual pocket position, device, battery mode and session length before claiming reliability.

## Sensors cannot supply a free GPS replacement

Android motion sensors use a device-relative coordinate system. A phone rotated in a pocket does not consistently face the walking direction. Android also restricts background sensor delivery; collect necessary sensor events through the active foreground session. [Sensor coordinates and background restrictions](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)

Game rotation vectors have a drifting reference rather than north; magnetometer-based orientation is susceptible to magnetic conditions. [Android position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)

The RoNIN authors explain why directly integrating phone acceleration accumulates bias rapidly and why fixed body/phone alignment assumptions break under natural carrying. Their work requires dedicated data, algorithms and evaluation; it is not evidence that this app has inertial navigation. [RoNIN, introduction and related work](https://arxiv.org/html/1905.12853v1)

**Recommendation:** use sensors for a held-phone compass or conservative motion hints later. Do not derive exact turns or distance from pocket orientation, use steps as proof of arrival, or add unvalidated inertial dead reckoning to tomorrow's scope.

## Offline reminders need local rules and honest alert states

Android's Geofencing API is unsuitable as the sole mechanism for this demo: its guide warns that unreliable network connectivity can prevent alerts because it depends on network location. Background delivery can average 2–3 minutes, with longer stationary delays; its recommended radius is 100–150 m. These are platform-documentation caveats, not our measured latency. [Android geofencing](https://developer.android.com/develop/sensors-and-location/location/geofencing)

**Recommendation:** while the return service is active, evaluate proximity using its fresh usable GNSS fixes and the expected checkpoint. Require several consistent observations, apply hysteresis/cooldown, and persist a deduplication key. These are design choices needing tests; do not invent universal distance/time thresholds. A reminder may say “Near your saved jacket checkpoint—check this photo.” User confirmation, not the reminder or model, advances progress. No usable fix means proximity reminders are unavailable, while their saved content remains accessible.

On Android 13+, ordinary alerts need `POST_NOTIFICATIONS`. Permission denial can leave a foreground service running with a Task Manager notice while hiding its notification from the drawer. Consequently, “recording active” does not prove “reminders can alert.” [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

Users control channel sound, vibration and importance; the app can inspect channel settings and link to them. Do Not Disturb can silence sound/vibration and affect presentation. Do not bypass user settings or promise an audible alert. Keep recording and reminders in separate channels, offer an explicit test notification, show blocked/silent status, and retain triggered reminders in an in-app list until acknowledged. [Notification channels](https://developer.android.com/develop/ui/compose/notifications/channels), [Do Not Disturb](https://developer.android.com/develop/ui/compose/notifications#do-not-disturb-mode)

## Is Gemma useful here; is training necessary?

Gemma 4 E2B is a pretrained vision/language model with image input. Google lists visual-data interpretation and summarization among intended uses, while warning that factual output can be incorrect. That makes it a plausible component for photo descriptions and evidence-grounded assistance; it does not establish checkpoint-localization accuracy. The selected native artifact's image support is documented separately in [MODEL-RESEARCH.md](MODEL-RESEARCH.md). [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)

**Recommendation:** first use the existing instruction-tuned model with bounded prompts and actual local context: the selected photos, user notes, and a small catalog of allowed checkpoint IDs. Ask for distinctive features, supporting/conflicting visual evidence, or a proposed destination. For a two- or three-checkpoint demonstration, ordinary local catalog selection can supply context; a vector database or embedding model is not a prerequisite. GPS math, route ordering, notification triggers and durable memory stay in app code.

Prompts/examples use existing capabilities without changing weights. Fine-tuning changes weights using task-specific examples; distillation typically adds teacher-generated labels and student training. None is a prerequisite for trying the pretrained slice. [Google explanation of prompting, tuning and distillation](https://developers.google.com/machine-learning/crash-course/llm/tuning)

**Recommendation:** do not retrain or distill for tomorrow. First evaluate real matching, confusingly similar, different, blurry and low-light scenes; ambiguous destination phrases; invalid outputs; and stale requests. Constrained/valid JSON proves structure, not visual truth. Validate allowed IDs and request/session/revision context, retain an insufficient-evidence result, and require explicit confirmation. The route remains usable through saved photos and user-entered notes when AI is unavailable; never label those as live model output.

Only consider fine-tuning after a repeated task-specific failure survives better prompts and evidence selection, with labeled examples, a held-out evaluation, and a proven path back to the phone runtime. Google's tuning guide requires framework/output-format compatibility and testing on success, failure and borderline cases. A new trained artifact also needs fresh conversion, integrity pins and device testing; it cannot silently replace the currently pinned `.litertlm`. [Gemma tuning workflow](https://ai.google.dev/gemma/docs/tune?hl=en)

## Required device test gates

1. **Positioning:** airplane mode with location enabled; open sky, trees/buildings and loss/reacquisition; record fix ages, gaps, accuracy estimates and actual landmark errors separately.
2. **Pocket recording:** phone held versus pocketed, screen off, stationary pause, Battery Saver and Doze; check complete persisted track and battery/thermal behavior.
3. **Permissions and interruption:** approximate/denied/revoked location, location toggle off, notification denial, user-stopped service and process restart; display the real state and never fabricate missing fixes.
4. **Reminders:** intended approach, nearby parallel path, outbound versus return, repeated boundary crossings, stale fixes, silent/blocked channel and DND; verify deduplication and acknowledgement without automatic route progression.
5. **Model value:** run the same offline photo/query cases with the model and with the saved-photo/manual workflow; record latency, invalid outputs, misleading matches and useful evidence. This establishes whether Gemma helps this task.

These are proposed acceptance checks. No sensor reliability, notification delivery, recognition accuracy, timing or power result is claimed here.
