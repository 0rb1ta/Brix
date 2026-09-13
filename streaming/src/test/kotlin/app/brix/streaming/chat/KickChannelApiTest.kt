package app.brix.streaming.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `chatroom.id` and `chatroom.channel_id` are two different numbers (verified
 *  live: kaysan had 246956 vs 246960) — this is exactly the field mix-up that
 *  produced silent zero-messages during the first pass at this client. */
class KickChannelApiTest {

    @Test
    fun `chatroom_id и channel_id разбираются раздельно`() {
        val body = """{"id":246960,"slug":"kaysan","chatroom":{"id":246956,"channel_id":246960}}"""
        val info = parseKickChatroomInfo(body)
        assertEquals(KickChatroomInfo(chatroomId = "246956", chatroomChannelId = "246960"), info)
    }

    @Test
    fun `неожиданная форма ответа — null, не падение`() {
        assertNull(parseKickChatroomInfo("{}"))
        assertNull(parseKickChatroomInfo("not json"))
        assertNull(parseKickChatroomInfo("""{"chatroom":{}}"""))
    }
}
