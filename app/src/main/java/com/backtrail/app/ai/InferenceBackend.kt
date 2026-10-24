package com.backtrail.app.ai

import com.google.ai.edge.litertlm.Backend

/**
 * Where a LiteRT-LM model runs. Kept as our own enum so the choice can be named in traces,
 * benchmarks and the UI without leaking runtime classes.
 *
 * [TENSOR] and [NPU] are two ways the 0.18.0 runtime exposes the Pixel's accelerator; which
 * one a given `.litertlm` bundle accepts is an on-device measurement, not an assumption.
 */
enum class InferenceBackend {
    CPU, GPU, TENSOR, NPU;

    fun toRuntime(nativeLibraryDir: String?): Backend = when (this) {
        CPU -> Backend.CPU()
        GPU -> Backend.GPU()
        TENSOR -> Backend.GOOGLE_TENSOR()
        NPU -> if (nativeLibraryDir != null) Backend.NPU(nativeLibraryDir) else Backend.NPU()
    }
}
