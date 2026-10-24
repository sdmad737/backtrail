# Travis: organizer rules check

Checked October 24, 2026, America/Los_Angeles. This research was written before implementation resumed. Rule findings remain the source record; the current product and implementation are in PRODUCT.txt and PREPARATION-STATUS.md. Historical statements below about paused or unimplemented work describe that earlier inspection.

## Required, optional, and prize-specific

The [official event page](https://www.agihouse.org/events/the-google-gemma-hardware-hackathon) states: **“Every project must use a Gemma-based model and run entirely on-device.”** Its broader description excludes cloud involvement. Thus Gemma-family use is required; Gemma 4 specifically is not a universal eligibility condition.

The [builder resource hub](https://www.agihouse.org/blog/google-gemma-hardware-builder-resource-hub#a-shared-starting-point-the-litert-demo-app) explicitly says **“any Gemma-based model and runtime qualifies.”** The supplied demo is optional. The guide discusses CPU, GPU, and NPU choices and lists older Gemma/FunctionGemma builds as well as Gemma 4. Consequently neither LiteRT nor NPU execution is a universal entry requirement.

| Question | Published position |
| --- | --- |
| Main track | Select one: Mobile AI, or Physical AI / IoT / Robotics. |
| Mobile prize | Rewards use of Gemma in a mobile app; $15,000 pool. |
| Best in Show | $15,000 plus Pixel hardware for an end-to-end on-device experience on Android and Google Pixel. |
| LiteRT | Separate $15,000 award pool for use of LiteRT. |
| Pixel / TPU | Separate hardware award; the page lists two Pixel 11 phones. |
| Stacking | One project may win several awards; special/partner awards remain available when their criteria are met. |

Source: [event prizes and eligibility](https://www.agihouse.org/events/the-google-gemma-hardware-hackathon). This page does not publish detailed scoring weights or a specific NPU performance threshold. Award decisions remain with judges. Do not infer that displaying an NPU label is necessary or sufficient to win.

## What is guidance rather than a mandate

The [technical primer](https://www.agihouse.org/blog/google-gemma-primer#build-day-project-ideas) offers multiple independent project ideas: voice agents, camera assistance, retrieval, benchmarks, actuation guards, and a sensor tricorder. It invites other ideas. Embeddings, voice, a particular tool set, a world model, and fine-tuning are not presented as universal requirements. It says the proposed projects need no pretraining. Its perception section recommends cheap continuous perception with expensive language-model calls triggered by events; it does not promise one multimodal inference every second on every phone.

The event's tricorder language describes the desired direction. It does not require every project to implement every sensing modality. A focused visual-memory app is consistent with that direction; its quality still needs demonstration.

There is no blanket prohibition on **Google APIs** in the inspected organizer text. The prohibition concerns cloud involvement in the runtime. The hub itself recommends Google's local runtimes and SDKs. Interpretation for Travis: local Android camera/location/notification/TTS APIs are compatible in principle, but each chosen capability must actually work locally with its assets already installed. An API's vendor name alone does not establish offline behavior. Do not use cloud inference, remote retrieval, or download-at-use fallbacks in the demonstrated flow.

## Implications for Travis

The user's latest premise is **Travis as visual trail memory**: remember the actual journey with photos, retrieve and compare saved evidence, and support return decisions locally. GPS is a supporting sensor; a basemap is not required; cloud runtime dependence is excluded. This differs from the GPS-first loop presently written in `docs/PRODUCT.txt`. That document and the implementation have not been silently reconciled or changed by this research.

**Gemma 4 E2B remains a suitable target.** Google's [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4) identifies E2B/E4B as mobile/edge variants with text, image, and audio inputs and text output. This supports image descriptions, comparison reasoning, and structured intent proposals; it does not establish reliable trail localization or navigation accuracy. Spoken output needs a separate local speech output path.

**Do not confuse EmbeddingGemma with EmbeddingGemma 2.** The hub and primer specifically recommend version 2. Google's [October 6 release](https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/) describes multimodal retrieval across text, images, audio, and video, with optional modality encoders. The [original EmbeddingGemma card](https://ai.google.dev/gemma/docs/embeddinggemma/model_card) concerns text embeddings and cannot be used to rule out version 2 image retrieval. Version 2 could retrieve candidate landmark photos; similarity is not proof of location, direction, safe passage, or a previously dropped object's whereabouts. Its exact LiteRT artifact and phone integration need separate validation.

Current repository inspection by the integration owner finds the Gemma 4 E2B LiteRT-LM artifact pinned and a GPU-configured Android image-inference adapter. That is **not measured phone execution and not NPU evidence**. Visual retrieval, continuous recording, voice, tool actions, and navigation are not established by the bootstrap. Keep the intended visual-memory premise separate from implemented capabilities.

Practical direction, not a new implementation commitment: record deliberate visual checkpoints, retrieve actual saved evidence, compare a live view against candidate references, let the user confirm a match/action, and retain an explicit unknown result. GPS/IMU may support this loop without being misrepresented as GPS-free visual dead reckoning. No source reviewed proves the proposed arbitrary indoor relocalization demo will work.

## Source access and limits

The full resource hub, full primer, and full event description were read from their rendered public pages in the in-app browser. Search/open tools could not render the two articles; direct HTTP fetches returned only the JavaScript application shell. The browser loaded the actual content successfully. No account login was needed. Existing repository notes were not substituted for organizer evidence.

The resource hub directs readers to the event page for current eligibility and prizes; no separate formal competition-rules document was linked in those sections. Missing details include a numerical judging rubric, exact evidence required for the Pixel/TPU award, and any guarantee of prize eligibility from a particular backend. These remain unknown, not inferred requirements. This report does not determine legal contest terms beyond the published material inspected.
