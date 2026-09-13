package app.brix.moblink

/** Read-only snapshot of one identified relay, for UI/HUD display. */
data class MoblinkRelayInfo(
    val id: String,
    val name: String,
    val batteryPercentage: Int?,
    val thermalState: MoblinkThermalState?,
)
