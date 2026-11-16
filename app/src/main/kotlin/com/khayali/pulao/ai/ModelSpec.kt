package com.khayali.pulao.ai

/**
 * The on-device model. Pinned to an exact file + sha256 so a tampered or partial
 * download can never be loaded. Source: Hugging Face (Apache-2.0, not gated).
 */
object ModelSpec {
    const val DISPLAY_NAME = "Gemma 4 E2B"
    const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val FILE = "gemma-4-E2B-it.litertlm"
    const val SIZE_BYTES = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val LICENSE = "Apache-2.0"

    /** The ONLY network address this app ever contacts. Enforced by OnDeviceOnlyTest. */
    const val HOST = "huggingface.co"
    const val URL = "https://$HOST/$REPO/resolve/main/$FILE"
    const val PAGE = "https://$HOST/$REPO"

    /** Keep this much free after the download so the phone stays healthy. */
    const val HEADROOM_BYTES = 500L * 1024 * 1024
}
