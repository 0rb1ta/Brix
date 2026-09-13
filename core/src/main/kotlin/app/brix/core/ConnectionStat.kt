package app.brix.core

data class ConnectionStat(
    val type: String,
    val score: Int,
    val rtt: Int,
    val enabled: Boolean,
    val bytesSent: Long = 0,
    val packetsDropped: Long = 0,
)
