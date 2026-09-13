package app.brix.streaming.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистые функции, без сети и без Android — сверено построчно с реальными
 * примерами из Twitch IRC (см. traps в TwitchChatClient: PING/PONG, CAP REQ
 * tags, RECONNECT).
 */
class TwitchIrcParserTest {

    @Test
    fun `PING без тегов`() {
        val event = TwitchIrcParser.parse("PING :tmi.twitch.tv")
        assertEquals(TwitchIrcEvent.Ping, event)
    }

    @Test
    fun `RECONNECT — команда, не ошибка`() {
        val event = TwitchIrcParser.parse(":tmi.twitch.tv RECONNECT")
        assertEquals(TwitchIrcEvent.Reconnect, event)
    }

    @Test
    fun `PRIVMSG с полными тегами даёт цвет и отображаемое имя`() {
        val line = "@badge-info=;badges=broadcaster/1;color=#FF0000;display-name=PogUser;" +
            "emotes=;id=abc;mod=0;room-id=1;subscriber=0;tmi-sent-ts=123;turbo=0;user-id=1;" +
            "user-type= :poguser!poguser@poguser.tmi.twitch.tv PRIVMSG #somechannel :Hello world!"
        val event = TwitchIrcParser.parse(line)
        assertTrue(event is TwitchIrcEvent.Privmsg)
        event as TwitchIrcEvent.Privmsg
        assertEquals("somechannel", event.channel)
        assertEquals("PogUser", event.displayName)
        assertEquals("#FF0000", event.colorHex)
        assertEquals("Hello world!", event.text)
    }

    @Test
    fun `PRIVMSG без тегов — без CAP REQ tags`() {
        val line = ":someuser!someuser@someuser.tmi.twitch.tv PRIVMSG #somechannel :hi"
        val event = TwitchIrcParser.parse(line)
        assertTrue(event is TwitchIrcEvent.Privmsg)
        event as TwitchIrcEvent.Privmsg
        assertEquals("someuser", event.displayName)
        assertNull(event.colorHex)
        assertEquals("hi", event.text)
    }

    @Test
    fun `пустой color в тегах — не путать с заданным`() {
        val line = "@color=;display-name=NoColorUser :nc!nc@nc.tmi.twitch.tv PRIVMSG #ch :yo"
        val event = TwitchIrcParser.parse(line) as TwitchIrcEvent.Privmsg
        assertNull(event.colorHex)
    }

    @Test
    fun `экранированные символы в тегах`() {
        // ':' -> '\:' (запятая на деле не нужна экранировать, тест на display-name
        // с пробелом — Twitch не шлёт пробелы в никнейме, но парсер не должен
        // падать на escape-последовательностях в принципе).
        val line = "@display-name=Weird\\sName;color=#123456 :w!w@w.tmi.twitch.tv PRIVMSG #ch :test"
        val event = TwitchIrcParser.parse(line) as TwitchIrcEvent.Privmsg
        assertEquals("Weird Name", event.displayName)
    }

    @Test
    fun `ACTION разворачивается в обычный текст`() {
        val ctcp = '\u0001'
        val line = ":u!u@u.tmi.twitch.tv PRIVMSG #ch :$ctcp" + "ACTION waves" + ctcp
        val event = TwitchIrcParser.parse(line) as TwitchIrcEvent.Privmsg
        assertEquals("waves", event.text)
    }

    @Test
    fun `неизвестная команда игнорируется, не падает`() {
        assertEquals(TwitchIrcEvent.Other, TwitchIrcParser.parse(":tmi.twitch.tv NOTICE * :garbage"))
        assertEquals(TwitchIrcEvent.Other, TwitchIrcParser.parse(""))
        assertEquals(TwitchIrcEvent.Other, TwitchIrcParser.parse("@"))
        assertEquals(TwitchIrcEvent.Other, TwitchIrcParser.parse(":"))
    }

    @Test
    fun `CAP ACK и JOIN эхо тоже Other`() {
        assertEquals(
            TwitchIrcEvent.Other,
            TwitchIrcParser.parse(":tmi.twitch.tv CAP * ACK :twitch.tv/tags twitch.tv/commands"),
        )
        assertEquals(
            TwitchIrcEvent.Other,
            TwitchIrcParser.parse(":justinfan12345!justinfan12345@justinfan12345.tmi.twitch.tv JOIN #somechannel"),
        )
    }
}
