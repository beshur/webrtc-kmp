package com.shepeliev.webrtckmp

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import org.webrtc.*
import org.webrtc.CameraEnumerationAndroid.CaptureFormat
import org.webrtc.Size
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraVideoCaptureController(private val constraints: VideoTrackConstraints) :
    AbstractVideoCaptureController() {

    private val tag = "CameraCaptureController"
    private val enumerator = Camera2Enumerator(applicationContext)
    private var device: String? = null

    override fun createVideoCapturer(): VideoCapturer {
        selectDevice()
        return enumerator.createCapturer(device, CameraEventsHandler())
    }

    private fun selectDevice() {
        val deviceId = constraints.deviceId
        val isFrontFacing = constraints.facingMode?.exact == FacingMode.User ||
            constraints.facingMode?.ideal == FacingMode.User

        val searchCriteria: (String) -> Boolean = if (deviceId != null) {
            { it == deviceId }
        } else {
            { enumerator.isFrontFacing(it) == isFrontFacing }
        }

        device = enumerator.deviceNames.firstOrNull(searchCriteria)
            ?: throw CameraVideoCapturerException.notFound(constraints)
    }

    override fun selectVideoSize(): Size {
        val requestedWidth = constraints.width?.exact
            ?: constraints.width?.ideal
            ?: DEFAULT_VIDEO_WIDTH
        val requestedHeight = constraints.height?.exact
            ?: constraints.height?.ideal
            ?: DEFAULT_VIDEO_HEIGHT

        val formats = enumerator.getSupportedFormats(device)
        val sizes = formats?.map { Size(it.width, it.height) } ?: emptyList()
        if (sizes.isEmpty()) throw CameraVideoCapturerException.notFound(constraints)

        return CameraEnumerationAndroid.getClosestSupportedSize(
            sizes,
            requestedWidth,
            requestedHeight
        )
    }

    override fun selectFps(): Int {
        val requestedFps = constraints.frameRate?.exact
            ?: constraints.frameRate?.ideal
            ?: DEFAULT_FRAME_RATE

        val formats = enumerator.getSupportedFormats(device)
        val framerates = formats?.map { it.framerate } ?: emptyList()
        if (framerates.isEmpty()) throw CameraVideoCapturerException.notFound(constraints)

        val requestedFpsInt = requestedFps.toInt()
        val range = CameraEnumerationAndroid.getClosestSupportedFramerateRange(
            framerates,
            requestedFpsInt
        )

        return requestedFpsInt.coerceIn(range.min / 1000, range.max / 1000)
    }

    suspend fun switchCamera() {
        val cameraCapturer = videoCapturer as CameraVideoCapturer
        suspendCoroutine<Unit> { cameraCapturer.switchCamera(switchCameraHandler(it)) }
    }

    suspend fun switchCamera(deviceId: String) {
        val cameraCapturer = videoCapturer as CameraVideoCapturer
        suspendCoroutine<Unit> { cameraCapturer.switchCamera(switchCameraHandler(it), deviceId) }
    }

    private fun switchCameraHandler(continuation: Continuation<Unit>): CameraVideoCapturer.CameraSwitchHandler {
        return object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                Log.d(tag, "Camera switched. isFront: $isFrontFacing")
                continuation.resume(Unit)
            }

            override fun onCameraSwitchError(error: String?) {
                val message = "Switch camera failed: $error"
                Log.e(tag, message)
                continuation.resumeWithException(CameraVideoCapturerException(message))
            }
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        val captureSession: CameraCaptureSession
        val cameraDevice: CameraDevice
        val captureFormat: CaptureFormat
        val fpsUnitFactor: Int
        val surface: Surface
        val cameraThreadHandler: Handler

        try {
            val session: Any = getPrivateProperty(
                Camera2Capturer::class.java.superclass,
                videoCapturer,
                "currentSession"
            ) ?: run {
                Log.e(tag, "setTorchEnabled => Failed to get currentSession")
                return
            }

            captureSession = getPrivateProperty(session.javaClass, session, "captureSession") as CameraCaptureSession
            cameraDevice = getPrivateProperty(session.javaClass, session, "cameraDevice") as CameraDevice
            captureFormat = getPrivateProperty(session.javaClass, session, "captureFormat") as CaptureFormat
            fpsUnitFactor = getPrivateProperty(session.javaClass, session, "fpsUnitFactor") as Int
            surface = getPrivateProperty(session.javaClass, session, "surface") as Surface
            cameraThreadHandler = getPrivateProperty(session.javaClass, session, "cameraThreadHandler") as Handler
        } catch (e: NuSuchFieldWithNameException) {
            // Most likely the upstream Camera2Capturer class have changed
            Log.e(tag, "setTorchEnabled => Failed to get '${e.fieldName}' from ${e.className}")
            return
        }

        try {
            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.set(
                CaptureRequest.FLASH_MODE,
                if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(
                    captureFormat.framerate.min / fpsUnitFactor,
                    captureFormat.framerate.max / fpsUnitFactor
                )
            )
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON
            )
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            captureRequestBuilder.addTarget(surface)
            captureSession.setRepeatingRequest(
                captureRequestBuilder.build(), null, cameraThreadHandler
            )
        } catch (e: CameraAccessException) {
            // Should never happen since we are already accessing the camera
            throw RuntimeException(e)
        }

        return
    }

    private fun getPrivateProperty(clazz: Class<*>?, obj: Any, propName: String): Any? {
        if (clazz == null) return null

        return try {
            val field = clazz.getDeclaredField(propName)
            field.isAccessible = true
            field.get(obj)
        } catch (e: NoSuchFieldException) {
            throw NuSuchFieldWithNameException(clazz.canonicalName, propName)
        }
    }

    private inner class CameraEventsHandler : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String) {
            Log.e(tag, "Camera error: $errorDescription")
        }

        override fun onCameraDisconnected() {
            Log.w(tag, "Camera disconnected")
        }

        override fun onCameraFreezed(errorDescription: String) {
            Log.e(tag, "Camera freezed: $errorDescription")
        }

        override fun onCameraOpening(cameraId: String) {
            Log.d(tag, "Opening camera $cameraId")
        }

        override fun onFirstFrameAvailable() {
            Log.d(tag, "First frame available")
        }

        override fun onCameraClosed() {
            Log.d(tag, "Camera closed")
        }
    }
}

private class NuSuchFieldWithNameException(val className: String?, val fieldName: String) : NoSuchFieldException()
