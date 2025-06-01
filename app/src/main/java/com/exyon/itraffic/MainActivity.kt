package com.exyon.itraffic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.exyon.itraffic.databinding.ActivityMainBinding
import java.io.IOException
import java.util.Objects
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {
    private var previewView: PreviewView? = null
    private lateinit var overlayView: OverlayView
    private var yoloHelper: YoloHelper? = null
    val REQUEST_CODE_PERMISSIONS: Int = 1001
    val TAG: String = "BLE_Scan"
    lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val TARGET_DEVICE_NAME = "iTraffic"
    private val bluetoothGatt: BluetoothGatt? = null

    val HM10_SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
    val HM10_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
    private var wifiReceiver: WifiConnectionReceiver? = null

    var speed: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothManager.adapter.bluetoothLeScanner

        previewView = binding.previewView
        overlayView = binding.overlayView
        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            yoloHelper = YoloHelper(this, "yolov5_640.tflite", "coco.txt")
        } catch (e: IOException) {
            e.printStackTrace()
        }
        Utility.appContext = applicationContext

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
            wifiReceiver = WifiConnectionReceiver()
            registerReceiver(wifiReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            if (Utility.getCurrentWifiSSID(this) == "iTraffic_WIFI") Utility.getTrafficStatus()
            else {
                // BLE 스캔 시작
                startCamera()
                val scanFilters = listOf(
                    ScanFilter.Builder()
                        .setDeviceName("iTraffic")
                        .build()
                )
                val scanSettings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                bluetoothLeScanner!!.startScan(scanFilters, scanSettings, scanCallback)
                connectToiTrafficWifi(this)
            }
        } else {
            if (!Settings.System.canWrite(applicationContext)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.setData(Uri.parse("package:" + applicationContext.getPackageName()))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.startActivity(intent)
            }
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_NETWORK_STATE,
                    //Manifest.permission.WRITE_SETTINGS
                ), 101
            )
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val device = result.device
            val deviceName = device.name

            if (deviceName != null) {
                Log.d(TAG, "Device found: " + deviceName + " (" + device.address + ")")
                if (deviceName.equals(TARGET_DEVICE_NAME, ignoreCase = true)) {
                    Log.d(
                        TAG,
                        "Target device found! Connecting to $deviceName"
                    )
                    stopBleScan()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        device.connectGatt(Utility.appContext, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")) // HM-10 기본 서비스 UUID
                val characteristic = service.getCharacteristic(UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"))
                gatt.setCharacteristicNotification(characteristic, true)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value.toString(Charsets.UTF_8)
                speed = data.filter { it.isDigit() }.toInt()
            }
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopBleScan() {
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner!!.stopScan(scanCallback)
            Log.d(TAG, "BLE scan stopped")
        }
    }

    fun connectToiTrafficWifi(context: Context, ssid: String = "iTraffic_WIFI", password: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectOnAndroid10Plus(context, ssid, password)
        } else {
            connectOnAndroid9OrBelow(context, ssid, password)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectOnAndroid10Plus(context: Context, ssid: String, password: String?) {
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)

        if (!password.isNullOrEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val specifier = specifierBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                connectivityManager.bindProcessToNetwork(network)
                Log.d("WiFi", "iTraffic_WIFI 연결 성공 (Android 10+)")
                Utility.getTrafficStatus()
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e("WiFi", "iTraffic_WIFI 연결 실패 (Android 10+)")
            }
        }

        connectivityManager.requestNetwork(request, callback)
    }

    @Suppress("DEPRECATION")
    private fun connectOnAndroid9OrBelow(context: Context, ssid: String, password: String?) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (!password.isNullOrEmpty()) {
                preSharedKey = "\"$password\""
            } else {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
        }

        val networkId = wifiManager.addNetwork(config)
        if (networkId != -1) {
            wifiManager.disconnect()
            wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()
            Log.d("WiFi", "iTraffic_WIFI 연결 시도 (Android 9 이하)")
            Utility.getTrafficStatus()
        } else {
            Log.e("WiFi", "iTraffic_WIFI 네트워크 추가 실패 (Android 9 이하)")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmap = imageProxy.toBitmap()
                yoloHelper!!.detect(bitmap)

                // 차량 클래스 감지 시 진동
                Utility.getTrafficStatus()
                if (YoloHelper.lastDetectedClasses.isNotEmpty()) {
                    if(speed >10)
                    {
                        vibrateOnce();
                        Utility.speak("차량 접근 중입니다.");
                    }
                    else Utility.speak("차량이 정지했습니다. 조심히 건너세요.");
                }
                else if(Objects.equals(Utility.appContext?.let { Utility.getCurrentWifiSSID(it) }, "iTraffic_WIFI")) {
                    if(Utility.traffic) {
                        vibrateOnce();
                        Utility.speak("초록불입니다. 조심히 건너세요.");
                    }
                    else
                        Utility.speak("빨간불입니다.");
                }

                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun vibrateOnce() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }
    }

    @Suppress("DEPRECATION")
    class WifiConnectionReceiver: BroadcastReceiver()
    {
        override fun onReceive(context: Context, intent: Intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION == intent.action) {
                val connManager =
                    context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkInfo = connManager.activeNetworkInfo

                if (networkInfo != null &&
                    networkInfo.isConnected && networkInfo.type == ConnectivityManager.TYPE_WIFI
                ) {
                    val wifiManager =
                        context.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    val wifiInfo = wifiManager.connectionInfo
                    val ssid = wifiInfo.ssid

                    if (ssid != null && ssid.replace("\"", "") == "iTraffic_WIFI") {
                        Log.d("WifiReceiver", "✅ iTraffic_WIFI에 연결됨!")
                        Utility.getTrafficStatus()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when(requestCode){
            101 -> {
                if (grantResults.isNotEmpty()){
                    var isAllGranted = true
                    // 요청한 권한 허용/거부 상태 한번에 체크
                    for (grant in grantResults) {
                        if (grant != PackageManager.PERMISSION_GRANTED) {
                            isAllGranted = false
                            break;
                        }
                    }

                    // 요청한 권한을 모두 허용했음.
                    if (isAllGranted) {
                        startCamera()
                        wifiReceiver = WifiConnectionReceiver()
                        registerReceiver(wifiReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
                        if (Utility.getCurrentWifiSSID(this) == "iTraffic_WIFI") Utility.getTrafficStatus()
                        else {
                            // BLE 스캔 시작
                            val scanFilters = listOf(
                                ScanFilter.Builder()
                                    .setDeviceName("iTraffic")
                                    .build()
                            )
                            val scanSettings = ScanSettings.Builder()
                                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                .build()
                            bluetoothLeScanner!!.startScan(scanFilters, scanSettings, scanCallback)
                            connectToiTrafficWifi(this)
                        }
                    }
                    // 허용하지 않은 권한이 있음. 필수권한/선택권한 여부에 따라서 별도 처리를 해주어야 함.
                    else {
                        if(!ActivityCompat.shouldShowRequestPermissionRationale(this,
                                Manifest.permission.READ_EXTERNAL_STORAGE)
                            || !ActivityCompat.shouldShowRequestPermissionRationale(this,
                                Manifest.permission.CAMERA)){
                            // 다시 묻지 않기 체크하면서 권한 거부 되었음.
                        } else {
                            // 접근 권한 거부하였음.
                        }
                    }
                }
            }
        }
    }
}