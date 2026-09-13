package app.brix.streaming.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads below are real frames captured 04.09 from a live Kick channel
 *  (`wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679`, `chatrooms.<id>.v2`)
 *  — not hand-written guesses. */
class KickPusherParserTest {

    @Test
    fun `реальный кадр ChatMessageEvent разбирается`() {
        val raw = """
            {"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"57eee8aa-7818-4194-9422-cedf3a00aca4\",\"chatroom_id\":246956,\"content\":\"I see the coke\",\"type\":\"message\",\"created_at\":\"2026-09-04T23:28:30+00:00\",\"sender\":{\"id\":117108965,\"username\":\"goofywilson96\",\"slug\":\"goofywilson96\",\"identity\":{\"color\":\"#31D6C2\",\"badges\":[{\"type\":\"subscriber\",\"text\":\"Subscriber\",\"count\":3}]}}}"}
        """.trimIndent()
        val event = KickPusherParser.parse(raw)
        assertTrue(event is KickPusherEvent.ChatMessage)
        event as KickPusherEvent.ChatMessage
        assertEquals("goofywilson96", event.author)
        assertEquals("#31D6C2", event.colorHex)
        assertEquals("I see the coke", event.text)
    }

    @Test
    fun `эмодзи-тег разворачивается в читаемое имя`() {
        val raw = """
            {"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"d1\",\"chatroom_id\":1,\"content\":\"[emote:37226:KEKW]\",\"sender\":{\"username\":\"u\",\"identity\":{\"color\":\"#FFFFFF\",\"badges\":[]}}}"}
        """.trimIndent()
        val event = KickPusherParser.parse(raw) as KickPusherEvent.ChatMessage
        assertEquals("KEKW", event.text)
    }

    @Test
    fun `несколько эмодзи подряд`() {
        val raw = """
            {"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"d2\",\"chatroom_id\":1,\"content\":\"[emote:1:A][emote:2:B]\",\"sender\":{\"username\":\"u\",\"identity\":{\"color\":\"#FFFFFF\",\"badges\":[]}}}"}
        """.trimIndent()
        val event = KickPusherParser.parse(raw) as KickPusherEvent.ChatMessage
        assertEquals("AB", event.text)
    }

    @Test
    fun `subscription_succeeded и прочие служебные кадры игнорируются`() {
        val raw = """{"event":"pusher_internal:subscription_succeeded","data":"{}","channel":"chatrooms.246956.v2"}"""
        assertEquals(KickPusherEvent.Other, KickPusherParser.parse(raw))
    }

    @Test
    fun `connection_established игнорируется`() {
        val raw = """{"event":"pusher:connection_established","data":"{\"socket_id\":\"1\",\"activity_timeout\":120}"}"""
        assertEquals(KickPusherEvent.Other, KickPusherParser.parse(raw))
    }

    @Test
    fun `мусор не роняет парсер`() {
        assertEquals(KickPusherEvent.Other, KickPusherParser.parse(""))
        assertEquals(KickPusherEvent.Other, KickPusherParser.parse("{not json"))
        assertEquals(KickPusherEvent.Other, KickPusherParser.parse("{}"))
    }

    @Test
    fun `пустой color не путается с заданным`() {
        val raw = """
            {"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"d3\",\"chatroom_id\":1,\"content\":\"hi\",\"sender\":{\"username\":\"u\",\"identity\":{\"color\":\"\",\"badges\":[]}}}"}
        """.trimIndent()
        val event = KickPusherParser.parse(raw) as KickPusherEvent.ChatMessage
        assertEquals(null, event.colorHex)
    }

    @Test
    fun `kickSubscribeChannels строит все варианты`() {
        val channels = kickSubscribeChannels("246956", "246960")
        assertEquals(
            listOf(
                "chatrooms.246956.v2",
                "chatroom_246956",
                "chatrooms.246956",
                "channel_246960",
            ),
            channels,
        )
    }
}
