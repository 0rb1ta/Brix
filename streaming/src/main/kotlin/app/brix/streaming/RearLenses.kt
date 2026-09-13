package app.brix.streaming

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.util.Locale

/** A rear lens the user can switch to via the lens bar. */
data class RearLens(
    /** Unique UI key (cameraId, or cameraId@zoom for virtual lenses). */
    val id: String,
    /** Camera2 device id to open. */
    val cameraId: String,
    val label: String,
    /** Zoom ratio applied after opening (virtual tele = main camera + crop). */
    val zoom: Float = 1f,
)

/**
 * Enumerate rear lenses with Moblin-style zoom labels (0.5x / 1x / 3x).
 *
 * Zoom factor is relative to the device's MAIN rear camera (focal in the
 * 4.5–8mm range). Includes physical sub-cameras of logical multi-camera
 * devices (API 30+). Phones whose "tele" is a crop-zoom sensor (e.g. Galaxy
 * S21: only main + ultra-wide are exposed) get a VIRTUAL tele pill backed by
 * the main camera at 3x zoom ratio.
 */
fun Context.rearLenses(): List<RearLens> {
    val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
    data class Raw(val id: String, val focal: Float, val zoomRange: android.util.Range<Float>?)

    val raws = mutableListOf<Raw>()
    fun addIfBack(id: String) {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
        if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.filter { it > 0f }
            ?.minOrNull() ?: return
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        raws += Raw(id, focal, zoomRange)
    }

    for (id in manager.cameraIdList) {
        addIfBack(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            for (physicalId in chars.physicalCameraIds) addIfBack(physicalId)
        }
    }

    val mains = raws.filter { it.focal in 4.5f..8f }
    val main = mains.maxByOrNull { it.focal } ?: raws.maxByOrNull { it.focal } ?: return emptyList()

    val lenses = raws
        .distinctBy { it.id }
        .sortedBy { it.focal }
        .map { r -> RearLens(r.id, r.id, "%.1fx".format(Locale.US, r.focal / main.focal)) }

    // No real tele? Offer a virtual one from the main camera's zoom range.
    val hasTele = raws.any { it.focal > main.focal * 1.8f }
    val mainZoomMax = main.zoomRange?.upper ?: 1f
    return if (!hasTele && mainZoomMax >= 3f) {
        val virtual = RearLens("${main.id}@3.0", main.id, "3.0x", zoom = 3f)
        lenses + virtual
    } else {
        lenses
    }
}
