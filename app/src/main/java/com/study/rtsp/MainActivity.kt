package com.study.rtsp

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.study.rtsp.rtsp.RTPReceiver
import com.study.rtsp.rtsp.RtspClient
import kotlinx.coroutines.*
import java.net.DatagramSocket

class MainActivity : AppCompatActivity() {

    private val rtspUrl = "rtsp://210.99.70.120:1935/live/cctv001.stream"
    private lateinit var rtspClient: RtspClient
    private lateinit var rtpReceiver: RTPReceiver
    private val TAG = "mainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 포트 사용 가능 여부 테스트
        testPortAvailability()

        // RTP Receiver 생성 (포트는 RTSP 협상 후 결정)
        rtpReceiver = RTPReceiver(0, 97, object : RTPReceiver.RTPReceiverListener {
            override fun onNALUnitReceived(nalUnit: ByteArray, timestamp: Long) {
                Log.d(TAG, "NAL Unit received: ${nalUnit.size} bytes, timestamp: $timestamp")
                // MediaCodec에 전달
            }

            override fun onSPSReceived(sps: ByteArray) {
                Log.d(TAG, "SPS received: ${sps.size} bytes")
                // MediaFormat 설정에 사용
            }

            override fun onPPSReceived(pps: ByteArray) {
                Log.d(TAG, "PPS received: ${pps.size} bytes")
                // MediaFormat 설정에 사용
            }

            override fun onError(error: String) {
                Log.e(TAG, "RTP Error: $error")
            }

            override fun onStatistics(stats: RTPReceiver.RtpStatistics) {
                Log.d(TAG, "RTP 상세 통계:")
                Log.d(TAG, "  수신: ${stats.packetsReceived}")
                Log.d(TAG, "  손실: ${stats.packetsLost}")
                Log.d(TAG, "  순서변경: ${stats.packetsOutOfOrder}")
                Log.d(TAG, "  중복: ${stats.duplicatePackets}")
                Log.d(TAG, "  비트레이트: ${stats.bitrate.toInt()} bps")
                Log.d(TAG, "  지터: ${String.format("%.2f", stats.jitter)} ms")
            }
        })

        // RTSP Client 생성
        rtspClient = RtspClient(rtspUrl, object : RtspClient.RTSPClientListener {
            override fun onConnected() {
                Log.d(TAG, "RTSP 연결됨")
            }

            override fun onSetupComplete(rtpPort: Int, rtcpPort: Int, isTcp: Boolean) {
                if (isTcp) {
                    Log.d(TAG, "SETUP 완료 - TCP Interleaved 모드")
                } else {
                    Log.d(TAG, "SETUP 완료 - UDP 모드, RTP Port: $rtpPort, RTCP Port: $rtcpPort")
                    // UDP 모드일 때만 새로운 포트로 RTPReceiver 재생성
                    if (rtpPort > 0) {
                        rtpReceiver.stopReceiving()
                        rtpReceiver = RTPReceiver(rtpPort, 97, rtpReceiver.listener)
                    }
                }
            }

            override fun onPlayStarted() {
                Log.d(TAG, "스트리밍 시작")
                if (!rtspClient.isUsingTcpInterleaved) {
                    // UDP 모드일 때만 RTPReceiver 시작
                    rtpReceiver.startReceiving()
                } else {
                    Log.d(TAG, "TCP Interleaved 모드 - RTP 데이터는 RTSP 소켓으로 수신")
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "RTSP 에러: $error")
            }

            override fun onSdpReceived(sdp: String) {
                Log.d(TAG, "SDP 수신: $sdp")
            }

            override fun onRtpDataReceived(data: ByteArray, isRtp: Boolean) {
                // TCP Interleaved 모드에서 RTP 데이터 수신
                if (isRtp) {
                    Log.d(TAG, "TCP RTP 데이터 수신: ${data.size} bytes")
                    rtpReceiver.processTcpRtpData(data)
                } else {
                    Log.d(TAG, "TCP RTCP 데이터 수신: ${data.size} bytes")
                }
            }
        })

        // RTP 통계 모니터링 (10초마다)
//        startRtpStatisticsMonitoring()

        // RTSP 연결 시작
        rtspClient.connect()
    }

    private fun testPortAvailability() {
        Log.d(TAG, "Testing port availability...")
        val portsToTest = listOf(5004, 6000, 7000, 8000)

        for (port in portsToTest) {
            try {
                val testSocket = DatagramSocket(port)
                Log.d(TAG, "Port $port is available")
                testSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Port $port is NOT available: ${e.message}")
            }
        }
    }

//    private fun startRtpStatisticsMonitoring() {
//        CoroutineScope(Dispatchers.Main).launch {
//            while (true) {
//                delay(10000) // 10초마다
//                val stats = rtpReceiver.getStatistics()
//                Log.d(TAG, "RTP 통계 - 수신: ${stats.first}, 손실: ${stats.second}")
//
//                // 30초 동안 패킷을 하나도 받지 못했다면 경고
//                if (stats.first == 0) {
//                    Log.w(TAG, "WARNING: No RTP packets received yet")
//                }
//            }
//        }
//    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying MainActivity - cleaning up resources")

        try {
            rtpReceiver.stopReceiving()
            rtspClient.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}