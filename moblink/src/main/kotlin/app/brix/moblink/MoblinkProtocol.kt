package app.brix.moblink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

const val MOBLINK_API_VERSION = "1.0"

data class MoblinkAuthentication(val challenge: String, val salt: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("challenge", challenge)
        put("salt", salt)
    }

    companion object {
        fun fromJson(o: JsonObject): MoblinkAuthentication? {
            val challenge = o["challenge"]?.jsonPrimitive?.contentOrNull ?: return null
            val salt = o["salt"]?.jsonPrimitive?.contentOrNull ?: return null
            return MoblinkAuthentication(challenge, salt)
        }
    }
}

enum class MoblinkThermalState(val wire: String) {
    WHITE("white"),
    YELLOW("yellow"),
    RED("red"),
    ;

    companion object {
        fun fromWire(s: String): MoblinkThermalState? = entries.firstOrNull { it.wire == s }
    }
}

/** Wire shape verified against a live Moblin test vector (MoblinTests/MoblinkSuite.swift):
 *  even a payload-less case serializes as `{"caseName":{}}`, never a bare string. */
sealed class MoblinkResult(val wireName: String) {
    object Ok : MoblinkResult("ok")
    object WrongPassword : MoblinkResult("wrongPassword")
    object UnknownRequest : MoblinkResult("unknownRequest")
    object NotIdentified : MoblinkResult("notIdentified")
    object AlreadyIdentified : MoblinkResult("alreadyIdentified")

    fun toJson(): JsonObject = buildJsonObject { put(wireName, buildJsonObject {}) }

    companion object {
        // NOT a precomputed list of the sibling objects: building one eagerly
        // in this companion hits a real Kotlin/JVM class-init-order hazard
        // (the objects can still be null at that point), surfacing as an NPE
        // at runtime. A plain `when` resolves each singleton lazily instead.
        fun fromJson(o: JsonObject): MoblinkResult? {
            val key = o.keys.firstOrNull() ?: return null
            return when (key) {
                "ok" -> Ok
                "wrongPassword" -> WrongPassword
                "unknownRequest" -> UnknownRequest
                "notIdentified" -> NotIdentified
                "alreadyIdentified" -> AlreadyIdentified
                else -> null
            }
        }
    }
}

sealed class MoblinkRequestData {
    data class StartTunnel(val address: String, val port: Int) : MoblinkRequestData()
    object Status : MoblinkRequestData()

    fun toJson(): JsonObject = when (this) {
        is StartTunnel -> buildJsonObject {
            put(
                "startTunnel",
                buildJsonObject {
                    put("address", address)
                    put("port", port)
                },
            )
        }
        Status -> buildJsonObject { put("status", buildJsonObject {}) }
    }
}

sealed class MoblinkResponseData {
    data class StartTunnel(val port: Int) : MoblinkResponseData()
    data class Status(val batteryPercentage: Int?, val thermalState: MoblinkThermalState?) : MoblinkResponseData()

    companion object {
        fun fromJson(o: JsonObject): MoblinkResponseData? {
            o["startTunnel"]?.let { el ->
                val port = el.jsonObject["port"]?.jsonPrimitive?.intOrNull ?: return null
                return StartTunnel(port)
            }
            o["status"]?.let { el ->
                val obj = el.jsonObject
                val battery = obj["batteryPercentage"]?.jsonPrimitive?.intOrNull
                val thermal = obj["thermalState"]?.jsonPrimitive?.contentOrNull
                    ?.let { MoblinkThermalState.fromWire(it) }
                return Status(battery, thermal)
            }
            return null
        }
    }
}

/** Messages we (the streamer) send to a relay. */
sealed class MessageToRelay {
    data class Hello(val apiVersion: String, val authentication: MoblinkAuthentication) : MessageToRelay()
    data class Identified(val result: MoblinkResult) : MessageToRelay()
    data class Request(val id: Int, val data: MoblinkRequestData) : MessageToRelay()

    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), toJsonObject())

    private fun toJsonObject(): JsonObject = when (this) {
        is Hello -> buildJsonObject {
            put(
                "hello",
                buildJsonObject {
                    put("apiVersion", apiVersion)
                    put("authentication", authentication.toJson())
                },
            )
        }
        is Identified -> buildJsonObject {
            put("identified", buildJsonObject { put("result", result.toJson()) })
        }
        is Request -> buildJsonObject {
            put(
                "request",
                buildJsonObject {
                    put("id", id)
                    put("data", data.toJson())
                },
            )
        }
    }
}

/** Messages a relay sends to us (the streamer). */
sealed class MessageToStreamer {
    data class Identify(val id: UUID, val name: String, val authentication: String) : MessageToStreamer()
    data class Response(val id: Int, val result: MoblinkResult, val data: MoblinkResponseData?) : MessageToStreamer()

    companion object {
        fun fromJson(text: String): MessageToStreamer? {
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val key = obj.keys.firstOrNull() ?: return null
            val payload = obj[key]?.jsonObject ?: return null
            return when (key) {
                "identify" -> {
                    val id = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
                    val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: return null
                    val auth = payload["authentication"]?.jsonPrimitive?.contentOrNull ?: return null
                    Identify(id, name, auth)
                }
                "response" -> {
                    val id = payload["id"]?.jsonPrimitive?.intOrNull ?: return null
                    val result = payload["result"]?.jsonObject?.let { MoblinkResult.fromJson(it) } ?: return null
                    val data = payload["data"]?.takeIf { it != JsonNull }?.jsonObject
                        ?.let { MoblinkResponseData.fromJson(it) }
                    Response(id, result, data)
                }
                else -> null
            }
        }
    }
}
