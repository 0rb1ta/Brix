package app.brix.streaming.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives [TwitchChatClient] without any real socket — connect()/send()/close()
 *  just record calls, and the test plays the server side by calling the
 *  listener back directly. Same trick as [app.brix.streaming.StreamReconnectorTest]:
 *  a fake behind the injectable seam instead of a real network. */
private class FakeChatSocket(
    val onConnect: (FakeChatSocket) -> Unit,
) : ChatSocket {
    val sent = mutableListOf<String>()
    var closed = false
    lateinit var listener: ChatSocketListener

    override fun connect() {
        onConnect(this)
    }

    override fun send(text: String) {
        sent += text
    }

    override fun close() {
        closed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TwitchChatClientTest {

    private fun newClient(
        scope: TestScope,
        maxMessages: Int = 250,
    ): Pair<TwitchChatClient, MutableList<FakeChatSocket>> {
        val sockets = mutableListOf<FakeChatSocket>()
        val client = TwitchChatClient(
            scope = scope,
            socketFactory = { _, listener ->
                val socket = FakeChatSocket(onConnect = {})
                socket.listener = listener
                sockets += socket
                socket
            },
            maxMessages = maxMessages,
        )
        return client to sockets
    }

    @Test
    fun `start открывает сокет и шлёт CAP REQ, NICK, JOIN в этом порядке`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)

        client.start("SomeChannel")
        val socket = sockets.single()
        socket.listener.onOpen()

        assertEquals(3, socket.sent.size)
        assertTrue(socket.sent[0].startsWith("CAP REQ :twitch.tv/tags"))
        assertTrue(socket.sent[1].startsWith("NICK justinfan"))
        assertEquals("JOIN #somechannel", socket.sent[2])
    }

    @Test
    fun `PING получает PONG`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan")
        val socket = sockets.single()
        socket.listener.onOpen()

        socket.listener.onLine("PING :tmi.twitch.tv")

        assertEquals("PONG :tmi.twitch.tv", socket.sent.last())
    }

    @Test
    fun `RECONNECT закрывает сокет вместо падения`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan")
        val socket = sockets.single()
        socket.listener.onOpen()

        socket.listener.onLine(":tmi.twitch.tv RECONNECT")

        assertTrue(socket.closed)
    }

    @Test
    fun `PRIVMSG попадает в messages`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan")
        sockets.single().listener.onOpen()
        sockets.single().listener.onLine(
            "@color=#FF0000;display-name=Foo :foo!foo@foo.tmi.twitch.tv PRIVMSG #chan :hi there",
        )

        val messages = client.messages.value
        assertEquals(1, messages.size)
        assertEquals("Foo", messages.single().author)
        assertEquals("hi there", messages.single().text)
        assertEquals("#FF0000", messages.single().colorHex)
    }

    @Test
    fun `хвост списка ограничен maxMessages`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope, maxMessages = 3)
        client.start("chan")
        val socket = sockets.single()
        socket.listener.onOpen()

        repeat(5) { i ->
            socket.listener.onLine(
                ":u$i!u$i@u$i.tmi.twitch.tv PRIVMSG #chan :msg$i",
            )
        }

        val messages = client.messages.value
        assertEquals(3, messages.size)
        assertEquals(listOf("msg2", "msg3", "msg4"), messages.map { it.text })
    }

    @Test
    fun `обрыв соединения планирует переподключение по лестнице`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan")
        sockets.single().listener.onOpen()
        assertTrue(client.connected.value)

        sockets.single().listener.onClosed()
        assertFalse(client.connected.value)
        assertEquals(1, sockets.size)

        advanceUntilIdle()
        assertEquals("вторая попытка после задержки", 2, sockets.size)
    }

    @Test
    fun `stop отменяет запланированное переподключение`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan")
        sockets.single().listener.onOpen()
        sockets.single().listener.onClosed()

        client.stop()
        advanceUntilIdle()

        assertEquals("стоп — новых попыток нет", 1, sockets.size)
    }

    @Test
    fun `start с тем же каналом повторно — не пересоздаёт сокет`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan")
        sockets.single().listener.onOpen()

        client.start("chan")

        assertEquals(1, sockets.size)
    }

    @Test
    fun `start с другим каналом переоткрывает сокет и чистит историю`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan1")
        sockets.single().listener.onOpen()
        sockets.single().listener.onLine(
            ":a!a@a.tmi.twitch.tv PRIVMSG #chan1 :old message",
        )
        assertEquals(1, client.messages.value.size)

        client.start("chan2")

        assertEquals(2, sockets.size)
        assertTrue("прежний сокет закрыт", sockets[0].closed)
        assertTrue("история очищена под новый канал", client.messages.value.isEmpty())
    }

    @Test
    fun `опоздавший onClosed от старого сокета не планирует лишний реконнект`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("chan1")
        sockets.single().listener.onOpen()

        client.start("chan2")
        sockets[1].listener.onOpen()
        assertTrue(client.connected.value)

        // Реальный сокет закрывается асинхронно — колбэк старого соединения
        // может прийти уже после того, как новое успело открыться.
        sockets[0].listener.onClosed()
        advanceUntilIdle()

        assertTrue("новое соединение не тронуто опоздавшим колбэком", client.connected.value)
        assertEquals("лишнего реконнекта не случилось", 2, sockets.size)
    }
}
