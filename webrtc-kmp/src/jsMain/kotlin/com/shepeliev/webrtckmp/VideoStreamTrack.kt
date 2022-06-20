package com.shepeliev.webrtckmp

import org.w3c.dom.mediacapture.MediaStreamTrack as JsMediaStreamTrack

actual class VideoStreamTrack internal constructor(js: JsMediaStreamTrack) : MediaStreamTrack(js) {
    actual suspend fun switchCamera(deviceId: String?) {
        console.warn("switchCamera() is not implemented in JS target")
    }

    actual fun setTorchEnabled(enabled: Boolean) {
        console.warn("setTorchEnabled() is not implemented in JS target")
    }
}
