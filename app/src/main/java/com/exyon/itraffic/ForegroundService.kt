package com.exyon.itraffic

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class ForegroundService : Service() {
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationUtils.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, NotificationUtils.buildServiceNotification(this))

        handler.post(object : Runnable {
            override fun run() {
                checkTrafficSignal()
                handler.postDelayed(this, 5000) // 5초마다 반복
            }
        })

        return START_STICKY
    }

    private fun checkTrafficSignal() {
        val request = Request.Builder()
            .url("http://192.168.4.1/json") // ESP32CAM 주소
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("TrafficCheck", "실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                try {
                    val obj = JSONObject(json)
                    val traffic = obj.getInt("value") > 600
                    if (traffic) {
                        triggerAlert()
                    }
                } catch (e: Exception) {
                    Log.e("TrafficCheck", "JSON 파싱 오류: ${e.message}")
                }
            }
        })
    }

    private fun triggerAlert() {
        NotificationUtils.showAlertNotification(this, "신호 건너세요", "지금 횡단보도를 건너세요!")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
