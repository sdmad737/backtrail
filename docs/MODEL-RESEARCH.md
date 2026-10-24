# Gemma model preparation for the Pixel

Research checked October 23, 2026 Pacific. This is source and artifact-header research, not a phone execution report. Download completion and device results belong in `DECISIONS.txt`.

## Download the native model already selected for this app

The current Android starter expects the following exact file. The publisher's public API was queried without credentials and returned `private: false`, `gated: false`, and an Apache-2.0 license declaration. No Hugging Face login or gated-access acceptance is required for this artifact. [Pinned publisher metadata](https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/revision/1caa527c1e88b55f046fcd8d2b4265606680663d?blobs=true)

```text
Repository: litert-community/gemma-4-E2B-it-litert-lm
Revision:   1caa527c1e88b55f046fcd8d2b4265606680663d
File:       gemma-4-E2B-it.litertlm
Bytes:      2588147712
SHA-256:    181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
```

Google's Android Gallery configuration identifies this native filename and size, enables image input, selects GPU vision, and sets an 8 GB minimum-device-memory filter. Treat 8 GB as a preparation target, not a guarantee of this app's performance. The file contains the image model; a separate vision-weight download is unnecessary. [Google Gallery configuration](https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_20.json)

The publisher distinguishes native deployment from the Web-specific, text-only model. Use the native file above, rather than a `.task`, `-web`, GGUF, or chip-specific NPU file. [Publisher model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)

## Resolve the apparently conflicting vision-backend instructions

The organizer-linked Flutter sample documents CPU vision/audio towers. It explains that its Flutter plugin chooses a declared backend, then an applicable NPU, otherwise CPU. That is the sample's policy; it does not prove the native vision encoder requires CPU. [Sample configuration explanation](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/flutter/README.md#chat-model-files)

Read-only inspection of the native download's initial header, decoded using Google's schema, found:

| Section | Header metadata |
|---|---|
| `tf_lite_vision_encoder` | `prefer_activation_type=fp16`; no `backend_constraint` |
| `tf_lite_vision_adapter` | `backend_constraint=cpu` |
| `tf_lite_audio_encoder_hw` / `tf_lite_audio_adapter` | `backend_constraint=cpu` |

This inspected header is metadata evidence, not model initialization or image-inference evidence. [Container schema](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/schema/core/litertlm_header_schema.fbs), [header reader](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/schema/core/litertlm_read.cc)

In LiteRT-LM 0.18.0, `EngineSettings::CreateDefault` maps the requested vision backend to the **encoder**, while explicitly putting the **adapter on CPU**. Kotlin JNI calls that factory. The vision executor implements both CPU and GPU encoders. Therefore the existing `visionBackend = Backend.GPU()` requests a GPU encoder with a CPU adapter; it does not request that every vision operation run on GPU. Keep this configuration pending the actual Pixel smoke test; a CPU change is not established as necessary by the Flutter README. [Engine settings, lines 218–224](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/engine/engine_settings.cc#L218), [Kotlin JNI, lines 524–526](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/jni/litertlm.cc#L524), [vision executor](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/executor/vision_litert_compiled_model_executor.cc#L230)

The pinned Kotlin guide supports `Content.ImageFile`, requires a configured vision backend, and lists Android GPU native-library declarations. The starter already declares `libOpenCL.so` and `libvndksupport.so` as optional native libraries. Their declaration alone does not prove the phone's driver works. [Kotlin 0.18.0 guide](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/api/kotlin/getting_started.md), [local manifest](../app/src/main/AndroidManifest.xml)

## Pixel Tensor NPU is a separate integration choice

Google publishes a separate Gemma E2B bundle for Tensor G6 / Pixel 11. Google Tensor SDK compilation access requires beta approval, but the precompiled-model route is separately documented. [Google Tensor SLM guide](https://developers.google.com/edge/tensor-sdk/slm-compilation)

An NPU deployment needs the matching chip bundle, NPU backend selection and Google Tensor dispatch/runtime setup. Downloading that bundle alone would not enable NPU in this starter; the current importer intentionally rejects other model hashes. Keep the general native model as the required preparation. If the integration owner later chooses Tensor acceleration, scope and test that change explicitly. [Google Tensor NPU setup](https://developers.google.com/edge/litert/next/litert_lm_npu#google_tensor), [local importer](../app/src/main/java/com/backtrail/app/ai/ModelStore.kt)

## Checks still requiring the actual phone

1. Record exact Pixel model, Android version, ARM64 support, RAM and free storage. Keep room for both the transferred 2.59 GB file and its app-private copy, plus runtime caches; the two files alone occupy about 5.18 GB.
2. Enable USB debugging, approve the laptop, and let the integration owner install the built APK. Import the verified local model and a real local photo.
3. Disable cellular and Wi-Fi. Run image description, a different image, an unclear image and cancellation. Record success, invalid JSON, native errors and timing as observed; preserve visible failure states.
4. Verify cold startup, repeat requests, cancellation and memory pressure before extending the app. The current adapter opens/closes an engine for every request; it has no warm-session benchmark or fallback backend.

The final checklist derives from the current [setup](SETUP.md), [adapter](../app/src/main/java/com/backtrail/app/ai/LocalVisionService.kt) and [importer](../app/src/main/java/com/backtrail/app/ai/ModelStore.kt). No reviewed source establishes that Backtrail has run successfully on a Pixel. Published Samsung or other-device timings must not be reported as Backtrail measurements.
