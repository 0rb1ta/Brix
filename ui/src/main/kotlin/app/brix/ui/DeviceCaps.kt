package app.brix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import app.brix.core.Codec
import app.brix.streaming.DeviceCapabilities

@Composable
fun rememberDeviceCapabilities(): DeviceCapabilities {
    val context = LocalContext.current
    val caps by produceState(DeviceCapabilities.cachedOrNull() ?: DeviceCapabilities.ALL) {
        value = DeviceCapabilities.load(context)
    }
    return caps
}

fun codecLabel(codec: Codec): String = when (codec) {
    Codec.H264 -> "H.264"
    Codec.HEVC -> "H.265/HEVC"
}
