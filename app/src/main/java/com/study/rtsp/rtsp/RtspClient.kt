package com.study.rtsp.rtsp

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

class RtspClient(
    private val rtspUrl: String,
    private val listener: RTSPClientListener?
) {
    companion object {
        private const val TAG = "RTSPClient"
        private const val RTSP_VERSION = "RTSP/1.0"
        private const val RTSP_PORT = 554
    }

    private var rtspSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private val cseq = AtomicInteger(1)

    private val serverHost: String
    private val serverPort: Int
    private val streamPath: String
    private var sessionId: String? = null
    private var contentBase: String? = null

    // SDP 정보
    private var videoTrack: String? = null
    private var audioTrack: String? = null
    private var videoPayloadType: Int = -1
    private var audioPayloadType: Int = -1

    // RTP 포트 정보
    private val clientRtpPort = 5004  // 클라이언트 RTP 포트
    private val clientRtcpPort = 5005 // 클라이언트 RTCP 포트
    private var serverRtpPort = -1    // 서버 RTP 포트
    private var serverRtcpPort = -1   // 서버 RTCP 포트

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface RTSPClientListener {
        fun onConnected()
        fun onSetupComplete(rtpPort: Int, rtcpPort: Int)
        fun onPlayStarted()
        fun onError(error: String)
        fun onSdpReceived(sdp: String)
    }

    init {
        val uri = URI(rtspUrl)
        serverHost = uri.host
        serverPort = if (uri.port == -1) RTSP_PORT else uri.port
        streamPath = uri.path + if (uri.query != null) "?${uri.query}" else ""
        Log.d(TAG, "Parsed URL - Host: $serverHost, Port: $serverPort, Path: $streamPath")
    }

    fun connect() {
        scope.launch {
            try {
                // RTSP 소켓 연결
                rtspSocket = Socket().apply {
                    soTimeout = 10000 // 10초 타임아웃 (범용적으로 안전한 값)
                    connect(InetSocketAddress(serverHost, serverPort), 15000) // 연결 타임아웃 15초
                    tcpNoDelay = true
                    keepAlive = true
                }

                reader = BufferedReader(InputStreamReader(rtspSocket!!.inputStream))
                writer = PrintWriter(rtspSocket!!.outputStream, true)

                Log.d(TAG, "Connected to RTSP server: $serverHost:$serverPort")

                withContext(Dispatchers.Main) {
                    listener?.onConnected()
                }

                // RTSP 프로토콜 시작
                sendOptions()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to RTSP server", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("Connection failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun sendOptions() {
        try {
            val currentCseq = cseq.getAndIncrement()
            val request = buildString {
                append("OPTIONS $rtspUrl $RTSP_VERSION\r\n")
                append("CSeq: $currentCseq\r\n")
                append("User-Agent: Universal-RTSP-Client/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Sending OPTIONS:\n$request")
            writer?.print(request)
            writer?.flush()

            val response = readResponse()
            Log.d(TAG, "OPTIONS Response:\n$response")

            if (response.contains("200 OK")) {
                sendDescribe()
            } else {
                withContext(Dispatchers.Main) {
                    listener?.onError("OPTIONS failed - Response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send OPTIONS", e)
            withContext(Dispatchers.Main) {
                listener?.onError("OPTIONS failed: ${e.message}")
            }
        }
    }

    private suspend fun sendDescribe() {
        try {
            val currentCseq = cseq.getAndIncrement()
            val request = buildString {
                append("DESCRIBE $rtspUrl $RTSP_VERSION\r\n")
                append("CSeq: $currentCseq\r\n")
                append("Accept: application/sdp\r\n")
                append("User-Agent: Universal-RTSP-Client/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Sending DESCRIBE:\n$request")
            writer?.print(request)
            writer?.flush()

            delay(100) // 서버 처리 시간 확보

            val response = readResponse()
            Log.d(TAG, "DESCRIBE Response:\n$response")

            if (response.contains("200 OK")) {
                val sdp = extractSdp(response)
                if (sdp.isNotEmpty()) {
                    parseSdp(sdp)
                    parseContentBase(response) // Content-Base 파싱
                    withContext(Dispatchers.Main) {
                        listener?.onSdpReceived(sdp)
                    }
                    sendSetup()
                } else {
                    withContext(Dispatchers.Main) {
                        listener?.onError("Empty SDP received")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    listener?.onError("DESCRIBE failed - Response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send DESCRIBE", e)
            withContext(Dispatchers.Main) {
                listener?.onError("DESCRIBE failed: ${e.message}")
            }
        }
    }

    private suspend fun sendSetup() {
        try {
            val track = videoTrack
            if (track == null) {
                withContext(Dispatchers.Main) {
                    listener?.onError("No video track found")
                }
                return
            }

            val currentCseq = cseq.getAndIncrement()
            val setupUrl = buildSetupUrl(track)
            val transport = "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort"

            val request = buildString {
                append("SETUP $setupUrl $RTSP_VERSION\r\n")
                append("CSeq: $currentCseq\r\n")
                append("Transport: $transport\r\n")
                append("User-Agent: Universal-RTSP-Client/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Sending SETUP:\n$request")
            writer?.print(request)
            writer?.flush()

            val response = readResponse()
            Log.d(TAG, "SETUP Response:\n$response")

            if (response.contains("200 OK")) {
                parseSetupResponse(response)
                withContext(Dispatchers.Main) {
                    listener?.onSetupComplete(clientRtpPort, clientRtcpPort)
                }
                sendPlay()
            } else {
                withContext(Dispatchers.Main) {
                    listener?.onError("SETUP failed - Response: $response")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SETUP", e)
            withContext(Dispatchers.Main) {
                listener?.onError("SETUP failed: ${e.message}")
            }
        }
    }

    private suspend fun sendPlay() {
        try {
            val session = sessionId
            if (session == null) {
                withContext(Dispatchers.Main) {
                    listener?.onError("No session ID")
                }
                return
            }

            val currentCseq = cseq.getAndIncrement()
            val request = buildString {
                append("PLAY $rtspUrl $RTSP_VERSION\r\n")
                append("CSeq: $currentCseq\r\n")
                append("Session: $session\r\n")
                append("User-Agent: Universal-RTSP-Client/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Sending PLAY:\n$request")
            writer?.print(request)
            writer?.flush()

            try {
                val response = readResponse()
                Log.d(TAG, "PLAY Response:\n$response")

                if (response.contains("200 OK")) {
                    Log.d(TAG, "RTSP PLAY successful - streaming started")
                    withContext(Dispatchers.Main) {
                        listener?.onPlayStarted()
                    }
                } else if (response.trim().isEmpty()) {
                    // 일부 서버는 PLAY 응답을 보내지 않음
                    Log.w(TAG, "Empty PLAY response - assuming streaming started")
                    withContext(Dispatchers.Main) {
                        listener?.onPlayStarted()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        listener?.onError("PLAY failed - Response: $response")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 일부 서버는 PLAY 응답을 보내지 않고 바로 스트리밍 시작
                Log.w(TAG, "PLAY response timeout - assuming streaming started")
                withContext(Dispatchers.Main) {
                    listener?.onPlayStarted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send PLAY", e)
            withContext(Dispatchers.Main) {
                listener?.onError("PLAY failed: ${e.message}")
            }
        }
    }

    private suspend fun readResponse(): String = withContext(Dispatchers.IO) {
        val response = StringBuilder()
        var contentLength = 0

        try {
            Log.d(TAG, "Starting to read response...")

            // 헤더 읽기
            var lineCount = 0
            while (true) {
                try {
                    val line = reader?.readLine()
                    if (line == null) {
                        Log.w(TAG, "Received null line after $lineCount lines")
                        break
                    }

                    lineCount++
                    Log.d(TAG, "Response line $lineCount: '$line'")
                    response.append(line).append("\r\n")

                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                        Log.d(TAG, "Content length: $contentLength")
                    }

                    if (line.isEmpty()) {
                        Log.d(TAG, "Empty line received, header complete after $lineCount lines")
                        break
                    }

                    if (lineCount > 50) { // 비정상적으로 긴 헤더 방지
                        Log.w(TAG, "Too many header lines, breaking")
                        break
                    }

                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "Timeout reading line $lineCount")
                    if (lineCount > 0) {
                        Log.w(TAG, "Partial response received, continuing")
                        break
                    } else {
                        throw e
                    }
                }
            }

            // 본문 읽기 (SDP 등)
            if (contentLength > 0) {
                Log.d(TAG, "Reading content body of $contentLength bytes")
                val buffer = CharArray(contentLength)
                var totalRead = 0
                val startTime = System.currentTimeMillis()

                while (totalRead < contentLength) {
                    try {
                        val read = reader?.read(buffer, totalRead, contentLength - totalRead) ?: -1
                        if (read == -1) {
                            Log.w(TAG, "End of stream while reading content after $totalRead bytes")
                            break
                        }
                        totalRead += read

                        // 본문 읽기 타임아웃 (10초)
                        if (System.currentTimeMillis() - startTime > 10000) {
                            Log.w(TAG, "Content read timeout after $totalRead bytes")
                            break
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(TAG, "Timeout reading content, got $totalRead of $contentLength bytes")
                        break
                    }
                }

                if (totalRead > 0) {
                    response.append(String(buffer, 0, totalRead))
                    Log.d(TAG, "Content body read complete: $totalRead bytes")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reading response", e)

            // 부분적인 응답이라도 있다면 반환
            if (response.isNotEmpty()) {
                Log.w(TAG, "Returning partial response due to error")
                return@withContext response.toString()
            }
            throw e
        }

        val result = response.toString()
        Log.d(TAG, "Complete response (${result.length} chars)")
        result
    }

    private fun extractSdp(response: String): String {
        Log.d(TAG, "Extracting SDP from response")
        val lines = response.split("\r\n")
        var inSdp = false
        val sdp = StringBuilder()

        for (line in lines) {
            if (line.isEmpty() && !inSdp) {
                inSdp = true
                continue
            }
            if (inSdp && line.trim().isNotEmpty()) {
                sdp.append(line).append("\r\n")
            }
        }

        val result = sdp.toString().trim()
        Log.d(TAG, "Extracted SDP (${result.length} chars):\n$result")
        return result
    }

    private fun parseContentBase(response: String) {
        val lines = response.split("\r\n")
        for (line in lines) {
            if (line.lowercase().startsWith("content-base:")) {
                contentBase = line.substring(13).trim()
                Log.d(TAG, "Content-Base: $contentBase")
                break
            }
        }
    }

    private fun parseSdp(sdp: String) {
        Log.d(TAG, "Parsing SDP:\n$sdp")
        val lines = sdp.split("\r\n").filter { it.trim().isNotEmpty() }
        var currentMedia: String? = null

        for (line in lines) {
            val trimmedLine = line.trim()
            Log.d(TAG, "SDP Line: '$trimmedLine'")

            when {
                trimmedLine.startsWith("m=") -> {
                    currentMedia = trimmedLine
                    Log.d(TAG, "Media line found: $currentMedia")
                    if (trimmedLine.startsWith("m=video")) {
                        val parts = trimmedLine.split(" ")
                        if (parts.size > 3) {
                            videoPayloadType = parts[3].toIntOrNull() ?: -1
                            Log.d(TAG, "Video payload type: $videoPayloadType")
                        }
                    } else if (trimmedLine.startsWith("m=audio")) {
                        val parts = trimmedLine.split(" ")
                        if (parts.size > 3) {
                            audioPayloadType = parts[3].toIntOrNull() ?: -1
                            Log.d(TAG, "Audio payload type: $audioPayloadType")
                        }
                    }
                }
                trimmedLine.startsWith("a=control:") && currentMedia != null -> {
                    val control = trimmedLine.substring(10).trim()
                    Log.d(TAG, "Control attribute found: '$control' for media: $currentMedia")

                    when {
                        currentMedia.startsWith("m=video") -> {
                            videoTrack = control
                            Log.d(TAG, "Video track set: $videoTrack")
                        }
                        currentMedia.startsWith("m=audio") -> {
                            audioTrack = control
                            Log.d(TAG, "Audio track set: $audioTrack")
                        }
                    }
                }
            }
        }

        // 결과 요약
        Log.d(TAG, "SDP Parsing Result:")
        Log.d(TAG, "  Video track: $videoTrack")
        Log.d(TAG, "  Audio track: $audioTrack")
        Log.d(TAG, "  Video payload type: $videoPayloadType")
        Log.d(TAG, "  Audio payload type: $audioPayloadType")

        // 비디오 트랙이 없으면 기본값들 시도
        if (videoTrack == null) {
            Log.w(TAG, "No video track found, trying common defaults")
            videoTrack = "*" // 많은 서버에서 사용하는 기본값
            Log.d(TAG, "Using default video track: $videoTrack")
        }
    }

    private fun buildSetupUrl(track: String): String {
        Log.d(TAG, "Building SETUP URL for track: '$track'")

        val setupUrl = when {
            // 절대 URL (full RTSP URL)
            track.startsWith("rtsp://") -> {
                Log.d(TAG, "Track is absolute URL")
                track
            }
            // 상대 경로 (/)로 시작
            track.startsWith("/") -> {
                val base = contentBase ?: "rtsp://$serverHost:$serverPort"
                val url = base.trimEnd('/') + track
                Log.d(TAG, "Track is absolute path, using base: $url")
                url
            }
            // 와일드카드 (*)
            track == "*" -> {
                Log.d(TAG, "Track is wildcard, using main URL")
                rtspUrl
            }
            // 상대 경로
            else -> {
                val base = contentBase ?: rtspUrl
                val url = base.trimEnd('/') + "/" + track
                Log.d(TAG, "Track is relative path, using base: $url")
                url
            }
        }

        Log.d(TAG, "Final SETUP URL: $setupUrl")
        return setupUrl
    }

    private fun parseSetupResponse(response: String) {
        val lines = response.split("\r\n")

        for (line in lines) {
            when {
                line.lowercase().startsWith("session:") -> {
                    val session = line.substring(8).trim()
                    // 세션 ID에서 타임아웃 부분 제거
                    sessionId = if (session.contains(";")) {
                        session.split(";")[0]
                    } else {
                        session
                    }
                    Log.d(TAG, "Session ID: $sessionId")
                }
                line.lowercase().startsWith("transport:") -> {
                    // 서버 RTP 포트 파싱
                    val transport = line.substring(10).trim()
                    if (transport.contains("server_port=")) {
                        try {
                            val serverPorts = transport.split("server_port=")[1].split(";")[0]
                            val ports = serverPorts.split("-")
                            serverRtpPort = ports[0].toIntOrNull() ?: -1
                            if (ports.size > 1) {
                                serverRtcpPort = ports[1].toIntOrNull() ?: -1
                            }
                            Log.d(TAG, "Server RTP Port: $serverRtpPort, RTCP Port: $serverRtcpPort")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse server ports: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    suspend fun sendTeardown() {
        try {
            val session = sessionId ?: return

            val currentCseq = cseq.getAndIncrement()
            val request = buildString {
                append("TEARDOWN $rtspUrl $RTSP_VERSION\r\n")
                append("CSeq: $currentCseq\r\n")
                append("Session: $session\r\n")
                append("User-Agent: Universal-RTSP-Client/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Sending TEARDOWN:\n$request")
            writer?.print(request)
            writer?.flush()

            try {
                val response = readResponse()
                Log.d(TAG, "TEARDOWN Response:\n$response")
            } catch (e: Exception) {
                Log.w(TAG, "TEARDOWN response error (expected): ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send TEARDOWN", e)
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                sendTeardown()

                reader?.close()
                writer?.close()
                rtspSocket?.takeIf { !it.isClosed }?.close()

                Log.d(TAG, "RTSP connection closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            } finally {
                scope.cancel()
            }
        }
    }

    // Getter 프로퍼티들
    val getClientRtpPort: Int get() = clientRtpPort
    val getClientRtcpPort: Int get() = clientRtcpPort
    val getServerRtpPort: Int get() = serverRtpPort
    val getServerRtcpPort: Int get() = serverRtcpPort
    val getVideoPayloadType: Int get() = videoPayloadType
    val getSessionId: String? get() = sessionId
    val getVideoTrack: String? get() = videoTrack
}