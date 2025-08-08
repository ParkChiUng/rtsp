package com.study.rtsp.rtsp

import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class H264Parser(private val listener: H264ParserListener?) {
companion object {
    private const val TAG = "H264Parser"

    // NAL Unit Types
    private const val NAL_TYPE_NON_IDR = 1      // Non-IDR slice
    private const val NAL_TYPE_PARTITION_A = 2   // Slice data partition A
    private const val NAL_TYPE_PARTITION_B = 3   // Slice data partition B
    private const val NAL_TYPE_PARTITION_C = 4   // Slice data partition C
    private const val NAL_TYPE_IDR = 5          // IDR slice
    private const val NAL_TYPE_SEI = 6          // SEI (Supplemental Enhancement Information)
    private const val NAL_TYPE_SPS = 7          // SPS (Sequence Parameter Set)
    private const val NAL_TYPE_PPS = 8          // PPS (Picture Parameter Set)
    private const val NAL_TYPE_AUD = 9          // AUD (Access Unit Delimiter)
    private const val NAL_TYPE_END_OF_SEQUENCE = 10
    private const val NAL_TYPE_END_OF_STREAM = 11
    private const val NAL_TYPE_FILLER_DATA = 12

    // Annex-B Start Codes
    private val START_CODE_3 = byteArrayOf(0x00, 0x00, 0x01)
    private val START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    // Buffer limits
    private const val MAX_FRAME_SIZE = 2 * 1024 * 1024  // 2MB
    private const val MAX_BUFFERED_FRAMES = 20
    private const val FRAME_TIMEOUT = 5000L // 5초
    private const val FRAME_CLEANUP_INTERVAL = 10000L // 10초
}

interface H264ParserListener {
    fun onSPSParsed(sps: ByteArray, width: Int, height: Int, fps: Float)
    fun onPPSParsed(pps: ByteArray)
    fun onFrameReady(frame: H264Frame)
    fun onError(error: String)
    fun onStatistics(stats: ParserStatistics)
}

// 파싱된 프레임 정보
data class H264Frame(
    val data: ByteArray,
    val timestamp: Long,
    val isKeyFrame: Boolean,
    val frameType: FrameType,
    val nalUnits: List<NALUnit>,
    val hasSpsPps: Boolean
) {
    enum class FrameType {
        I_FRAME,    // IDR 프레임 (키프레임)
        P_FRAME,    // 예측 프레임
        B_FRAME,    // 양방향 예측 프레임 (H.264에서는 드물음)
        UNKNOWN
    }
}

data class NALUnit(
    val type: Int,
    val data: ByteArray,
    val startCodeLength: Int,
    val nalRefIdc: Int
)

// 파서 통계 정보
data class ParserStatistics(
    val totalFramesParsed: Int,
    val iFramesParsed: Int,
    val pFramesParsed: Int,
    val parseErrors: Int,
    val currentBufferSize: Int,
    val spsReceived: Boolean,
    val ppsReceived: Boolean,
    val lastUpdate: Long
)

private val isRunning = AtomicBoolean(false)
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

// SPS/PPS 정보
private var currentSps: ByteArray? = null
private var currentPps: ByteArray? = null
private var videoWidth = 0
private var videoHeight = 0
private var videoFps = 30.0f

// 프레임 조립용 버퍼
private val frameBuffer = mutableListOf<NALUnit>()
private var currentFrameTimestamp = -1L
private var frameStartTime = 0L
private var isFrameStarted = false

// 통계
private var totalFramesParsed = 0
private var iFramesParsed = 0
private var pFramesParsed = 0
private var parseErrors = 0
private var lastStatsUpdate = System.currentTimeMillis()

// 프레임 버퍼링 큐
private val frameQueue = ConcurrentLinkedQueue<H264Frame>()

/** H264 파서 시작 **/
fun startParsing() {
    if (isRunning.get()) {
        Log.w(TAG, "Parser already running")
        return
    }

    isRunning.set(true)
    initializeStatistics()

    // 주기적인 정리 작업 시작
    scope.launch {
        startPeriodicCleanup()
    }

    Log.d(TAG, "H264 Parser started")
}

/** 통계 초기화 **/
private fun initializeStatistics() {
    totalFramesParsed = 0
    iFramesParsed = 0
    pFramesParsed = 0
    parseErrors = 0
    lastStatsUpdate = System.currentTimeMillis()
}

/** 주기적인 정리 작업 **/
private suspend fun startPeriodicCleanup() {
    var lastCleanupTime = System.currentTimeMillis()
    var lastStatsTime = System.currentTimeMillis()

    while (isRunning.get()) {
        try {
            val currentTime = System.currentTimeMillis()

            // 오래된 프레임 정리 (10초마다)
            if (currentTime - lastCleanupTime > FRAME_CLEANUP_INTERVAL) {
                cleanupOldFrames()
                lastCleanupTime = currentTime
            }

            // 통계 업데이트 (5초마다)
            if (currentTime - lastStatsTime > 5000) {
                updateStatistics()
                lastStatsTime = currentTime
            }

            delay(1000) // 1초마다 체크

        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error in periodic cleanup", e)
            }
        }
    }
}

