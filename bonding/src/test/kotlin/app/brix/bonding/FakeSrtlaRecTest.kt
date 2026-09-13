package app.brix.bonding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests against an in-JVM stand-in for the belabox receiver.
 * These cover the classes of field failures that pure unit tests missed for
 * days: registration flows against a live UDP peer, one-way-deaf first ports,
 * and rapid Stop->Start cycles.
 */
class FakeSrtlaRecTest {

    private fun newClient(onReady: () -> Unit): SrtlaClient {
        val client = SrtlaClient()
        client.onReady = onReady
        return client
    }

    @Test
    fun `full registration flow succeeds against fake receiver`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)

            assertTrue("client did not reach RUNNING within 5s", ready.await(5, TimeUnit.SECONDS))
            assertTrue(client.isRunning())

            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `one-way-deaf first port recovers via socket bounce`() {
        // Swallow REG3 for the first 6 attempts (2 retries x 2s patience +
        // margin): the client must bounce to a fresh source port and register.
        val fake = FakeSrtlaRec(dropReg3Count = 6)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            client.addConnection("cellular", 1f)
            client.start("127.0.0.1", fake.port)

            assertTrue(
                "client did not recover from a deaf port within 20s",
                ready.await(20, TimeUnit.SECONDS),
            )

            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `per-link rtt is measured from the keepalive round trip`() {
        // Полевой прогон 31.08: rtt по каналам принимал ровно два значения —
        // 0 и 10000 (потолок coerceIn), настоящего замера не было ни разу, хотя
        // сквозной srtrtt считался верно. Причина, почему это никто не поймал:
        // FakeSrtlaRec игнорировал keepalive, поэтому путь замера не проходил
        // ни один тест. Приёмник теперь отвечает как настоящий srtla_rec —
        // возвращает пакет как есть, — и придерживает эхо на 80 мс, чтобы у
        // round trip была измеримая длительность.
        val echoDelayMs = 80L
        val fake = FakeSrtlaRec(keepaliveEchoDelayMs = echoDelayMs)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue("client did not reach RUNNING within 5s", ready.await(5, TimeUnit.SECONDS))

            // Keepalive уходит раз в секунду; ждём несколько оборотов.
            val deadline = System.currentTimeMillis() + 8000
            var rtt = 0
            while (System.currentTimeMillis() < deadline) {
                rtt = client.connectionStats().first().rtt
                if (rtt > 0) break
                Thread.sleep(100)
            }

            assertTrue(
                "приёмник не получил ни одного keepalive",
                fake.keepalivesEchoed > 0,
            )
            assertTrue(
                "rtt так и не измерен: $rtt мс (эхо задержано на $echoDelayMs мс)",
                rtt > 0,
            )
            assertTrue(
                "rtt $rtt мс не похож на задержку эха $echoDelayMs мс",
                rtt in (echoDelayMs / 2).toInt()..(echoDelayMs.toInt() * 6),
            )

            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `keepalive invented by the receiver does not poison per-link rtt`() {
        // Ровно то, что случилось в поле 31.08: во время разрыва канала приёмник
        // начал слать свои собственные keepalive, клиент вычел из их payload
        // свою метку времени, получил мусор и упёрся в потолок coerceIn — rtt
        // стал 10000 и залип навсегда, потому что сбрасывать его было нечем.
        val fake = FakeSrtlaRec(originateKeepalives = true)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue("client did not reach RUNNING within 5s", ready.await(5, TimeUnit.SECONDS))

            // Несколько оборотов keepalive, чтобы чужие пакеты точно дошли.
            Thread.sleep(3500)
            val rtt = client.connectionStats().first().rtt

            assertEquals(
                "чужой keepalive не должен превращаться в замер rtt, а он дал $rtt мс",
                0,
                rtt,
            )

            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `link that answers but never delivers leaves the rotation`() {
        // Форма отказа из поля 31.08: разрыв был только на передачу. Сервер
        // продолжал отвечать, latestReceivedTime оставался свежим, сторож по
        // входящему трафику не срабатывал — и линк числился живым 24 секунды,
        // всё это время сливая ~100-300 kbps в никуда. Здесь приёмник так же
        // отвечает на keepalive (вход свежий), но медиа не подтверждает.
        val fake = FakeSrtlaRec(ackData = false)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            val conn = client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue("client did not reach RUNNING within 5s", ready.await(5, TimeUnit.SECONDS))

            // Гоним медиа, чтобы пакеты повисли в полёте неподтверждёнными.
            // Проверяем именно сторож доставки, а не факт переподключения:
            // канал переподключается и по другим причинам, и первая версия
            // этого теста их за него принимала — проходила и без правки.
            val deadline = System.currentTimeMillis() + 10_000
            var sn = 1L
            while (System.currentTimeMillis() < deadline && conn.deliveryWatchdogTrips == 0) {
                client.handleLocalPacket(dataPacket(sn++))
                Thread.sleep(20)
            }

            assertTrue(
                "сторож доставки не сработал ни разу за 10с, хотя канал отвечал " +
                    "и не подтверждал доставку",
                conn.deliveryWatchdogTrips > 0,
            )
            client.stop()
        } finally {
            fake.stop()
        }
    }

    /** Минимальный SRT data-пакет: старший бит нулевой + порядковый номер. */
    private fun dataPacket(sn: Long): ByteArray {
        val p = ByteArray(64)
        p[0] = ((sn shr 24) and 0x7F).toByte()
        p[1] = ((sn shr 16) and 0xFF).toByte()
        p[2] = ((sn shr 8) and 0xFF).toByte()
        p[3] = (sn and 0xFF).toByte()
        return p
    }

    @Test
    fun `periodic checks still run while inbound traffic never pauses`() {
        // Тик раньше наступал только по таймауту сокета, то есть в паузах
        // входящего потока. Во время отказа канала пауз как раз нет: сервер
        // продолжает по нему слать. В поле 31.08 это дало отключение мёртвого
        // канала через 23 секунды при пороге сторожа в 3 — проверять условие
        // было просто некому. Здесь приёмник заливает клиента входящим каждые
        // 10 мс и при этом ничего не подтверждает.
        val fake = FakeSrtlaRec(ackData = false, floodInboundEveryMs = 10)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            val conn = client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue("client did not reach RUNNING within 5s", ready.await(5, TimeUnit.SECONDS))

            val deadline = System.currentTimeMillis() + 10_000
            var sn = 1L
            while (System.currentTimeMillis() < deadline && conn.deliveryWatchdogTrips == 0) {
                client.handleLocalPacket(dataPacket(sn++))
                Thread.sleep(20)
            }

            assertTrue(
                "при непрерывном входящем потоке периодические проверки не выполнились " +
                    "ни разу за 10с",
                conn.deliveryWatchdogTrips > 0,
            )
            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `lost probe reply is retried and registration still completes`() {
        // The pre-group handshake (probe -> REG_NGP -> REG1 -> REG2) had no
        // retry at all: a single lost packet here left the connection parked in
        // SHOULD_SEND_REGISTER_REQUEST, which onTick ignored, so the session
        // hung until SrtlaStream's 15s watchdog tore the whole transport down.
        val fake = FakeSrtlaRec(dropProbeReplies = 1)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            client.addConnection("cellular", 1f)
            client.start("127.0.0.1", fake.port)

            assertTrue(
                "client never retried the probe after a lost REG_NGP",
                ready.await(15, TimeUnit.SECONDS),
            )
            assertTrue(client.isRunning())

            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `lost REG1 reply is retried and registration still completes`() {
        val fake = FakeSrtlaRec(dropReg1Replies = 1)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient { ready.countDown() }
            client.addConnection("cellular", 1f)
            client.start("127.0.0.1", fake.port)

            assertTrue(
                "client never re-sent REG1 after a lost group reply",
                ready.await(15, TimeUnit.SECONDS),
            )
            assertTrue(client.isRunning())

            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `rapid stop-start cycles all reach RUNNING`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            repeat(5) { cycle ->
                fake.reset()
                val ready = CountDownLatch(1)
                val client = newClient { ready.countDown() }
                client.addConnection("wifi", 1f)
                client.start("127.0.0.1", fake.port)

                assertTrue(
                    "cycle $cycle did not reach RUNNING within 5s",
                    ready.await(5, TimeUnit.SECONDS),
                )
                assertTrue(client.isRunning())

                // Mirror SrtlaStreamer.stop(): stop + drop connections.
                client.stop()
                client.clearConnections()
            }
        } finally {
            fake.stop()
        }
    }


    @Test
    fun `rapid stop-start cycles maintain clean state`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            repeat(8) { cycle ->
                fake.reset()
                val ready = CountDownLatch(1)
                val c = SrtlaClient()
                c.onReady = { ready.countDown() }
                c.addConnection("wifi", 1f)
                c.start("127.0.0.1", fake.port)

                assertTrue(
                    "cycle $cycle did not reach RUNNING within 5s",
                    ready.await(5, TimeUnit.SECONDS),
                )

                c.stop()
                c.clearConnections()
            }
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `concurrent reconnect and stop do not corrupt state`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val c = SrtlaClient()
            c.onReady = { ready.countDown() }
            c.addConnection("cellular", 1f)
            c.start("127.0.0.1", fake.port)

            assertTrue("initial registration failed", ready.await(10, TimeUnit.SECONDS))

            // Spawn concurrent stop() + reconnect() on separate threads
            val stopThread = Thread { c.stop() }
            val reconnectThread = Thread {
                try { c.start("127.0.0.1", fake.port) } catch (_: Exception) {}
            }
            stopThread.start()
            reconnectThread.start()
            stopThread.join(5000)
            reconnectThread.join(5000)

            // After concurrent operations, a fresh start must work cleanly
            val ready2 = CountDownLatch(1)
            fake.reset()
            c.clearConnections()
            c.addConnection("wifi", 1f)
            c.start("127.0.0.1", fake.port)

            for (i in 1..40) {
                if (c.isRunning()) { ready2.countDown(); break }
                Thread.sleep(250)
            }
            assertTrue("fresh start after concurrent stop/reconnect failed", ready2.await(10, TimeUnit.SECONDS))

            c.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `duplicate start does not create duplicate connections`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val client = SrtlaClient()
            client.addConnection("wifi", 1f)
            
            // Call start twice — second call must be a no-op for connections
            client.start("127.0.0.1", fake.port)
            val countAfterFirst = client.connectionCount()
            client.start("127.0.0.1", fake.port)
            val countAfterSecond = client.connectionCount()

            assertEquals(
                "duplicate start created extra connections",
                countAfterFirst, countAfterSecond,
            )

            client.stop()
        } finally {
            fake.stop()
        }
    }
}
