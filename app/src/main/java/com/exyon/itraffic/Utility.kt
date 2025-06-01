package com.exyon.itraffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class Utility {
    companion object{
        var appContext: Context? = null
        var tts: TextToSpeech? = null
        var isInitialized: Boolean = false

        fun speak(text: String?) {
            if (tts == null) {
                tts = TextToSpeech(appContext!!.applicationContext) { status: Int ->
                    if (status == TextToSpeech.SUCCESS) {
                        val result = tts!!.setLanguage(Locale.KOREAN)
                        if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            Log.e("TTSUtil", "한국어 음성 언어를 지원하지 않음")
                        } else {
                            isInitialized = true
                            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1")
                        }
                    } else {
                        Log.e("TTSUtil", "TTS 초기화 실패")
                    }
                }
            } else if (isInitialized) {
                tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1")
            }
        }

        fun getCurrentWifiSSID(context: Context): String? {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 이상
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networks = connectivityManager.allNetworks
                for (network in networks) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        val wifiInfo = wifiManager.connectionInfo
                        val ssid = wifiInfo.ssid
                        return if (ssid != null && ssid != "<unknown ssid>") ssid.replace("\"", "") else null
                    }
                }
                null
            } else {
                // Android 9 이하
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid
                if (ssid != null && ssid != "<unknown ssid>") ssid.replace("\"", "") else null
            }
        }

        val URL: String = "http://192.168.4.1/json"
        var traffic = false
        fun getTrafficStatus(){
            val client = OkHttpClient()
            val request = Request.Builder().url(URL).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("HTTP", "Error: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string()
                    val jsonObject = JSONObject(json)
                    val value = jsonObject.getString("value")
                    traffic = value.toInt() > 600
                }
            })
        }
    }
}