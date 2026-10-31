package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.shared.R as UiR

enum class StreamTechnology(@param:androidx.annotation.StringRes val labelRes: Int) {
    /** WebSocket + WebCodecs: lowest latency, TCP retransmits for us. Default. */
    WEBCODECS(UiR.string.ui_webcodecs_low_latency),
    WEBRTC(UiR.string.ui_webrtc_video_passthrough_automatic_fallback),
    JPEG(UiR.string.ui_jpeg_compatibility_mode),
}
