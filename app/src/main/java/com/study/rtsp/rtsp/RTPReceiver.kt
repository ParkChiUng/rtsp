package com.study.rtsp.rtsp

import android.util.Log
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class RTPReceiver(
    private val localPort: Int,
    private val payloadType: Int,
    val listener: RTPReceiverListener?
) {
    companion object {
        private const val TAG = "RTPReceiver"
        private const val RTP_HEADER_SIZE = 12
        private const val MAX_PACKET_SIZE = 65536

        // RTP 헤더 필드
        private const val RTP_VERSION = 2

        // H.264 NAL 유닛 타입
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val NAL_TYPE_IDR = 5
        private const val NAL_TYPE_NON_IDR = 1
        private const val NAL_TYPE_FU_A = 28  // Fragmentation Unit A

        // H.264 Start Code
        private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

        // FU-A 제한값들
        private const val MAX_FRAGMENT_SIZE = 1024 * 1024 // 1MB
        private const val FRAGMENT_TIMEOUT = 5000L // 5초
        private const val FRAGMENT_CLEANUP_INTERVAL = 10000L // 10초

        // 패킷 순서 관련 상수
        private const val MAX_DROPOUT = 3000
        private const val MAX_MISORDER = 100
        private const val SEQ_MOD = 0x10000
    }

    private var udpSocket: DatagramSocket? = null
    private val isReceiving = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // RTP 시퀀스 관리 개선
    private var expectedSequence = -1
    private var lastTimestamp = 0L
    private var maxSeq = 0
    private var badSeq = 0
    private var probation = 0

    // FU-A 프래그먼트 재조립 개선
    private val fragmentBuffer = mutableListOf<ByteArray>()
    private var fuStartReceived = false
    private var currentFragmentTimestamp = -1L
    private var fragmentStartTime = 0L

    // 패킷 통계 개선
    private var packetsReceived = 0
    private var packetsLost = 0
    private var packetsOutOfOrder = 0
    private var duplicatePackets = 0
    private var bytesReceived = 0L
    private var lastStatsUpdate = System.currentTimeMillis()
    private var lastBytesReceived = 0L

    // 지터 계산용
    private var lastArrivalTime = 0L
    private var lastRtpTimestamp = 0L
    private var jitterSum = 0.0
    private var jitterCount = 0

    // TCP Interleaved 모드 지원
    private var isTcpMode = false
    private var actualPort = -1

    interface RTPReceiverListener {
        fun onNALUnitReceived(nalUnit: ByteArray, timestamp: Long)
        fun onSPSReceived(sps: ByteArray)
        fun onPPSReceived(pps: ByteArray)
        fun onError(error: String)
        fun onStatistics(stats: RtpStatistics)
    }

    // 개선된 통계 정보 클래스
    data class RtpStatistics(
        val packetsReceived: Int,
        val packetsLost: Int,
        val packetsOutOfOrder: Int,
        val duplicatePackets: Int,
        val bytesReceived: Long,
        val bitrate: Double,  // bps
        val jitter: Double,   // ms
        val lastUpdate: Long
    )

    /** RTP 패킷 수신 시작 **/
    fun startReceiving() {
        if (isReceiving.get()) {
            Log.w(TAG, "Already receiving")
            return
        }

        scope.launch {
            try {
                // 포트 할당 로직 개선
                udpSocket = if (localPort == 0) {
                    // 시스템 자동 할당
                    DatagramSocket().apply {
                        soTimeout = 5000                    // 5초 타임아웃
                        receiveBufferSize = 65536 * 10      // 640KB 버퍼
                        actualPort = localPort              // 실제 할당된 포트 저장
                        Log.d(TAG, "Auto-assigned port: $actualPort")
                    }
                } else {
                    try {
                        // 지정된 포트 사용 시도
                        DatagramSocket(localPort).apply {
                            soTimeout = 5000
                            receiveBufferSize = 65536 * 10
                            actualPort = localPort
                            Log.d(TAG, "Using fixed port: $localPort")
                        }
                    } catch (e: BindException) {
                        Log.w(TAG, "Port $localPort not available, trying auto-assignment")
                        DatagramSocket().apply {
                            soTimeout = 5000
                            receiveBufferSize = 65536 * 10
                            actualPort = localPort
                            Log.d(TAG, "Auto-assigned port: $actualPort")
                        }
                    }
                }

                actualPort = udpSocket?.localPort ?: -1
                isReceiving.set(true)
                Log.d(TAG, "RTP Receiver started on actual port: $actualPort")

                // 통계 초기화
                initializeStatistics()

                // 패킷 수신 루프
                receiveLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RTP receiver", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("Failed to start RTP receiver: ${e.message}")
                }
            }
        }
    }

    /** 통계 초기화 **/
    private fun initializeStatistics() {
        packetsReceived = 0
        packetsLost = 0
        packetsOutOfOrder = 0
        duplicatePackets = 0
        bytesReceived = 0L
        lastStatsUpdate = System.currentTimeMillis()
        lastBytesReceived = 0L
        jitterSum = 0.0
        jitterCount = 0
        expectedSequence = -1
        maxSeq = 0
        badSeq = 0
        probation = 0
    }

    /** 패킷 수신 루프  **/
    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        var lastLogTime = System.currentTimeMillis()
        var lastCleanupTime = System.currentTimeMillis()
        var consecutiveTimeouts = 0

        while (isReceiving.get()) {
            try {
                val currentTime = System.currentTimeMillis()

                // 주기적으로 수신 상태 로그 (5초마다)
                if (currentTime - lastLogTime > 5000) {
                    Log.d(TAG, "RTP Receiver still running, packets received: $packetsReceived")
                    if (packetsReceived == 0 && consecutiveTimeouts > 5) {
                        Log.w(TAG, "No packets received for ${consecutiveTimeouts * 5} seconds")
                        withContext(Dispatchers.Main) {
                            listener?.onError("No RTP data received - possible NAT/firewall blocking UDP")
                        }
                    }
                    lastLogTime = currentTime
                    consecutiveTimeouts++
                }

                // 주기적으로 오래된 프래그먼트 정리 (10초마다)
                if (currentTime - lastCleanupTime > FRAGMENT_CLEANUP_INTERVAL) {
                    cleanupOldFragments()
                    lastCleanupTime = currentTime
                }

                // UDP 패킷 수신
                udpSocket?.receive(packet)

                consecutiveTimeouts = 0 // 패킷 수신 시 카운터 리셋
                Log.d(TAG, "RTP packet received: ${packet.length} bytes from ${packet.address}:${packet.port}")

                // 바이트 통계 업데이트
                bytesReceived += packet.length

                // RTP 패킷 처리
                processRTPPacket(packet.data, packet.length)

            } catch (e: SocketTimeoutException) {
                // 타임아웃은 정상 - 계속 수신 시도
                continue
            } catch (e: Exception) {
                if (isReceiving.get()) {
                    Log.e(TAG, "Error receiving RTP packet", e)
                    delay(100)
                }
            }
        }

        Log.d(TAG, "RTP receive loop ended")
    }

    // TCP Interleaved 모드에서 RTP 데이터 처리 개선
    fun processTcpRtpData(data: ByteArray) {
        scope.launch {
            try {
                Log.d(TAG, "Processing TCP RTP data: ${data.size} bytes")
                isTcpMode = true
                bytesReceived += data.size
                processRTPPacket(data, data.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing TCP RTP data", e)
            }
        }
    }

    private suspend fun processRTPPacket(data: ByteArray, length: Int) {
        if (length < RTP_HEADER_SIZE) {
            Log.w(TAG, "Packet too short: $length bytes")
            return
        }

        try {
            // RTP 헤더 파싱
            val rtpHeader = parseRTPHeader(data)

            // 페이로드 타입 확인
            if (rtpHeader.payloadType != payloadType) {
                Log.d(TAG, "Ignoring packet with payload type: ${rtpHeader.payloadType} (expected: $payloadType)")
                return
            }

            // 지터 계산
            calculateJitter(rtpHeader.timestamp)

            // 시퀀스 번호 확인 및 손실 검출 개선
            val sequenceResult = checkSequenceImproved(rtpHeader.sequenceNumber)
            when (sequenceResult) {
                SequenceResult.VALID -> {
                    packetsReceived++
                    Log.d(TAG, "Valid RTP packet #$packetsReceived, seq=${rtpHeader.sequenceNumber}, timestamp=${rtpHeader.timestamp}")
                }
                SequenceResult.DUPLICATE -> {
                    duplicatePackets++
                    Log.w(TAG, "Duplicate packet: seq=${rtpHeader.sequenceNumber}")
                    return
                }
                SequenceResult.OUT_OF_ORDER -> {
                    packetsOutOfOrder++
                    packetsReceived++
                    Log.w(TAG, "Out of order packet: seq=${rtpHeader.sequenceNumber}")
                }
                SequenceResult.LOST -> {
                    packetsReceived++
                    Log.w(TAG, "Packets lost before seq=${rtpHeader.sequenceNumber}")
                }
            }

            // RTP 페이로드 추출 (H.264)
            val payloadOffset = RTP_HEADER_SIZE + (rtpHeader.csrcCount * 4)
            val payloadSize = length - payloadOffset

            if (payloadSize <= 0) {
                Log.w(TAG, "No payload in RTP packet")
                return
            }

            val payload = ByteArray(payloadSize)
            System.arraycopy(data, payloadOffset, payload, 0, payloadSize)

            // H.264 NAL 유닛 처리
            processH264Payload(payload, rtpHeader.timestamp)

            // 통계 업데이트 (10패킷마다)
            if (packetsReceived % 10 == 0) {
                updateAndSendStatistics()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing RTP packet", e)
        }
    }

    private fun parseRTPHeader(data: ByteArray): RTPHeader {
        val buffer = ByteBuffer.wrap(data)

        // 첫 번째 바이트: V(2) + P(1) + X(1) + CC(4)
        val firstByte = buffer.get().toInt() and 0xFF
        val version = (firstByte shr 6) and 0x03
        val padding = (firstByte shr 5) and 0x01
        val extension = (firstByte shr 4) and 0x01
        val csrcCount = firstByte and 0x0F

        // 두 번째 바이트: M(1) + PT(7)
        val secondByte = buffer.get().toInt() and 0xFF
        val marker = (secondByte shr 7) and 0x01
        val payloadType = secondByte and 0x7F

        // 시퀀스 번호 (16비트)
        val sequenceNumber = buffer.short.toInt() and 0xFFFF

        // 타임스탬프 (32비트)
        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL

        // SSRC (32비트)
        val ssrc = buffer.int.toLong() and 0xFFFFFFFFL

        return RTPHeader(
            version = version,
            padding = padding == 1,
            extension = extension == 1,
            csrcCount = csrcCount,
            marker = marker == 1,
            payloadType = payloadType,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            ssrc = ssrc
        )
    }

    // 개선된 시퀀스 번호 체크
    private enum class SequenceResult {
        VALID, DUPLICATE, OUT_OF_ORDER, LOST
    }

    private fun checkSequenceImproved(sequenceNumber: Int): SequenceResult {
        if (expectedSequence == -1) {
            // 첫 번째 패킷
            expectedSequence = sequenceNumber
            maxSeq = sequenceNumber
            return SequenceResult.VALID
        }

        val delta = sequenceNumber - expectedSequence

        return when {
            delta == 0 -> {
                // 예상한 패킷
                expectedSequence = (expectedSequence + 1) and 0xFFFF
                if (sequenceNumber > maxSeq) {
                    maxSeq = sequenceNumber
                }
                SequenceResult.VALID
            }
            delta > 0 && delta < MAX_DROPOUT -> {
                // 패킷 손실 발생
                val lost = delta
                packetsLost += lost
                expectedSequence = (sequenceNumber + 1) and 0xFFFF
                maxSeq = sequenceNumber
                Log.w(TAG, "Lost $lost packets (expected: $expectedSequence, received: $sequenceNumber)")
                SequenceResult.LOST
            }
            delta < 0 && (-delta) < MAX_MISORDER -> {
                // 순서 뒤바뀜 (지연 도착)
                SequenceResult.OUT_OF_ORDER
            }
            delta < 0 && sequenceNumber == expectedSequence - 1 -> {
                // 중복 패킷
                SequenceResult.DUPLICATE
            }
            else -> {
                // 시퀀스 넘버 리셋 또는 큰 점프
                Log.w(TAG, "Sequence number reset or big jump: $sequenceNumber")
                expectedSequence = (sequenceNumber + 1) and 0xFFFF
                maxSeq = sequenceNumber
                SequenceResult.VALID
            }
        }
    }

    private fun calculateJitter(rtpTimestamp: Long) {
        val currentTime = System.currentTimeMillis()

        if (lastArrivalTime != 0L && lastRtpTimestamp != 0L) {
            val arrivalDiff = currentTime - lastArrivalTime
            val timestampDiff = rtpTimestamp - lastRtpTimestamp

            // RTP 타임스탬프를 ms로 변환 (90kHz 클록 가정)
            val timestampDiffMs = timestampDiff / 90.0
            val jitterSample = kotlin.math.abs(arrivalDiff - timestampDiffMs)

            jitterSum += jitterSample
            jitterCount++
        }

        lastArrivalTime = currentTime
        lastRtpTimestamp = rtpTimestamp
    }

    private suspend fun processH264Payload(payload: ByteArray, timestamp: Long) {
        if (payload.isEmpty()) return

        val nalHeader = payload[0].toInt() and 0xFF
        val nalType = nalHeader and 0x1F

        Log.d(TAG, "Processing H.264 payload, NAL type: $nalType, size: ${payload.size}")

        when (nalType) {
            NAL_TYPE_SPS -> {
                Log.d(TAG, "Received SPS")
                val nalUnit = createNALUnit(payload)
                withContext(Dispatchers.Main) {
                    listener?.onSPSReceived(nalUnit)
                    listener?.onNALUnitReceived(nalUnit, timestamp)
                }
            }

            NAL_TYPE_PPS -> {
                Log.d(TAG, "Received PPS")
                val nalUnit = createNALUnit(payload)
                withContext(Dispatchers.Main) {
                    listener?.onPPSReceived(nalUnit)
                    listener?.onNALUnitReceived(nalUnit, timestamp)
                }
            }

            NAL_TYPE_IDR, NAL_TYPE_NON_IDR -> {
                Log.d(TAG, "Received ${if (nalType == NAL_TYPE_IDR) "IDR" else "Non-IDR"} frame")
                val nalUnit = createNALUnit(payload)
                withContext(Dispatchers.Main) {
                    listener?.onNALUnitReceived(nalUnit, timestamp)
                }
            }

            NAL_TYPE_FU_A -> {
                // Fragmentation Unit A 처리 개선
                processFUAImproved(payload, timestamp)
            }

            else -> {
                Log.d(TAG, "Received other NAL type: $nalType")
                val nalUnit = createNALUnit(payload)
                withContext(Dispatchers.Main) {
                    listener?.onNALUnitReceived(nalUnit, timestamp)
                }
            }
        }
    }

    // 개선된 FU-A 처리
    private suspend fun processFUAImproved(payload: ByteArray, timestamp: Long) {
        if (payload.size < 2) {
            Log.w(TAG, "FU-A payload too short")
            return
        }

        val fuIndicator = payload[0].toInt() and 0xFF
        val fuHeader = payload[1].toInt() and 0xFF

        val start = (fuHeader and 0x80) != 0  // S bit
        val end = (fuHeader and 0x40) != 0    // E bit
        val nalType = fuHeader and 0x1F

        Log.d(TAG, "FU-A: start=$start, end=$end, nalType=$nalType, size=${payload.size}")

        // 새로운 타임스탬프면 기존 프래그먼트 폐기
        if (currentFragmentTimestamp != -1L && currentFragmentTimestamp != timestamp) {
            Log.w(TAG, "Timestamp changed during fragmentation, discarding ${fragmentBuffer.size} fragments")
            fragmentBuffer.clear()
            fuStartReceived = false
        }

        if (start) {
            // 새로운 프래그먼트 시작
            fragmentBuffer.clear()
            fuStartReceived = true
            currentFragmentTimestamp = timestamp
            fragmentStartTime = System.currentTimeMillis()

            // 첫 번째 프래그먼트: NAL 헤더 재구성
            val nalHeader = (fuIndicator and 0xE0) or nalType
            val fragment = ByteArray(payload.size - 1)
            fragment[0] = nalHeader.toByte()
            System.arraycopy(payload, 2, fragment, 1, payload.size - 2)
            fragmentBuffer.add(fragment)

        } else if (fuStartReceived) {
            // 프래그먼트 크기 제한 체크
            val currentSize = fragmentBuffer.sumOf { it.size }
            if (currentSize > MAX_FRAGMENT_SIZE) {
                Log.w(TAG, "Fragment size exceeded limit ($currentSize > $MAX_FRAGMENT_SIZE), discarding")
                fragmentBuffer.clear()
                fuStartReceived = false
                return
            }

            // 타임아웃 체크
            if (System.currentTimeMillis() - fragmentStartTime > FRAGMENT_TIMEOUT) {
                Log.w(TAG, "Fragment assembly timeout, discarding ${fragmentBuffer.size} fragments")
                fragmentBuffer.clear()
                fuStartReceived = false
                return
            }

            // 중간 또는 마지막 프래그먼트
            val fragment = ByteArray(payload.size - 2)
            System.arraycopy(payload, 2, fragment, 0, payload.size - 2)
            fragmentBuffer.add(fragment)
        }

        if (end && fuStartReceived) {
            // 프래그먼트 재조립 완료
            val totalSize = fragmentBuffer.sumOf { it.size }
            val completeNAL = ByteArray(totalSize)
            var offset = 0

            for (fragment in fragmentBuffer) {
                System.arraycopy(fragment, 0, completeNAL, offset, fragment.size)
                offset += fragment.size
            }

            Log.d(TAG, "FU-A reassembly complete, total size: $totalSize")

            // 완성된 NAL 유닛 처리
            val nalType = completeNAL[0].toInt() and 0x1F
            when (nalType) {
                NAL_TYPE_SPS -> {
                    withContext(Dispatchers.Main) {
                        listener?.onSPSReceived(createNALUnit(completeNAL))
                    }
                }
                NAL_TYPE_PPS -> {
                    withContext(Dispatchers.Main) {
                        listener?.onPPSReceived(createNALUnit(completeNAL))
                    }
                }
            }

            val nalUnit = createNALUnit(completeNAL)
            withContext(Dispatchers.Main) {
                listener?.onNALUnitReceived(nalUnit, timestamp)
            }

            // 프래그먼트 버퍼 초기화
            fragmentBuffer.clear()
            fuStartReceived = false
            currentFragmentTimestamp = -1L
        }
    }

    private fun createNALUnit(payload: ByteArray): ByteArray {
        // Annex-B 형식: Start Code + NAL Unit
        val nalUnit = ByteArray(START_CODE.size + payload.size)
        System.arraycopy(START_CODE, 0, nalUnit, 0, START_CODE.size)
        System.arraycopy(payload, 0, nalUnit, START_CODE.size, payload.size)
        return nalUnit
    }

    // 오래된 프래그먼트 정리
    private fun cleanupOldFragments() {
        if (fragmentBuffer.isNotEmpty() &&
            System.currentTimeMillis() - fragmentStartTime > FRAGMENT_CLEANUP_INTERVAL) {
            Log.w(TAG, "Cleaning up old fragments (${fragmentBuffer.size} fragments)")
            fragmentBuffer.clear()
            fuStartReceived = false
            currentFragmentTimestamp = -1L
        }
    }

    // 개선된 통계 업데이트
    private suspend fun updateAndSendStatistics() {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastStatsUpdate

        if (timeDiff > 0) {
            val bytesDiff = bytesReceived - lastBytesReceived
            val bitrate = (bytesDiff * 8.0 * 1000.0) / timeDiff // bps

            val jitter = if (jitterCount > 0) jitterSum / jitterCount else 0.0

            val stats = RtpStatistics(
                packetsReceived = packetsReceived,
                packetsLost = packetsLost,
                packetsOutOfOrder = packetsOutOfOrder,
                duplicatePackets = duplicatePackets,
                bytesReceived = bytesReceived,
                bitrate = bitrate,
                jitter = jitter,
                lastUpdate = currentTime
            )

            withContext(Dispatchers.Main) {
                listener?.onStatistics(stats)
            }

            lastStatsUpdate = currentTime
            lastBytesReceived = bytesReceived
        }
    }

    fun stopReceiving() {
        if (!isReceiving.get()) {
            Log.w(TAG, "Not receiving")
            return
        }

        isReceiving.set(false)

        try {
            udpSocket?.close()
            udpSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP socket", e)
        }

        // 리소스 정리
        fragmentBuffer.clear()
        fuStartReceived = false
        currentFragmentTimestamp = -1L

        scope.cancel()

        Log.d(TAG, "RTP Receiver stopped")
        Log.d(TAG, "Final Statistics - Received: $packetsReceived, Lost: $packetsLost, OutOfOrder: $packetsOutOfOrder, Duplicates: $duplicatePackets")
    }

    // RTP 헤더 데이터 클래스
    data class RTPHeader(
        val version: Int,
        val padding: Boolean,
        val extension: Boolean,
        val csrcCount: Int,
        val marker: Boolean,
        val payloadType: Int,
        val sequenceNumber: Int,
        val timestamp: Long,
        val ssrc: Long
    )

    // 통계 정보 조회 (개선된 버전)
    fun getStatistics(): RtpStatistics {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastStatsUpdate

        val bytesDiff = bytesReceived - lastBytesReceived
        val bitrate = if (timeDiff > 0) (bytesDiff * 8.0 * 1000.0) / timeDiff else 0.0
        val jitter = if (jitterCount > 0) jitterSum / jitterCount else 0.0

        return RtpStatistics(
            packetsReceived = packetsReceived,
            packetsLost = packetsLost,
            packetsOutOfOrder = packetsOutOfOrder,
            duplicatePackets = duplicatePackets,
            bytesReceived = bytesReceived,
            bitrate = bitrate,
            jitter = jitter,
            lastUpdate = currentTime
        )
    }

    // 실제 사용 중인 포트 반환
    fun getActualPort(): Int = actualPort
}