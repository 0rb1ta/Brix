package app.brix.streaming.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTokenProvider(private val token: String? = "tok") : VkTokenProvider {
    var calls = 0
    override suspend fun fetchToken(clientId: String, clientSecret: String): String? {
        calls++
        return token
    }
}

private class FakeMessagesFetcher(
    private val respond: (callIndex: Int) -> VkMessagesResult,
) : VkMessagesFetcher {
    var calls = 0
    override suspend fun fetchMessages(accessToken: String, channelUrl: String, limit: Int): VkMessagesResult {
        val result = respond(calls)
        calls++
        return result
    }
}

private fun vkMessage(id: Long, text: String = "msg$id", createdAt: Long = id) =
    VkChatMessage(id = id, author = "u$id", colorHex = null, text = text, createdAtSec = createdAt)

/** Poll loop is `while (isActive) { poll(); delay(interval) }` — infinite by
 *  design (VK has no connection to drop, so no ladder to exhaust) — same
 *  advanceUntilIdle() trap as Kick's infinite-retry ladder: every assertion
 *  here uses bounded time advances instead. */
@OptIn(ExperimentalCoroutinesApi::class)
class VkChatClientTest {

    @Test
    fun `start получает токен и опрашивает сообщения`() = runTest {
        val scope = TestScope(testScheduler)
        val tokens = FakeTokenProvider()
        val fetcher = FakeMessagesFetcher { VkMessagesResult.Success(listOf(vkMessage(1))) }
        val client = VkChatClient(scope, tokens, fetcher, pollIntervalMs = 1_000)

        client.start("chan", "id", "secret")
        runCurrent()

        assertEquals(1, tokens.calls)
        assertEquals(1, fetcher.calls)
        assertTrue(client.connected.value)
        assertEquals(1, client.messages.value.size)
        assertEquals(ChatPlatform.VK, client.messages.value.single().platform)
        client.stop()
    }

    @Test
    fun `токен переиспользуется между опросами, пока не 401`() = runTest {
        val scope = TestScope(testScheduler)
        val tokens = FakeTokenProvider()
        val fetcher = FakeMessagesFetcher { VkMessagesResult.Success(emptyList()) }
        val client = VkChatClient(scope, tokens, fetcher, pollIntervalMs = 1_000)

        client.start("chan", "id", "secret")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("токен запрошен один раз на все опросы", 1, tokens.calls)
        assertEquals(3, fetcher.calls)
        client.stop()
    }

    @Test
    fun `401 обновляет токен и повторяет запрос в том же тике`() = runTest {
        val scope = TestScope(testScheduler)
        val tokens = FakeTokenProvider()
        val fetcher = FakeMessagesFetcher { call ->
            if (call == 0) VkMessagesResult.Unauthorized else VkMessagesResult.Success(listOf(vkMessage(1)))
        }
        val client = VkChatClient(scope, tokens, fetcher, pollIntervalMs = 1_000)

        client.start("chan", "id", "secret")
        runCurrent()

        assertEquals("первый токен + обновление после 401", 2, tokens.calls)
        assertEquals(2, fetcher.calls)
        assertTrue(client.connected.value)
        assertEquals(1, client.messages.value.size)
        client.stop()
    }

    @Test
    fun `повторяющиеся сообщения между опросами не дублируются`() = runTest {
        val scope = TestScope(testScheduler)
        val tokens = FakeTokenProvider()
        val fetcher = FakeMessagesFetcher { call ->
            // Второй опрос возвращает то же сообщение id=1 плюс новое id=2 —
            // ровно то, что реально отдаёт GET /v1/chat/messages между
            // соседними опросами (окно "последние N", не "новые с X").
            if (call == 0) {
                VkMessagesResult.Success(listOf(vkMessage(1)))
            } else {
                VkMessagesResult.Success(listOf(vkMessage(1), vkMessage(2)))
            }
        }
        val client = VkChatClient(scope, tokens, fetcher, pollIntervalMs = 1_000)

        client.start("chan", "id", "secret")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, client.messages.value.size)
        assertEquals(setOf("msg1", "msg2"), client.messages.value.map { it.text }.toSet())
        client.stop()
    }

    @Test
    fun `неудача не роняет клиент — connected=false, опрос продолжается`() = runTest {
        val scope = TestScope(testScheduler)
        val tokens = FakeTokenProvider()
        val fetcher = FakeMessagesFetcher { VkMessagesResult.Failure }
        val client = VkChatClient(scope, tokens, fetcher, pollIntervalMs = 1_000)

        client.start("chan", "id", "secret")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(client.connected.value)
        assertEquals(2, fetcher.calls)
        client.stop()
    }

    @Test
    fun `stop останавливает опрос`() = runTest {
        val scope = TestScope(testScheduler)
        val tokens = FakeTokenProvider()
        val fetcher = FakeMessagesFetcher { VkMessagesResult.Success(emptyList()) }
        val client = VkChatClient(scope, tokens, fetcher, pollIntervalMs = 1_000)

        client.start("chan", "id", "secret")
        runCurrent()
        client.stop()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("после stop новых опросов быть не должно", 1, fetcher.calls)
        assertFalse(client.connected.value)
    }

    @Test
    fun `без client id или секрета не запускается`() = runTest {
        val scope = TestScope(testScheduler)
        val tokens = FakeTokenProvider()
        val fetcher = FakeMessagesFetcher { VkMessagesResult.Success(emptyList()) }
        val client = VkChatClient(scope, tokens, fetcher, pollIntervalMs = 1_000)

        client.start("chan", "", "")
        runCurrent()

        assertEquals(0, tokens.calls)
        assertEquals(0, fetcher.calls)
    }
}
