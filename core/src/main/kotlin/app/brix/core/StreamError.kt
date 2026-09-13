package app.brix.core

/**
 * Stable, user-facing error codes for the streaming session. Exposed through
 * [StreamState] so the UI and notification can show a clear reason instead of
 * a bare exception message.
 */
enum class StreamErrorCode {
    CAMERA_PERMISSION_DENIED,
    CAMERA_OPEN_FAILED,
    ENCODER_PREPARE_FAILED,
    SERVER_REJECTED,
    AUTH_FAILED,
    NETWORK_UNAVAILABLE,
    SRTLA_REGISTRATION_FAILED,
    SRT_TIMEOUT,
    STORAGE_LOW,
    UNKNOWN,
}

data class StreamError(
    val code: StreamErrorCode,
    val message: String,
    val technical: String? = null,
    val retryable: Boolean = false,
) {
    companion object {
        fun unknown(message: String, technical: String? = null) = StreamError(
            code = StreamErrorCode.UNKNOWN,
            message = message,
            technical = technical,
        )
    }
}