package app.brix.moblink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MoblinkProtocolTest {

    private fun normalize(json: String): String =
        Json.parseToJsonElement(json).jsonObject.toString()

    @Test
    fun `hello encodes with nested case keys`() {
        val message = MessageToRelay.Hello(MOBLINK_API_VERSION, MoblinkAuthentication("chal", "salt"))
        val expected = """{"hello":{"apiVersion":"1.0","authentication":{"challenge":"chal","salt":"salt"}}}"""
        assertEquals(normalize(expected), normalize(message.toJson()))
    }

    @Test
    fun `identified ok encodes payload-less case as empty object, not bare string`() {
        val message = MessageToRelay.Identified(MoblinkResult.Ok)
        assertEquals(normalize("""{"identified":{"result":{"ok":{}}}}"""), normalize(message.toJson()))
    }

    @Test
    fun `identified wrongPassword encodes correctly`() {
        val message = MessageToRelay.Identified(MoblinkResult.WrongPassword)
        assertEquals(normalize("""{"identified":{"result":{"wrongPassword":{}}}}"""), normalize(message.toJson()))
    }

    @Test
    fun `request startTunnel encodes address and port`() {
        val message = MessageToRelay.Request(3, MoblinkRequestData.StartTunnel("1.2.3.4", 5000))
        val expected = """{"request":{"id":3,"data":{"startTunnel":{"address":"1.2.3.4","port":5000}}}}"""
        assertEquals(normalize(expected), normalize(message.toJson()))
    }

    @Test
    fun `identify decodes from relay message`() {
        val id = UUID.randomUUID()
        val json = """{"identify":{"id":"$id","name":"Relay 1","authentication":"abc123"}}"""
        val decoded = MessageToStreamer.fromJson(json)
        assertTrue(decoded is MessageToStreamer.Identify)
        decoded as MessageToStreamer.Identify
        assertEquals(id, decoded.id)
        assertEquals("Relay 1", decoded.name)
        assertEquals("abc123", decoded.authentication)
    }

    @Test
    fun `identify with malformed uuid fails to decode`() {
        val json = """{"identify":{"id":"not-a-uuid","name":"Relay 1","authentication":"abc123"}}"""
        assertNull(MessageToStreamer.fromJson(json))
    }

    @Test
    fun `response startTunnel decodes port`() {
        val json = """{"response":{"id":1,"result":{"ok":{}},"data":{"startTunnel":{"port":12345}}}}"""
        val decoded = MessageToStreamer.fromJson(json)
        assertTrue(decoded is MessageToStreamer.Response)
        decoded as MessageToStreamer.Response
        assertEquals(1, decoded.id)
        assertEquals(MoblinkResult.Ok, decoded.result)
        assertEquals(MoblinkResponseData.StartTunnel(12345), decoded.data)
    }

    // Ground-truth vector, verbatim from real Moblin's own test suite
    // (MoblinTests/MoblinkSuite.swift, decodeStatusResponse) — the exact shape a
    // genuine Moblin relay sends, not a guess at the format.
    @Test
    fun `decodes real Moblin status response vector`() {
        val json = """
            {"response":{"id":5,"data":{"status":{"thermalState":"white","batteryPercentage":30}},"result":{"ok":{}}}}
        """.trimIndent()
        val decoded = MessageToStreamer.fromJson(json)
        assertNotNull(decoded)
        assertTrue(decoded is MessageToStreamer.Response)
        decoded as MessageToStreamer.Response
        assertEquals(5, decoded.id)
        assertEquals(MoblinkResult.Ok, decoded.result)
        val status = decoded.data as MoblinkResponseData.Status
        assertEquals(30, status.batteryPercentage)
        assertEquals(MoblinkThermalState.WHITE, status.thermalState)
    }

    @Test
    fun `response with wrongPassword result decodes`() {
        val json = """{"response":{"id":2,"result":{"wrongPassword":{}},"data":null}}"""
        val decoded = MessageToStreamer.fromJson(json) as MessageToStreamer.Response
        assertEquals(MoblinkResult.WrongPassword, decoded.result)
        assertNull(decoded.data)
    }

    @Test
    fun `garbage input fails to decode instead of throwing`() {
        assertNull(MessageToStreamer.fromJson("not json at all"))
        assertNull(MessageToStreamer.fromJson("""{"unknownCase":{}}"""))
    }
}
