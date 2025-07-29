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

    // RTP 포트 정보 - 여러 포트로 시도
    private val clientRtpPorts = listOf(6000, 7000, 8000, 5004)
    private var selectedRtpPort = -1
    private var selectedRtcpPort = -1
    private var serverRtpPort = -1
    private var serverRtcpPort = -1

    // TCP Interleaved 모드 지원
    private var useTcpInterleaved = false
    private var interleavedChannel = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface RTSPClientListener {
        fun onConnected()
        fun onSetupComplete(rtpPort: Int, rtcpPort: Int, isTcp: Boolean)
        fun onPlayStarted()
        fun onError(error: String)
        fun onSdpReceived(sdp: String)
        fun onRtpDataReceived(data: ByteArray, isRtp: Boolean) // TCP Interleaved용
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
                rtspSocket = Socket().apply {
                    soTimeout = 10000
                    connect(InetSocketAddress(serverHost, serverPort), 15000)
                    tcpNoDelay = true
                    keepAlive = true
                }

                reader = BufferedReader(InputStreamReader(rtspSocket!!.inputStream))
                writer = PrintWriter(rtspSocket!!.outputStream, true)

                Log.d(TAG, "Connected to RTSP server: $serverHost:$serverPort")

                withContext(Dispatchers.Main) {
                    listener?.onConnected()
                }

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

            delay(100)

            val response = readResponse()
            Log.d(TAG, "DESCRIBE Response:\n$response")

            if (response.contains("200 OK")) {
                val sdp = extractSdp(response)
                if (sdp.isNotEmpty()) {
                    parseSdp(sdp)
                    parseContentBase(response)
                    withContext(Dispatchers.Main) {
                        listener?.onSdpReceived(sdp)
                    }

                    // TCP Interleaved 먼저 시도
                    if (!tryTcpInterleavedSetup()) {
                        // TCP 실패 시 UDP 시도
                        tryUdpSetup()
                    }
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

    private suspend fun tryTcpInterleavedSetup(): Boolean {
        try {
            val track = videoTrack ?: return false

            val currentCseq = cseq.getAndIncrement()
            val setupUrl = buildSetupUrl(track)
            val transport = "RTP/AVP/TCP;unicast;interleaved=0-1"

            val request = buildString {
                append("SETUP $setupUrl $RTSP_VERSION\r\n")
                append("CSeq: $currentCseq\r\n")
                append("Transport: $transport\r\n")
                append("User-Agent: Universal-RTSP-Client/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Trying TCP Interleaved SETUP:\n$request")
            writer?.print(request)
            writer?.flush()

            val response = readResponse()
            Log.d(TAG, "TCP SETUP Response:\n$response")

            if (response.contains("200 OK") && response.contains("interleaved")) {
                Log.d(TAG, "TCP Interleaved mode successful!")
                useTcpInterleaved = true
                parseSetupResponse(response)

                withContext(Dispatchers.Main) {
                    listener?.onSetupComplete(-1, -1, true)
                }

                sendPlay()
                return true
            }

            return false
        } catch (e: Exception) {
            Log.w(TAG, "TCP Interleaved setup failed: ${e.message}")
            return false
        }
    }

    private suspend fun tryUdpSetup(): Boolean {
        // 여러 포트로 시도
        for (basePort in clientRtpPorts) {
            try {
                if (tryUdpSetupWithPort(basePort, basePort + 1)) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP setup failed with port $basePort: ${e.message}")
            }
        }

        // 모든 포트 실패 시 자동 할당 시도
        return tryUdpSetupWithPort(0, 0)
    }

    private suspend fun tryUdpSetupWithPort(rtpPort: Int, rtcpPort: Int): Boolean {
        try {
            val track = videoTrack ?: return false

            // 포트 사용 가능 여부 확인
            if (rtpPort > 0) {
                val testSocket = DatagramSocket(rtpPort)
                testSocket.close()
                Log.d(TAG, "Port $rtpPort is available")
            }

            val currentCseq = cseq.getAndIncrement()
            val setupUrl = buildSetupUrl(track)
            val transport = if (rtpPort == 0) {
                "RTP/AVP;unicast" // 서버가 포트 할당
            } else {
                "RTP/AVP;unicast;client_port=$rtpPort-$rtcpPort"
            }

            val request = buildString {
                append("SETUP $setupUrl $RTSP_VERSION\r\n")
                append("CSeq: $currentCseq\r\n")
                append("Transport: $transport\r\n")
                append("User-Agent: Universal-RTSP-Client/1.0\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Trying UDP SETUP with port $rtpPort:\n$request")
            writer?.print(request)
            writer?.flush()

            val response = readResponse()
            Log.d(TAG, "UDP SETUP Response:\n$response")

            if (response.contains("200 OK")) {
                useTcpInterleaved = false
                parseSetupResponse(response)

                selectedRtpPort = if (rtpPort == 0) extractClientPortFromResponse(response) else rtpPort
                selectedRtcpPort = selectedRtpPort + 1

                Log.d(TAG, "UDP mode successful! Using ports: $selectedRtpPort-$selectedRtcpPort")

                withContext(Dispatchers.Main) {
                    listener?.onSetupComplete(selectedRtpPort, selectedRtcpPort, false)
                }

                sendPlay()
                return true
            }

            return false
        } catch (e: Exception) {
            Log.w(TAG, "UDP setup failed with port $rtpPort: ${e.message}")
            return false
        }
    }

    private fun extractClientPortFromResponse(response: String): Int {
        // Transport 라인에서 실제 할당된 클라이언트 포트 추출
        val lines = response.split("\r\n")
        for (line in lines) {
            if (line.lowercase().startsWith("transport:")) {
                val transport = line.substring(10).trim()
                if (transport.contains("client_port=")) {
                    val clientPorts = transport.split("client_port=")[1].split(";")[0]
                    val ports = clientPorts.split("-")
                    return ports[0].toIntOrNull() ?: selectedRtpPort
                }
            }
        }
        return selectedRtpPort
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

                    // TCP Interleaved 모드일 때 RTP 데이터 수신 시작
                    if (useTcpInterleaved) {
                        startTcpInterleavedReceiving()
                    }
                } else if (response.trim().isEmpty()) {
                    Log.w(TAG, "Empty PLAY response - assuming streaming started")
                    withContext(Dispatchers.Main) {
                        listener?.onPlayStarted()
                    }

                    if (useTcpInterleaved) {
                        startTcpInterleavedReceiving()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        listener?.onError("PLAY failed - Response: $response")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "PLAY response timeout - assuming streaming started")
                withContext(Dispatchers.Main) {
                    listener?.onPlayStarted()
                }

                if (useTcpInterleaved) {
                    startTcpInterleavedReceiving()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send PLAY", e)
            withContext(Dispatchers.Main) {
                listener?.onError("PLAY failed: ${e.message}")
            }
        }
    }

    private fun startTcpInterleavedReceiving() {
        scope.launch {
            try {
                Log.d(TAG, "Starting TCP Interleaved RTP receiving...")
                val inputStream = rtspSocket?.inputStream ?: return@launch
                val buffer = ByteArray(65536)

                while (isActive) {
                    try {
                        // Interleaved frame 헤더 읽기: $ + channel + length(2bytes)
                        val header = ByteArray(4)
                        var bytesRead = 0
                        while (bytesRead < 4) {
                            val read = inputStream.read(header, bytesRead, 4 - bytesRead)
                            if (read == -1) break
                            bytesRead += read
                        }

                        if (bytesRead == 4 && header[0] == '$'.code.toByte()) {
                            val channel = header[1].toInt() and 0xFF
                            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

                            Log.d(TAG, "TCP Interleaved frame: channel=$channel, length=$length")

                            if (length > 0 && length < buffer.size) {
                                // RTP/RTCP 데이터 읽기
                                bytesRead = 0
                                while (bytesRead < length) {
                                    val read = inputStream.read(buffer, bytesRead, length - bytesRead)
                                    if (read == -1) break
                                    bytesRead += read
                                }

                                if (bytesRead == length) {
                                    val data = ByteArray(length)
                                    System.arraycopy(buffer, 0, data, 0, length)

                                    // RTP 데이터 처리
                                    if (channel == 0) { // RTP channel
                                        Log.d(TAG, "TCP RTP data received: $length bytes")
                                        withContext(Dispatchers.Main) {
                                            listener?.onRtpDataReceived(data, true)
                                        }
                                    } else if (channel == 1) { // RTCP channel
                                        Log.d(TAG, "TCP RTCP data received: $length bytes")
                                        withContext(Dispatchers.Main) {
                                            listener?.onRtpDataReceived(data, false)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 정상적인 타임아웃
                        continue
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error in TCP Interleaved receiving", e)
                            delay(100)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP Interleaved receiving failed", e)
            }
        }
    }

    private suspend fun readResponse(): String = withContext(Dispatchers.IO) {
        val response = StringBuilder()
        var contentLength = 0

        try {
            Log.d(TAG, "Starting to read response...")

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

                    if (lineCount > 50) {
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

        Log.d(TAG, "SDP Parsing Result:")
        Log.d(TAG, "  Video track: $videoTrack")
        Log.d(TAG, "  Audio track: $audioTrack")
        Log.d(TAG, "  Video payload type: $videoPayloadType")
        Log.d(TAG, "  Audio payload type: $audioPayloadType")

        if (videoTrack == null) {
            Log.w(TAG, "No video track found, trying common defaults")
            videoTrack = "*"
            Log.d(TAG, "Using default video track: $videoTrack")
        }
    }

    private fun buildSetupUrl(track: String): String {
        Log.d(TAG, "Building SETUP URL for track: '$track'")

        val setupUrl = when {
            track.startsWith("rtsp://") -> {
                Log.d(TAG, "Track is absolute URL")
                track
            }
            track.startsWith("/") -> {
                val base = contentBase ?: "rtsp://$serverHost:$serverPort"
                val url = base.trimEnd('/') + track
                Log.d(TAG, "Track is absolute path, using base: $url")
                url
            }
            track == "*" -> {
                Log.d(TAG, "Track is wildcard, using main URL")
                rtspUrl
            }
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
                    sessionId = if (session.contains(";")) {
                        session.split(";")[0]
                    } else {
                        session
                    }
                    Log.d(TAG, "Session ID: $sessionId")
                }
                line.lowercase().startsWith("transport:") -> {
                    val transport = line.substring(10).trim()

                    // Interleaved 모드 확인
                    if (transport.contains("interleaved=")) {
                        val interleavedStr = transport.split("interleaved=")[1].split(";")[0]
                        val channels = interleavedStr.split("-")
                        interleavedChannel = channels[0].toIntOrNull() ?: 0
                        Log.d(TAG, "TCP Interleaved channel: $interleavedChannel")
                    }

                    // 서버 포트 파싱
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
    val getClientRtpPort: Int get() = selectedRtpPort
    val getClientRtcpPort: Int get() = selectedRtcpPort
    val getServerRtpPort: Int get() = serverRtpPort
    val getServerRtcpPort: Int get() = serverRtcpPort
    val getVideoPayloadType: Int get() = videoPayloadType
    val getSessionId: String? get() = sessionId
    val getVideoTrack: String? get() = videoTrack
    val isUsingTcpInterleaved: Boolean get() = useTcpInterleaved
}