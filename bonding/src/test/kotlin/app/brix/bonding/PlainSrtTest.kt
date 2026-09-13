package app.brix.bonding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обычный SRT — режим без SRTLA, который включается схемой `srt://`.
 *
 * Проверяется против поддельного приёмника, который ведёт себя как настоящий
 * `srt-live-transmit` или вход SRT в OBS: пакеты SRTLA молча отбрасывает и
 * отвечает только на рукопожатие SRT. Если клиент попробует зарегистрироваться
 * или пошлёт keepalive SRTLA, ответа не будет — и тест это поймает.
 */
class PlainSrtTest {


    /** Пакет данных SRT с заданным номером. Клиент сам данных не создаёт — их
     *  подаёт поток сверху, поэтому в тестах подаём их руками. */
    private fun dataPacket(sn: Long): ByteArray {
        val p = ByteArray(64)
        writeUInt32(p, 0, sn) // старший бит нулевой = пакет данных
        return p
    }

    private fun newClient(plain: Boolean, onReady: () -> Unit): SrtlaClient {
        val client = SrtlaClient()
        client.useSrtla = !plain
        client.onReady = onReady
        return client
    }

    @Test
    fun `клиент выходит на связь с приёмником, не знающим SRTLA`() {
        val fake = FakeSrtlaRec(plainSrt = true)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)

            assertTrue("не вышел в рабочее состояние за 5 с", ready.await(5, TimeUnit.SECONDS))
            assertTrue(client.isRunning())
            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `в простом режиме служебные пакеты SRTLA не отправляются`() {
        val fake = FakeSrtlaRec(plainSrt = true)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue(ready.await(5, TimeUnit.SECONDS))

            // Данные подаём сами: без них клиент молчит, и тест прошёл бы
            // впустую — «пакетов SRTLA нет» при полном отсутствии трафика.
            repeat(20) { client.handleLocalPacket(dataPacket(it.toLong())) }
            // Больше двух секунд: keepalive SRTLA идёт раз в секунду, так что
            // за это окно он успел бы уйти дважды, если бы режим не работал.
            Thread.sleep(2500)
            assertTrue(
                "приёмник не получил вообще ничего — тест ничего не доказывает",
                fake.receivedPackets().isNotEmpty(),
            )
            client.stop()

            val srtlaPackets = fake.receivedPackets().count { p ->
                Srt.getControlPacketType(p) in setOf(
                    Srtla.PacketType.KEEPALIVE.rawValue,
                    Srtla.PacketType.REG1.rawValue,
                    Srtla.PacketType.REG2.rawValue,
                )
            }
            assertEquals("приёмник получил пакеты SRTLA, хотя не должен", 0, srtlaPackets)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `против настоящего SRTLA-приёмника простой режим не проходит регистрацию`() {
        // Обратная проверка: приёмник SRTLA ждёт REG1, а простой клиент его не
        // шлёт. Клиент при этом считает себя готовым — и это верно, потому что
        // рукопожатие SRT ему отвечают. Смысл теста в том, что регистрации
        // действительно не было.
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            Thread.sleep(500)
            client.stop()

            val reg1 = fake.receivedPackets().count {
                Srt.getControlPacketType(it) == Srtla.PacketType.REG1.rawValue
            }
            assertEquals("REG1 не должен уходить в простом режиме", 0, reg1)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `в простом режиме поток идёт по одному линку, а не по двум`() {
        // SRTLA размазывает пакеты по каналам намеренно: сервер их склеивает.
        // У обычного SRT такой стороны нет, и пакеты со второго сокета он
        // видит как чужие. Полевой прогон 14.09 показал именно это: поток
        // пошёл сразу по Wi-Fi и соте.
        val fake = FakeSrtlaRec(plainSrt = true)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.addConnection("cellular", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            // Второму линку надо дать подняться, иначе тест доказал бы лишь
            // то, что он ещё не готов.
            Thread.sleep(800)
            repeat(50) { client.handleLocalPacket(dataPacket(it.toLong())) }
            Thread.sleep(500)

            // Сервер видит пакеты с разных исходящих портов, если линков больше
            // одного. Для обычного SRT их должно быть ровно столько же, сколько
            // и сессий — одна.
            val ports = fake.receivedSourcePorts()
            assertEquals("поток ушёл по нескольким линкам: $ports", 1, ports.size)
            client.stop()
        } finally {
            fake.stop()
        }
    }
}
