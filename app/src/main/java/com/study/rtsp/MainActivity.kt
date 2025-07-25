package com.study.rtsp

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.study.rtsp.rtsp.RtspClient
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

//    private val rtspUrl = "rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mov"
    private val rtspUrl = "rtsp://210.99.70.120:1935/live/cctv001.stream"
    private lateinit var rtspClient: RtspClient
    private val TAG = "mainActivity"
//    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

//        val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
//
//        scope.launch {
//            rtspClient = RtspClient(rtspUrl)
//
//            if (!rtspClient.connect()) {
//                Log.e("RTSP", "Failed to connect")
//                return@launch
//            }
//
//            if (!rtspClient.sendOptions()) {
//                Log.e("RTSP", "OPTIONS failed")
//                return@launch
//            }
//
//            if (!rtspClient.sendDescribe()) {
//                Log.e("RTSP", "DESCRIBE failed")
//                return@launch
//            }
//
//            if (!rtspClient.sendSetup()) {
//                Log.e("RTSP", "SETUP failed")
//                return@launch
//            }
//
//            if (!rtspClient.sendPlay()) {
//                Log.e("RTSP", "PLAY failed")
//                return@launch
//            }
//
//            Log.i("RTSP", "RTSP Session started successfully")
//
//            // 다음: RTP 패킷 수신 및 디코딩 구현 단계
//        }

        val rtspClient = RtspClient(rtspUrl, object : RtspClient.RTSPClientListener {
            override fun onConnected() {
                Log.d(TAG, "RTSP 연결됨")
            }

            override fun onSetupComplete(rtpPort: Int, rtcpPort: Int) {
                Log.d(TAG, "SETUP 완료 - RTP Port: $rtpPort")
                // 여기서 RTPReceiver 시작
            }

            override fun onPlayStarted() {
                Log.d(TAG, "스트리밍 시작")
            }

            override fun onError(error: String) {
                Log.e(TAG, "에러: $error")
            }

            override fun onSdpReceived(sdp: String) {
                Log.d(TAG, "SDP 수신: $sdp")
            }
        })

        rtspClient.connect()
    }

//    override fun onDestroy() {
//        super.onDestroy()
//        rtspClient.sendTeardown()
//        rtspClient.close()
//    }
}