/**
 * RTPReceiver로부터 받은 NAL Unit을 파싱
 *
 * @param nalUnit RTPReceiver에서 이미 Annex-B 형식으로 변환된 NAL Unit
 * @param timestamp RTP 타임스탬프
 */
fun parseNALUnit(nalUnit: ByteArray, timestamp: Long) {
    if (!isRunning.get()) {
        Log.w(TAG, "Parser not running")
        return
    }

    if (nalUnit.size < 4) {
        Log.w(TAG, "NAL Unit too small: ${nalUnit.size} bytes")
        return
    }

    scope.launch {
        try {
            processNALUnit(nalUnit, timestamp)
        } catch (e: Exception) {
            parseErrors++
            Log.e(TAG, "Error processing NAL Unit", e)
            withContext(Dispatchers.Main) {
                listener?.onError("NAL Unit processing error: ${e.message}")
            }
        }
    }
}

/** NAL Unit 처리 **/
private suspend fun processNALUnit(nalUnit: ByteArray, timestamp: Long) {
    // Start code 및 NAL header 추출
    val nalData = extractNALData(nalUnit) ?: run {
        Log.w(TAG, "Invalid NAL Unit format")
        return
    }

    val nalHeader = nalData[0].toInt() and 0xFF
    val nalType = nalHeader and 0x1F
    val nalRefIdc = (nalHeader shr 5) and 0x03
    val startCodeLength = getStartCodeLength(nalUnit)

    Log.d(TAG, "Processing NAL Unit - Type: $nalType, Size: ${nalData.size}, RefIdc: $nalRefIdc, Timestamp: $timestamp")

    val nalUnitObj = NALUnit(nalType, nalData, startCodeLength, nalRefIdc)

    when (nalType) {
        NAL_TYPE_SPS -> {
            Log.d(TAG, "Processing SPS")
            processSPS(nalData)
        }

        NAL_TYPE_PPS -> {
            Log.d(TAG, "Processing PPS")
            processPPS(nalData)
        }

        NAL_TYPE_IDR -> {
            Log.d(TAG, "Processing IDR slice")
            startNewFrame(nalUnitObj, timestamp, true)
        }

        NAL_TYPE_NON_IDR -> {
            Log.d(TAG, "Processing Non-IDR slice")
            startNewFrame(nalUnitObj, timestamp, false)
        }

        NAL_TYPE_AUD -> {
            Log.d(TAG, "Access Unit Delimiter - finalizing current frame")
            finalizeCurrentFrame()
        }

        NAL_TYPE_SEI -> {
            Log.d(TAG, "SEI NAL Unit received")
            // SEI는 현재 프레임에 포함시킬 수 있음
            addToCurrentFrame(nalUnitObj, timestamp)
        }

        else -> {
            Log.d(TAG, "Other NAL Unit type: $nalType")
            addToCurrentFrame(nalUnitObj, timestamp)
        }
    }
    }

    /** SPS 처리 및 파싱 **/
    private suspend fun processSPS(spsData: ByteArray) {
        try {
            currentSps = createAnnexBNALUnit(spsData)

            // SPS 파싱하여 비디오 정보 추출
            val spsInfo = parseSPSInfo(spsData)
            videoWidth = spsInfo.width
            videoHeight = spsInfo.height
            videoFps = spsInfo.fps

            Log.d(TAG, "SPS parsed - Width: $videoWidth, Height: $videoHeight, FPS: $videoFps")

            withContext(Dispatchers.Main) {
                listener?.onSPSParsed(currentSps!!, videoWidth, videoHeight, videoFps)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing SPS", e)
            parseErrors++
        }
    }

    /** PPS 처리 **/
    private suspend fun processPPS(ppsData: ByteArray) {
        try {
            currentPps = createAnnexBNALUnit(ppsData)

            Log.d(TAG, "PPS processed - Size: ${currentPps!!.size}")

            withContext(Dispatchers.Main) {
                listener?.onPPSParsed(currentPps!!)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing PPS", e)
            parseErrors++
        }
    }

    /** 새로운 프레임 시작 **/
    private suspend fun startNewFrame(nalUnit: NALUnit, timestamp: Long, isKeyFrame: Boolean) {
        // 이전 프레임이 있다면 완료 처리
        if (isFrameStarted) {
            finalizeCurrentFrame()
        }

        // 새 프레임 시작
        frameBuffer.clear()
        frameBuffer.add(nalUnit)
        currentFrameTimestamp = timestamp
        frameStartTime = System.currentTimeMillis()
        isFrameStarted = true

        Log.d(TAG, "Started new frame - ${if (isKeyFrame) "KEY" else "NON-KEY"} frame, timestamp: $timestamp")
    }

    /** 현재 프레임에 NAL Unit 추가 **/
    private fun addToCurrentFrame(nalUnit: NALUnit, timestamp: Long) {
        if (!isFrameStarted) {
            Log.w(TAG, "No frame started, ignoring NAL Unit type: ${nalUnit.type}")
            return
        }

        // 타임스탬프가 다르면 새 프레임으로 처리
        if (currentFrameTimestamp != timestamp) {
            Log.d(TAG, "Timestamp changed ($currentFrameTimestamp -> $timestamp), finalizing frame")
            scope.launch { finalizeCurrentFrame() }
            return
        }

        // 프레임 크기 제한 체크
        val currentSize = frameBuffer.sumOf { it.data.size }
        if (currentSize > MAX_FRAME_SIZE) {
            Log.w(TAG, "Frame size exceeded limit ($currentSize > $MAX_FRAME_SIZE)")
            scope.launch { finalizeCurrentFrame() }
            return
        }

        frameBuffer.add(nalUnit)
    }

    /** 현재 프레임 완료 처리 **/
    private suspend fun finalizeCurrentFrame() {
        if (!isFrameStarted || frameBuffer.isEmpty()) {
            return
        }

        try {
            // 프레임 타입 결정
            val isKeyFrame = frameBuffer.any { it.type == NAL_TYPE_IDR }
            val frameType = when {
                isKeyFrame -> H264Frame.FrameType.I_FRAME
                frameBuffer.any { it.type == NAL_TYPE_NON_IDR } -> H264Frame.FrameType.P_FRAME
                else -> H264Frame.FrameType.UNKNOWN
            }

            // SPS/PPS가 필요한 키프레임인 경우 추가
            val nalUnitsWithConfig = mutableListOf<NALUnit>()
            var hasSpsPps = false

            if (isKeyFrame && currentSps != null && currentPps != null) {
                // 키프레임 앞에 SPS, PPS 추가
                val spsNal = extractNALData(currentSps!!)
                val ppsNal = extractNALData(currentPps!!)

                if (spsNal != null && ppsNal != null) {
                    nalUnitsWithConfig.add(NALUnit(NAL_TYPE_SPS, spsNal, 4, 3))
                    nalUnitsWithConfig.add(NALUnit(NAL_TYPE_PPS, ppsNal, 4, 3))
                    hasSpsPps = true
                    Log.d(TAG, "Added SPS/PPS to keyframe")
                }
            }

            nalUnitsWithConfig.addAll(frameBuffer)

            // 완전한 프레임 데이터 생성 (Annex-B 형식)
            val frameData = createCompleteFrameData(nalUnitsWithConfig)

            val frame = H264Frame(
                data = frameData,
                timestamp = currentFrameTimestamp,
                isKeyFrame = isKeyFrame,
                frameType = frameType,
                nalUnits = nalUnitsWithConfig.toList(),
                hasSpsPps = hasSpsPps
            )

            // 통계 업데이트
            totalFramesParsed++
            if (isKeyFrame) iFramesParsed++ else pFramesParsed++

            // 프레임 큐에 추가 (버퍼 크기 제한)
            frameQueue.offer(frame)
            while (frameQueue.size > MAX_BUFFERED_FRAMES) {
                frameQueue.poll()
                Log.w(TAG, "Frame queue overflow, dropping old frame")
            }

            Log.d(TAG, "Frame finalized - Type: $frameType, Size: ${frameData.size}, NAL Units: ${nalUnitsWithConfig.size}")

            // 리스너에게 전달
            withContext(Dispatchers.Main) {
                listener?.onFrameReady(frame)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing frame", e)
            parseErrors++
        } finally {
            // 프레임 버퍼 초기화
            frameBuffer.clear()
            isFrameStarted = false
            currentFrameTimestamp = -1L
        }
    }

    /** NAL Unit에서 실제 데이터 부분 추출 (Start code 제거) **/
    private fun extractNALData(nalUnit: ByteArray): ByteArray? {
        return when {
            nalUnit.size >= 4 &&
                    nalUnit[0] == 0x00.toByte() && nalUnit[1] == 0x00.toByte() &&
                    nalUnit[2] == 0x00.toByte() && nalUnit[3] == 0x01.toByte() -> {
                // 4바이트 start code
                ByteArray(nalUnit.size - 4).also {
                    System.arraycopy(nalUnit, 4, it, 0, it.size)
                }
            }

            nalUnit.size >= 3 &&
                    nalUnit[0] == 0x00.toByte() && nalUnit[1] == 0x00.toByte() &&
                    nalUnit[2] == 0x01.toByte() -> {
                // 3바이트 start code
                ByteArray(nalUnit.size - 3).also {
                    System.arraycopy(nalUnit, 3, it, 0, it.size)
                }
            }

            else -> {
                // Start code가 없는 경우 - 그대로 반환
                nalUnit
            }
        }
    }

    /** Start code 길이 확인 **/
    private fun getStartCodeLength(nalUnit: ByteArray): Int {
        return when {
            nalUnit.size >= 4 &&
                    nalUnit[0] == 0x00.toByte() && nalUnit[1] == 0x00.toByte() &&
                    nalUnit[2] == 0x00.toByte() && nalUnit[3] == 0x01.toByte() -> 4

            nalUnit.size >= 3 &&
                    nalUnit[0] == 0x00.toByte() && nalUnit[1] == 0x00.toByte() &&
                    nalUnit[2] == 0x01.toByte() -> 3

            else -> 0
        }
    }

    /** Annex-B NAL Unit 생성 **/
    private fun createAnnexBNALUnit(nalData: ByteArray): ByteArray {
        val nalUnit = ByteArray(START_CODE_4.size + nalData.size)
        System.arraycopy(START_CODE_4, 0, nalUnit, 0, START_CODE_4.size)
        System.arraycopy(nalData, 0, nalUnit, START_CODE_4.size, nalData.size)
        return nalUnit
    }

    /** 완전한 프레임 데이터 생성 **/
    private fun createCompleteFrameData(nalUnits: List<NALUnit>): ByteArray {
        val totalSize = nalUnits.sumOf { it.startCodeLength + it.data.size }
        val frameData = ByteArray(totalSize)
        var offset = 0

        for (nalUnit in nalUnits) {
            // Start code 추가
            val startCode = if (nalUnit.startCodeLength == 4) START_CODE_4 else START_CODE_3
            System.arraycopy(startCode, 0, frameData, offset, startCode.size)
            offset += startCode.size

            // NAL data 추가
            System.arraycopy(nalUnit.data, 0, frameData, offset, nalUnit.data.size)
            offset += nalUnit.data.size
        }

        return frameData
    }

    /** SPS 정보 파싱 **/
    private fun parseSPSInfo(spsData: ByteArray): SPSInfo {
        try {
            // 간단한 SPS 파싱 (완전한 구현은 매우 복잡함)
            val buffer = ByteBuffer.wrap(spsData)
            buffer.get() // NAL header 스킵

            // Profile, level 등 기본 정보 스킵
            buffer.get() // profile_idc
            buffer.get() // constraint flags
            buffer.get() // level_idc

            // 더 정확한 파싱을 위해서는 Exponential Golomb 디코딩이 필요
            // 여기서는 기본값 사용
            return SPSInfo(
                width = 1920,  // 기본값 - 실제로는 SPS에서 파싱해야 함
                height = 1080, // 기본값 - 실제로는 SPS에서 파싱해야 함
                fps = 30.0f    // 기본값 - 실제로는 SPS에서 파싱해야 함
            )

        } catch (e: Exception) {
            Log.w(TAG, "SPS parsing failed, using defaults", e)
            return SPSInfo(1920, 1080, 30.0f)
        }
    }

    data class SPSInfo(
        val width: Int,
        val height: Int,
        val fps: Float
    )

    /** 오래된 프레임 정리 **/
    private fun cleanupOldFrames() {
        val currentTime = System.currentTimeMillis()

        // 미완성 프레임 정리
        if (isFrameStarted && currentTime - frameStartTime > FRAME_TIMEOUT) {
            Log.w(TAG, "Cleaning up old incomplete frame")
            frameBuffer.clear()
            isFrameStarted = false
            currentFrameTimestamp = -1L
        }

        // 큐 크기 정리
        while (frameQueue.size > MAX_BUFFERED_FRAMES) {
            frameQueue.poll()
        }
    }

    /** 통계 업데이트 **/
    private suspend fun updateStatistics() {
        val currentTime = System.currentTimeMillis()

        val stats = ParserStatistics(
            totalFramesParsed = totalFramesParsed,
            iFramesParsed = iFramesParsed,
            pFramesParsed = pFramesParsed,
            parseErrors = parseErrors,
            currentBufferSize = frameQueue.size,
            spsReceived = currentSps != null,
            ppsReceived = currentPps != null,
            lastUpdate = currentTime
        )

        withContext(Dispatchers.Main) {
            listener?.onStatistics(stats)
        }

        lastStatsUpdate = currentTime
    }

    /** 다음 프레임 가져오기 **/
    fun getNextFrame(): H264Frame? {
        return frameQueue.poll()
    }

    /** 현재 버퍼된 프레임 수 **/
    fun getBufferedFrameCount(): Int = frameQueue.size

    /** SPS/PPS 준비 상태 확인 **/
    fun isConfigurationReady(): Boolean = currentSps != null && currentPps != null

    /** 현재 비디오 정보 **/
    fun getVideoInfo(): VideoInfo? {
        return if (currentSps != null) {
            VideoInfo(videoWidth, videoHeight, videoFps)
        } else null
    }

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val fps: Float
    )

    /** 통계 정보 가져오기 **/
    fun getStatistics(): ParserStatistics {
        return ParserStatistics(
            totalFramesParsed = totalFramesParsed,
            iFramesParsed = iFramesParsed,
            pFramesParsed = pFramesParsed,
            parseErrors = parseErrors,
            currentBufferSize = frameQueue.size,
            spsReceived = currentSps != null,
            ppsReceived = currentPps != null,
            lastUpdate = System.currentTimeMillis()
        )
    }

    /** 파서 중지 **/
    fun stopParsing() {
        if (!isRunning.get()) {
            Log.w(TAG, "Parser not running")
            return
        }

        isRunning.set(false)

        // 리소스 정리
        frameBuffer.clear()
        frameQueue.clear()
        isFrameStarted = false
        currentFrameTimestamp = -1L

        scope.cancel()

        Log.d(TAG, "H264 Parser stopped")
        Log.d(TAG, "Final Statistics - Total: $totalFramesParsed, I-frames: $iFramesParsed, P-frames: $pFramesParsed, Errors: $parseErrors")
    }
}
