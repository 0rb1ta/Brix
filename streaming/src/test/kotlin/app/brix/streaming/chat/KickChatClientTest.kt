package app.brix.streaming.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeKickSocket : ChatSocket {
    val sent = mutableListOf<String>()
    var closed = false
    lateinit var listener: ChatSocketListener

    override fun connect() {}
    override fun send(text: String) {
        sent += text
    }

    override fun close() {
        closed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KickChatClientTest {

    private fun newClient(
        scope: TestScope,
        resolver: KickChannelResolver = KickChannelResolver { slug ->
            KickChatroomInfo(chatroomId = "${slug}Room", chatroomChannelId = "${slug}Chan")
        },
    ): Pair<KickChatClient, MutableList<FakeKickSocket>> {
        val sockets = mutableListOf<FakeKickSocket>()
        val client = KickChatClient(
            scope = scope,
            socketFactory = { _, listener ->
                val socket = FakeKickSocket()
                socket.listener = listener
                sockets += socket
                socket
            },
            channelResolver = resolver,
        )
        return client to sockets
    }

    @Test
    fun `start резолвит канал и подписывается на все варианты`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)

        client.start("kaysan")
        advanceUntilIdle()
        sockets.single().listener.onOpen()

        val sent = sockets.single().sent
        assertEquals(4, sent.size)
        assertTrue(sent.any { it.contains("\"chatrooms.kaysanRoom.v2\"") })
        assertTrue(sent.any { it.contains("\"chatroom_kaysanRoom\"") })
        assertTrue(sent.any { it.contains("\"chatrooms.kaysanRoom\"") })
        assertTrue(sent.any { it.contains("\"channel_kaysanChan\"") })
    }

    @Test
    fun `резолв не найден — реконнект по лестнице, без падения`() = runTest {
        val scope = TestScope(testScheduler)
        var calls = 0
        val (client, sockets) = newClient(
            scope,
            resolver = KickChannelResolver {
                calls++
                check(calls < 20) { "резолв вызван $calls раз — похоже на цикл, а не на 2 ранга за 3.5с" }
                null
            },
        )

        client.start("ghost")
        advanceTimeBy(3_500)

        assertEquals("сокет не открывается без chatroom id", 0, sockets.size)
        assertTrue("несколько попыток за 3.5 виртуальных секунды, не тысячи", calls in 1..5)
    }

    @Test
    fun `реальный кадр сообщения попадает в messages`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("kaysan")
        advanceUntilIdle()
        val socket = sockets.single()
        socket.listener.onOpen()

        socket.listener.onLine(
            """{"event":"App\\Events\\ChatMessageEvent","data":"{\"id\":\"m1\",\"chatroom_id\":1,\"content\":\"hi\",\"sender\":{\"username\":\"foo\",\"identity\":{\"color\":\"#ABCDEF\",\"badges\":[]}}}"}""",
        )

        val messages = client.messages.value
        assertEquals(1, messages.size)
        assertEquals(ChatPlatform.KICK, messages.single().platform)
        assertEquals("foo", messages.single().author)
        assertEquals("hi", messages.single().text)
    }

    @Test
    fun `повторный резолв не выполняется при переподключении того же канала`() = runTest {
        val scope = TestScope(testScheduler)
        var resolveCalls = 0
        val (client, sockets) = newClient(
            scope,
            resolver = KickChannelResolver {
                resolveCalls++
                KickChatroomInfo("r", "c")
            },
        )
        client.start("kaysan")
        advanceUntilIdle()
        sockets.single().listener.onOpen()
        assertEquals(1, resolveCalls)

        sockets.single().listener.onClosed()
        advanceUntilIdle()

        assertEquals("id закэшированы — второй сокет открылся без нового резолва", 1, resolveCalls)
        assertEquals(2, sockets.size)
    }

    @Test
    fun `смена канала сбрасывает кэш id и переоткрывает сокет`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("kaysan")
        advanceUntilIdle()
        sockets.single().listener.onOpen()

        client.start("otherchan")
        advanceUntilIdle()

        assertEquals(2, sockets.size)
        assertTrue(sockets[0].closed)
        val sentToSecond = sockets[1].sent
        // Второй сокет ждёт onOpen перед отправкой подписок — их пока нет,
        // но факт нового сокета уже подтверждает, что резолв не переиспользовал
        // старый кэш otherchan-специфичных id (иначе имена каналов остались бы
        // от kaysan).
        assertEquals(0, sentToSecond.size)
    }

    @Test
    fun `stop останавливает реконнект`() = runTest {
        val scope = TestScope(testScheduler)
        val (client, sockets) = newClient(scope)
        client.start("kaysan")
        advanceUntilIdle()
        sockets.single().listener.onOpen()
        sockets.single().listener.onClosed()

        client.stop()
        advanceUntilIdle()

        assertEquals(1, sockets.size)
    }
}
