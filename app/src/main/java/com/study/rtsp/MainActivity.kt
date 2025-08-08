package com.study.rtsp

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.study.rtsp.rtsp.H264Parser
import com.study.rtsp.rtsp.RTPReceiver
import com.study.rtsp.rtsp.RtspClient
import java.net.DatagramSocket

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RTSP_URL = "rtsp://210.99.70.120:1935/live/cctv001.stream"
        private const val PAYLOAD_TYPE = 97
    }

    // 컴포넌트들 - 의존성 순서대로 선언
    private lateinit var h264Parser: H264Parser
    private lateinit var rtpReceiver: RTPReceiver
    private lateinit var rtspClient: RtspClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "Starting RTSP streaming application")

        // 포트 사용 가능 여부 테스트
        testPortAvailability()

        // 의존성 순서에 맞게 초기화하고 시작
        initializeComponents()
    }

    /**
     * 컴포넌트들을 의존성 순서에 맞게 초기화
     * H264Parser → RTPReceiver → RtspClient 순서로 초기화하고 즉시 시작
     */
    private fun initializeComponents() {
        try {
            // 1. H264Parser 먼저 초기화 (다른 컴포넌트들이 의존)
            initH264Parser()

            // 2. RTPReceiver 초기화 (H264Parser 의존)
            initRTPReceiver()

            // 3. RtspClient 초기화하고 즉시 연결 시작 (RTPReceiver 의존)
            initRtspClient()

            Log.d(TAG, "All components initialized and RTSP connection started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize components", e)
            finish()
        }
    }

    /**
     * H264Parser 초기화
     * - 가장 하위 레벨 컴포넌트로 다른 의존성 없음
     */
    private fun initH264Parser() {
        Log.d(TAG, "Initializing H264Parser...")

        h264Parser = H264Parser(object : H264Parser.H264ParserListener {
            override fun onSPSParsed(sps: ByteArray, width: Int, height: Int, fps: Float) {
                Log.i(TAG, "✓ SPS parsed successfully - ${width}x${height} @${fps}fps")
                // TODO: MediaCodec 설정에 사용
            }

            override fun onPPSParsed(pps: ByteArray) {
                Log.i(TAG, "✓ PPS parsed successfully - ${pps.size} bytes")
                // TODO: MediaCodec 설정에 사용
            }

            override fun onFrameReady(frame: H264Parser.H264Frame) {
                Log.d(TAG, "✓ Frame ready - Type: ${frame.frameType}, Size: ${frame.data.size} bytes, KeyFrame: ${frame.isKeyFrame}")
                // TODO: MediaCodecDecoder로 전달
                handleParsedFrame(frame)
            }

            override fun onError(error: String) {
                Log.e(TAG, "✗ H264Parser error: $error")
            }

            override fun onStatistics(stats: H264Parser.ParserStatistics) {
                Log.d(TAG, "H264Parser Stats - Total: ${stats.totalFramesParsed}, I: ${stats.iFramesParsed}, P: ${stats.pFramesParsed}, Errors: ${stats.parseErrors}")
            }
        })

        h264Parser.startParsing()
        Log.d(TAG, "H264Parser started")
    }

    /**
     * RTPReceiver 초기화
     * - H264Parser 의존 (파싱된 NAL Unit을 전달)
     */
    private fun initRTPReceiver() {
        Log.d(TAG, "Initializing RTPReceiver...")

        rtpReceiver = RTPReceiver(0, PAYLOAD_TYPE, object : RTPReceiver.RTPReceiverListener {
            override fun onNALUnitReceived(nalUnit: ByteArray, timestamp: Long) {
                // ★ 핵심: RTP에서 받은 NAL Unit을 H264Parser로 전달
                h264Parser.parseNALUnit(nalUnit, timestamp)
            }

            override fun onSPSReceived(sps: ByteArray) {
                Log.i(TAG, "RTP SPS received: ${sps.size} bytes")
                // H264Parser에서도 처리되지만, 직접적인 SPS 정보로도 활용 가능
            }

            override fun onPPSReceived(pps: ByteArray) {
                Log.i(TAG, "RTP PPS received: ${pps.size} bytes")
                // H264Parser에서도 처리되지만, 직접적인 PPS 정보로도 활용 가능
            }

            override fun onError(error: String) {
                Log.e(TAG, "✗ RTP Error: $error")
            }

            override fun onStatistics(stats: RTPReceiver.RtpStatistics) {
                // 5초마다 한번씩만 로그 (너무 빈번하면 로그 스팸)
                if (stats.packetsReceived % 100 == 0) {
                    Log.d(TAG, "RTP Stats - Received: ${stats.packetsReceived}, Lost: ${stats.packetsLost}, " +
                            "Bitrate: ${(stats.bitrate/1000).toInt()}kbps, Jitter: ${String.format("%.1f", stats.jitter)}ms")
                }
            }
        })

        Log.d(TAG, "RTPReceiver initialized")
    }

    /**
     * RtspClient 초기화 및 연결 시작
     * - RTPReceiver 의존 (RTP 데이터 전달 및 포트 관리)
     */
    private fun initRtspClient() {
        Log.d(TAG, "Initializing RtspClient...")

        rtspClient = RtspClient(RTSP_URL, object : RtspClient.RTSPClientListener {
            override fun onConnected() {
                Log.i(TAG, "✓ RTSP connected successfully")
            }

            override fun onSetupComplete(rtpPort: Int, rtcpPort: Int, isTcp: Boolean) {
                if (isTcp) {
                    Log.i(TAG, "✓ RTSP SETUP complete - TCP Interleaved mode")
                } else {
                    Log.i(TAG, "✓ RTSP SETUP complete - UDP mode (RTP: $rtpPort, RTCP: $rtcpPort)")
                    handleUdpSetup(rtpPort)
                }
            }

            override fun onPlayStarted() {
                Log.i(TAG, "✓ RTSP streaming started")
                handleStreamingStart()
            }

            override fun onError(error: String) {
                Log.e(TAG, "✗ RTSP error: $error")
            }

            override fun onSdpReceived(sdp: String) {
                Log.d(TAG, "SDP received:\n$sdp")
            }

            override fun onRtpDataReceived(data: ByteArray, isRtp: Boolean) {
                // TCP Interleaved 모드에서 RTP 데이터 수신
                if (isRtp) {
                    // ★ 핵심: TCP로 받은 RTP 데이터를 RTPReceiver로 전달
                    rtpReceiver.processTcpRtpData(data)
                } else {
                    // RTCP 데이터는 현재 사용하지 않음
                    Log.v(TAG, "RTCP data received: ${data.size} bytes")
                }
            }
        })

        Log.d(TAG, "RtspClient initialized")

        // 초기화 완료 즉시 연결 시작
        Log.d(TAG, "Starting RTSP connection...")
        rtspClient.connect()
    }

    /**
     * UDP 모드일 때 RTPReceiver 재구성
     */
    private fun handleUdpSetup(rtpPort: Int) {
        if (rtpPort > 0) {
            Log.d(TAG, "Reconfiguring RTPReceiver for UDP port $rtpPort")

            // 기존 RTPReceiver 중지
            rtpReceiver.stopReceiving()

            // 새로운 포트로 RTPReceiver 재생성 (리스너는 동일하게 유지)
            rtpReceiver = RTPReceiver(rtpPort, PAYLOAD_TYPE, rtpReceiver.listener)
        }
    }

    /**
     * 스트리밍 시작 처리
     */
    private fun handleStreamingStart() {
        if (!rtspClient.isUsingTcpInterleaved) {
            // UDP 모드: RTPReceiver 시작
            Log.d(TAG, "Starting RTPReceiver for UDP mode")
            rtpReceiver.startReceiving()
        } else {
            // TCP Interleaved 모드: 별도 RTPReceiver 시작 불필요
            Log.d(TAG, "TCP Interleaved mode - RTP data received via RTSP socket")
        }
    }

    /**
     * 파싱된 프레임 처리
     */
    private fun handleParsedFrame(frame: H264Parser.H264Frame) {
        // TODO: 여기서 MediaCodecDecoder로 프레임 전달
        // mediaCodecDecoder.decodeFrame(frame)

        // 임시로 키프레임만 로그
        if (frame.isKeyFrame) {
            Log.i(TAG, "★ Key frame received - ${frame.data.size} bytes, ${frame.nalUnits.size} NAL units")
        }
    }



    /**
     * 포트 사용 가능 여부 테스트
     */
    private fun testPortAvailability() {
        Log.d(TAG, "Testing UDP port availability...")
        val portsToTest = listOf(5004, 6000, 7000, 8000)

        for (port in portsToTest) {
            try {
                DatagramSocket(port).use { socket ->
                    Log.d(TAG, "✓ Port $port is available")
                }
            } catch (e: Exception) {
                Log.w(TAG, "✗ Port $port is not available: ${e.message}")
            }
        }
    }

    /**
     * 리소스 정리
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Cleaning up resources...")

        try {
            // 역순으로 정리 (초기화의 반대 순서)
            if (::rtspClient.isInitialized) {
                rtspClient.disconnect()
            }

            if (::rtpReceiver.isInitialized) {
                rtpReceiver.stopReceiving()
            }

            if (::h264Parser.isInitialized) {
                h264Parser.stopParsing()
            }

            Log.d(TAG, "All resources cleaned up successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * 현재 스트리밍 상태 정보 (디버깅용)
     */
    private fun logCurrentStatus() {
        if (::h264Parser.isInitialized && ::rtpReceiver.isInitialized) {
            val parserStats = h264Parser.getStatistics()
            val rtpStats = rtpReceiver.getStatistics()

            Log.d(TAG, "=== Current Status ===")
            Log.d(TAG, "RTP: Received=${rtpStats.packetsReceived}, Lost=${rtpStats.packetsLost}")
            Log.d(TAG, "H264: Frames=${parserStats.totalFramesParsed}, I=${parserStats.iFramesParsed}, P=${parserStats.pFramesParsed}")
            Log.d(TAG, "Config Ready: ${if (h264Parser.isConfigurationReady()) "✓" else "✗"}")
        }
    }
}