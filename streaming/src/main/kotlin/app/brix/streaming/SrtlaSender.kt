package app.brix.streaming

import app.brix.bonding.SrtSender
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.base.BaseSender
import com.pedro.common.frame.MediaFrame
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.srt.mpeg2ts.MpegTsPacketizer
import com.pedro.srt.mpeg2ts.MpegType
import com.pedro.srt.mpeg2ts.packets.AacPacket
import com.pedro.srt.mpeg2ts.packets.BasePacket
import com.pedro.srt.mpeg2ts.packets.H264Packet
import com.pedro.srt.mpeg2ts.packets.H265Packet
import com.pedro.srt.mpeg2ts.psi.Psi
import com.pedro.srt.mpeg2ts.psi.PsiManager
import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import com.pedro.srt.srt.packets.data.PacketPosition
import com.pedro.srt.utils.chunkPackets
import com.pedro.srt.utils.toCodec
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer

class SrtlaSender(
    connectChecker: ConnectChecker,
    private val srtSender: SrtSender,
) : BaseSender(connectChecker, "SrtlaSender") {

    private var service = Mpeg2TsService()
    private val psiManager = PsiManager(service).apply {
        upgradePatVersion()
        upgradeSdtVersion()
    }
    // Conservative cellular MTU for the whole IP packet: keep the UDP datagram
    // (SRT header 16 + TS payload) under it so frames never IP-fragment on
    // ~1400-byte cellular paths (H1).
    private val limitSize = 1400 - 20 - 8 - 16
    private val mpegTsPacketizer = MpegTsPacketizer(psiManager)
    private var audioPacket: BasePacket = AacPacket(limitSize, psiManager)
    // 2.8.1 разделил общий H26XPacket на два класса — пакет выбирается по кодеку
    // при каждом setVideoInfo, как в их собственном SrtSender.
    private var videoPacket: BasePacket = H264Packet(limitSize, psiManager)

    private var videoCodec = VideoCodec.H264

    fun setVideoCodec(codec: VideoCodec) {
        videoCodec = codec
    }

    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        videoPacket = when (videoCodec) {
            VideoCodec.H265 -> {
                requireNotNull(vps) { "vps can't be null with h265" }
                requireNotNull(pps) { "pps can't be null with h265" }
                (videoPacket as? H265Packet ?: H265Packet(limitSize, psiManager)).apply {
                    setLimitSize(limitSize)
                    sendVideoInfo(sps, pps, vps)
                }
            }
            else -> {
                requireNotNull(pps) { "pps can't be null with h264" }
                (videoPacket as? H264Packet ?: H264Packet(limitSize, psiManager)).apply {
                    setLimitSize(limitSize)
                    sendVideoInfo(sps, pps)
                }
            }
        }
    }

    override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
        audioPacket = AacPacket(limitSize, psiManager).apply {
            setLimitSize(limitSize)
            sendAudioInfo(sampleRate, isStereo, AudioCodec.AAC)
        }
    }

    private fun setTrackConfig(videoEnabled: Boolean, audioEnabled: Boolean) {
        service.clear()
        if (audioEnabled) service.addTrack(AudioCodec.AAC.toCodec())
        if (videoEnabled) service.addTrack(videoCodec.toCodec())
        service.generatePmt()
        psiManager.updateService(service)
    }

    override suspend fun onRun() {
        val chunkSize = limitSize / MpegTsPacketizer.packetSize
        audioPacket.setLimitSize(limitSize)
        videoPacket.setLimitSize(limitSize)

        setTrackConfig(videoEnabled = true, audioEnabled = true)
        val psiList = mutableListOf<Psi>(psiManager.getPat())
        psiManager.getPmt()?.let { psiList.add(it) }
        psiList.add(psiManager.getSdt())
        val psiPacketsConfig = mpegTsPacketizer.write(psiList).chunkPackets(chunkSize).map { buffer ->
            MpegTsPacket(buffer, MpegType.PSI, PacketPosition.SINGLE, isKey = false)
        }
        sendPackets(psiPacketsConfig)

        while (scope.isActive && running) {
            // consumeFrame, а не queue.take(): очередь в 2.8.1 закрыта, а буфер кадра
            // после обработки возвращается в их пул — отсюда и экономия на мусоре.
            consumeFrame { mediaFrame ->
                getMpegTsPackets(mediaFrame) { mpegTsPackets ->
                    val isKey = mpegTsPackets[0].isKey
                    val psiPackets = psiManager.checkSendInfo(isKey, mpegTsPacketizer, chunkSize)
                    sendPackets(psiPackets)
                    sendPackets(mpegTsPackets)
                }
            }
        }
    }

    private fun sendPackets(packets: List<MpegTsPacket>) {
        if (packets.isEmpty()) return
        val now = System.nanoTime() / 1000
        // Пока нет соединения, enqueue всё равно выбросит пакет — а мы уже
        // выделили и скопировали его. Конвейер при переподключении намеренно
        // не останавливается, так что в тоннеле это минуты по ~2 МБ/с мусора
        // (аудит 23.09). send() зовём всё равно: он же переотправляет
        // CONCLUSION и следит за таймаутом рукопожатия.
        if (!srtSender.isConnectedOrProvisional()) {
            srtSender.send(now)
            return
        }
        var totalBytes = 0L
        packets.forEach { mpegTsPacket ->
            srtSender.enqueue(srtSender.newDataPacket(mpegTsPacket.buffer), now)
            totalBytes += mpegTsPacket.buffer.size
        }
        bytesSend.addAndGet(totalBytes)
        bytesSendPerSecond.addAndGet(totalBytes)
        srtSender.send(now)
    }

    private suspend fun getMpegTsPackets(
        mediaFrame: MediaFrame,
        callback: suspend (List<MpegTsPacket>) -> Unit,
    ) {
        when (mediaFrame.type) {
            MediaFrame.Type.VIDEO -> videoPacket.createAndSendPacket(mediaFrame) { callback(it) }
            MediaFrame.Type.AUDIO -> audioPacket.createAndSendPacket(mediaFrame) { callback(it) }
        }
    }

    override suspend fun stopImp(clear: Boolean) {
        psiManager.reset()
        if (clear) service = Mpeg2TsService()
        mpegTsPacketizer.reset()
        audioPacket.reset(clear)
        videoPacket.reset(clear)
    }
}
