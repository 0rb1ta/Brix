package app.brix.streaming

import app.brix.core.Codec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {

    private val caps = DeviceCapabilities(
        hardwareCodecs = setOf(Codec.H264),
        encoderSupports = mapOf(Codec.H264 to { w: Int, h: Int, fps: Int -> w * h <= 1920 * 1080 && fps <= 60 }),
        cameraMaxFps = 30,
        cameraSizes = listOf(1920 to 1080, 1440 to 1080),
        opticalStabilization = true,
        electronicStabilization = false,
        torch = true,
    )

    @Test
    fun `режим годится, только если его тянут и энкодер, и камера`() {
        assertTrue(caps.supports(Codec.H264, VideoMode(1280, 720, 30)))
        assertFalse("камера до 30 fps", caps.supports(Codec.H264, VideoMode(1280, 720, 60)))
        assertFalse("энкодер не тянет 4K", caps.supports(Codec.H264, VideoMode(3840, 2160, 30)))
        assertFalse("HEVC-энкодера нет", caps.supports(Codec.HEVC, VideoMode(1280, 720, 30)))
    }

    @Test
    fun `камере нужен режим той же пропорции не меньше заданного`() {
        assertTrue(caps.supports(Codec.H264, VideoMode(1920, 1080, 30)))
        assertFalse("нет режима 1:1", caps.supports(Codec.H264, VideoMode(720, 720, 30)))
    }
}
