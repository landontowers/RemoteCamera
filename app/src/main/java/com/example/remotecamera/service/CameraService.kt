package com.example.remotecamera.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.remotecamera.MainActivity
import com.example.remotecamera.camera.YuvToJpegConverter
import com.example.remotecamera.net.NsdHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class CameraService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var nsdHelper: NsdHelper
    private var serverSocket: ServerSocket? = null
    private var activeClientSocket: Socket? = null

    // CameraX use cases
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: androidx.camera.core.Camera? = null
    private var activeCameraIndex = 0
    @Volatile
    private var isStreamingEnabled = true
    private var telemetryJob: kotlinx.coroutines.Job? = null
    private var isFlashEnabled = false

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val writeLock = Any()

    @Volatile
    private var isWritingFrame = false

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "camera_service_channel"

        // Exposing service state to Local UI
        val isRunning = MutableStateFlow(false)
        val serverPort = MutableStateFlow(0)
        val clientConnected = MutableStateFlow(false)
        val clientIp = MutableStateFlow<String?>(null)
        val isRecording = MutableStateFlow(false)
        val systemMessage = MutableStateFlow<String?>(null)

        fun start(context: Context) {
            val intent = Intent(context, CameraService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CameraService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        nsdHelper = NsdHelper(this)
        isRunning.value = true
        systemMessage.value = "Starting service..."

        createNotificationChannel()
        startForegroundServiceWithNotification()

        startServerSocket()
        setupCamera()
        startTelemetryUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Camera Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Remote Camera Server running in the background."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Camera Server Active")
            .setContentText("Camera stream and recording triggers are running.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServerSocket() {
        serviceScope.launch {
            try {
                val server = ServerSocket(0) // ephemeral port
                serverSocket = server
                val port = server.localPort
                serverPort.value = port
                Log.d(TAG, "Server socket listening on port $port")
                systemMessage.value = "Server listening on port $port"

                nsdHelper.registerService(port)

                while (isActive) {
                    val socket = server.accept()
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error or closed", e)
                systemMessage.value = "Server error: ${e.localizedMessage}"
            }
        }
    }

    private fun handleClient(socket: Socket) {
        closeActiveClient()

        activeClientSocket = socket
        clientConnected.value = true
        clientIp.value = socket.inetAddress.hostAddress
        systemMessage.value = "Client connected from ${socket.inetAddress.hostAddress}"
        Log.d(TAG, "Client connected: ${socket.inetAddress.hostAddress}")

        sendCameraDetails()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val reader = socket.getInputStream().bufferedReader()
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    handleCommand(line.trim())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client socket read error", e)
            } finally {
                closeActiveClient()
            }
        }
    }

    private fun closeActiveClient() {
        synchronized(writeLock) {
            activeClientSocket?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing client socket", e)
                }
            }
            activeClientSocket = null
            clientConnected.value = false
            clientIp.value = null
            systemMessage.value = "Client disconnected"
        }
    }

    private fun handleCommand(command: String) {
        Log.d(TAG, "Received command: $command")
        when {
            command == "TAKE_PHOTO" -> takePhoto()
            command == "START_RECORDING" -> startRecording()
            command == "STOP_RECORDING" -> stopRecording()
            command == "PAUSE_STREAM" -> {
                isStreamingEnabled = false
                Log.d(TAG, "Viewfinder streaming paused")
            }
            command == "RESUME_STREAM" -> {
                isStreamingEnabled = true
                Log.d(TAG, "Viewfinder streaming resumed")
            }
            command == "TOGGLE_TORCH" -> {
                isFlashEnabled = !isFlashEnabled
                sendFeedbackToClient("TORCH_STATE:$isFlashEnabled")
                // For photos, flash fires only at the moment of capture (see
                // takePhoto()'s ImageCapture.flashMode) rather than staying lit as a
                // torch. A continuous torch is only correct while actively recording,
                // since a video can't use a discrete per-frame flash pulse.
                if (activeRecording != null) {
                    ContextCompat.getMainExecutor(this).execute {
                        camera?.cameraControl?.enableTorch(isFlashEnabled)
                    }
                }
            }
            command == "GET_GALLERY" -> {
                val files = getGalleryFiles()
                val listStr = files.map { "${it.name}|${it.size}|${it.mime}" }.joinToString(",")
                sendFeedbackToClient("GALLERY_LIST:$listStr")
            }
            command.startsWith("DOWNLOAD_FILE:") -> {
                val fileName = command.removePrefix("DOWNLOAD_FILE:")
                downloadFile(fileName)
            }
            command.startsWith("SET_CAMERA:") -> {
                val index = command.substringAfter("SET_CAMERA:").toIntOrNull()
                if (index != null) {
                    activeCameraIndex = index
                    ContextCompat.getMainExecutor(this).execute {
                        setupCamera()
                    }
                }
            }
            command.startsWith("SET_ZOOM:") -> {
                val zoomVal = command.substringAfter("SET_ZOOM:").toFloatOrNull()
                if (zoomVal != null) {
                    ContextCompat.getMainExecutor(this).execute {
                        camera?.cameraControl?.setZoomRatio(zoomVal)
                    }
                }
            }
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }

    private fun writeFrameToClient(jpegBytes: ByteArray) {
        if (!isStreamingEnabled) return
        if (isWritingFrame) return
        val client = activeClientSocket ?: return
        if (client.isClosed) return

        isWritingFrame = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                synchronized(writeLock) {
                    val outputStream = client.getOutputStream()
                    val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n"
                    outputStream.write(header.toByteArray(Charsets.UTF_8))
                    outputStream.write(jpegBytes)
                    outputStream.write("\r\n".toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing frame to client, disconnecting", e)
                closeActiveClient()
            } finally {
                isWritingFrame = false
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val available = provider.availableCameraInfos
                if (activeCameraIndex < 0 || activeCameraIndex >= available.size) {
                    activeCameraIndex = 0
                }
                val targetInfo = available.getOrNull(activeCameraIndex)
                val cameraSelector = if (targetInfo != null) {
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { it == targetInfo }
                        }
                        .build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // 1. Viewfinder stream (low latency analysis)
                // setTargetResolution is only a hint CameraX can ignore entirely; on some
                // devices/hardware levels it was falling back to a square (1:1) analysis
                // stream instead of 4:3. ResolutionSelector's AspectRatioStrategy actually
                // constrains the aspect ratio of the stream CameraX picks.
                val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis!!.setAnalyzer(cameraExecutor) { imageProxy ->
                    val jpegBytes = YuvToJpegConverter.convertYuvToJpeg(imageProxy, 70)
                    if (jpegBytes != null) {
                        writeFrameToClient(jpegBytes)
                    }
                    imageProxy.close()
                }

                // 2. High-res snapshots
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                // 3. Local recording engine
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.UHD, FallbackStrategy.higherQualityOrLowerThan(Quality.HIGHEST)))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis,
                    imageCapture,
                    videoCapture
                )
                camera = boundCamera

                sendCameraDetails()

                boundCamera.cameraInfo.zoomState.observe(this) { state ->
                    sendFeedbackToClient("ZOOM_VAL:${state.zoomRatio}")
                }

                // Unified flash/torch state is managed via isFlashEnabled setting

                systemMessage.value = "Camera bound and ready"
                Log.d(TAG, "Camera setup successfully bound")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up camera", e)
                systemMessage.value = "Camera bind error: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun getPhysicalZoomPresets(cameraInfo: androidx.camera.core.CameraInfo): List<Float> {
        val presets = mutableListOf<Float>()
        try {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val logicalCameraId = camera2Info.cameraId
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)

            // Track (focalLength, sensorWidthMm) pairs rather than bare focal lengths.
            // A longer focal length on a physically smaller sensor crops further, so raw
            // focal-length ratios alone understate zoom for lenses with a different sensor
            // size (e.g. a telephoto lens marketed as "5x" can have a focal length only
            // ~2.6x the main lens's, with its smaller sensor accounting for the rest).
            val lensSpecs = mutableListOf<Pair<Float, Float>>()

            fun collectLensSpecs(specs: CameraCharacteristics) {
                val lengths = specs.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorWidth = specs.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
                if (lengths != null && sensorWidth != null && sensorWidth > 0f) {
                    lengths.forEach { lensSpecs.add(it to sensorWidth) }
                }
            }

            collectLensSpecs(characteristics)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (physicalId in characteristics.physicalCameraIds) {
                    collectLensSpecs(cameraManager.getCameraCharacteristics(physicalId))
                }
            }

            // Keep one sensor width per distinct focal length (the first/primary physical
            // id for that lens — some devices expose extra binned-resolution variants of
            // the same lens under separate physical ids with identical focal lengths).
            val uniqueLensSpecs = lensSpecs.distinctBy { it.first }
            if (uniqueLensSpecs.isNotEmpty()) {
                val focalLengths = uniqueLensSpecs.map { it.first }
                // Baseline standard focal length (normally between 3.5mm and 6.0mm)
                val baselineFocal = focalLengths.firstOrNull { it in 3.5f..6.0f }
                    ?: focalLengths.firstOrNull { it >= 3.0f }
                    ?: focalLengths.first()
                val baselineSpec = uniqueLensSpecs.first { it.first == baselineFocal }
                val baselineCropRatio = baselineSpec.first / baselineSpec.second

                for ((focal, sensorWidth) in uniqueLensSpecs) {
                    val ratio = (focal / sensorWidth) / baselineCropRatio
                    // Round to one decimal place, e.g. 0.5, 1.0
                    val roundedRatio = Math.round(ratio * 10f) / 10f
                    // Trust this corrected ratio for the main lens and any wider-angle
                    // lens (ultra-wide), where it's reliably accurate. A longer lens's
                    // true marketed zoom factor depends on vendor tuning beyond simple
                    // crop math, so leave that to the digital zoom fallback below,
                    // which supplies clean round numbers (2x, 5x) instead.
                    if (roundedRatio in 0.2f..1.05f && !presets.contains(roundedRatio)) {
                        presets.add(roundedRatio)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating physical zoom presets", e)
        }

        // Ensure 1.0f is always present
        if (!presets.contains(1.0f)) {
            presets.add(1.0f)
        }

        // Add helpful digital/telephoto zoom levels (e.g., 2.0x, 5.0x) if they fit inside bounds and are not covered
        cameraInfo.zoomState.value?.let { state ->
            val maxZoom = state.maxZoomRatio
            if (maxZoom >= 2.0f && presets.none { Math.abs(it - 2.0f) < 0.2f }) {
                presets.add(2.0f)
            }
            if (maxZoom >= 5.0f && presets.none { Math.abs(it - 5.0f) < 0.2f }) {
                presets.add(5.0f)
            }
        }
        
        return presets.sorted()
    }

    private fun sendCameraDetails() {
        val provider = cameraProvider ?: return
        val available = provider.availableCameraInfos
        val cameraNames = available.mapIndexed { index, info ->
            val lensFacing = info.lensFacing
            val facingName = when (lensFacing) {
                CameraSelector.LENS_FACING_BACK -> "Back Camera"
                CameraSelector.LENS_FACING_FRONT -> "Front Camera"
                CameraSelector.LENS_FACING_EXTERNAL -> "External Camera"
                else -> "Camera"
            }
            "$facingName $index"
        }.joinToString(",")

        sendFeedbackToClient("CAMERA_LIST:$cameraNames")
        sendFeedbackToClient("CAMERA_INDEX:$activeCameraIndex")
        sendFeedbackToClient("TORCH_STATE:$isFlashEnabled")

        camera?.let { cam ->
            sendFeedbackToClient("CAMERA_ROTATION:${cam.cameraInfo.sensorRotationDegrees}")
            cam.cameraInfo.zoomState.value?.let { state ->
                sendFeedbackToClient("ZOOM_LIMITS:${state.minZoomRatio}:${state.maxZoomRatio}")
                sendFeedbackToClient("ZOOM_VAL:${state.zoomRatio}")
            }
            val presets = getPhysicalZoomPresets(cam.cameraInfo)
            sendFeedbackToClient("ZOOM_PRESETS:${presets.joinToString(",")}")
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        capture.flashMode = if (isFlashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RemoteCamera")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    systemMessage.value = "Photo captured successfully"
                    sendFeedbackToClient("PHOTO_SUCCESS: ${outputFileResults.savedUri}")
                    Log.d(TAG, "Photo saved to ${outputFileResults.savedUri}")
                }

                override fun onError(exception: ImageCaptureException) {
                    systemMessage.value = "Photo capture failed"
                    sendFeedbackToClient("PHOTO_ERROR: ${exception.localizedMessage}")
                    Log.e(TAG, "Photo error", exception)
                }
            }
        )
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        if (activeRecording != null) return

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "REC_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/RemoteCamera")
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val recordingBuilder = capture.output.prepareRecording(this, mediaStoreOutput)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recordingBuilder.withAudioEnabled()
        }

        activeRecording = recordingBuilder.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    isRecording.value = true
                    systemMessage.value = "Recording video..."
                    sendFeedbackToClient("RECORD_STARTED")
                    if (isFlashEnabled) {
                        camera?.cameraControl?.enableTorch(true)
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording.value = false
                    activeRecording = null
                    camera?.cameraControl?.enableTorch(false)
                    if (recordEvent.hasError()) {
                        systemMessage.value = "Recording failed: ${recordEvent.error}"
                        sendFeedbackToClient("RECORD_ERROR: ${recordEvent.error}")
                        Log.e(TAG, "Video recording error: ${recordEvent.error}")
                    } else {
                        systemMessage.value = "Video saved successfully"
                        sendFeedbackToClient("RECORD_FINISHED: ${recordEvent.outputResults.outputUri}")
                        Log.d(TAG, "Video saved to ${recordEvent.outputResults.outputUri}")
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
        }
    }

    private fun sendFeedbackToClient(message: String) {
        val client = activeClientSocket ?: return
        if (client.isClosed) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                synchronized(writeLock) {
                    val outputStream = client.getOutputStream()
                    val payload = "STATUS:$message"
                    val header = "--frame\r\nContent-Type: text/plain\r\nContent-Length: ${payload.length}\r\n\r\n"
                    outputStream.write(header.toByteArray(Charsets.UTF_8))
                    outputStream.write(payload.toByteArray(Charsets.UTF_8))
                    outputStream.write("\r\n".toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send status update to client", e)
            }
        }
    }

    override fun onDestroy() {
        isRunning.value = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        // Cleanup telemetry
        telemetryJob?.cancel()

        // Cleanup networking
        nsdHelper.unregisterService()
        closeActiveClient()
        serverSocket?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket", e)
            }
        }

        // Cleanup CameraX
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startTelemetryUpdates() {
        telemetryJob = serviceScope.launch {
            while (isActive) {
                if (clientConnected.value) {
                    val battery = getBatteryPercentage()
                    val charging = isBatteryCharging()
                    val disk = getAvailableDiskSpace()
                    sendFeedbackToClient("TELEMETRY:$battery:$charging:$disk")
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun getBatteryPercentage(): Int {
        val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level == -1 || scale == -1) return 100
        return ((level.toFloat() / scale.toFloat()) * 100.0f).toInt()
    }

    private fun isBatteryCharging(): Boolean {
        val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getAvailableDiskSpace(): String {
        return try {
            val path = android.os.Environment.getExternalStorageDirectory()
            val stat = android.os.StatFs(path.path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val gb = bytesAvailable.toFloat() / (1024f * 1024f * 1024f)
            String.format("%.1f GB free", gb)
        } catch (e: Exception) {
            "Unknown space"
        }
    }

    private fun getGalleryFiles(): List<GalleryFile> {
        val files = mutableListOf<GalleryFile>()
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns.DATE_ADDED,
            android.provider.MediaStore.MediaColumns.MIME_TYPE
        )

        // Query Images
        contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%DCIM/RemoteCamera%"),
            "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val size = cursor.getLong(sizeCol)
                val mime = cursor.getString(mimeCol)
                val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                files.add(GalleryFile(name, size, mime, uri.toString()))
            }
        }

        // Query Videos
        contentResolver.query(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%DCIM/RemoteCamera%"),
            "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val size = cursor.getLong(sizeCol)
                val mime = cursor.getString(mimeCol)
                val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                files.add(GalleryFile(name, size, mime, uri.toString()))
            }
        }

        return files
    }

    private fun downloadFile(fileName: String) {
        val files = getGalleryFiles()
        val target = files.find { it.name == fileName }
        if (target == null) {
            sendFeedbackToClient("DOWNLOAD_ERROR:File not found")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(target.uriString)
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    sendFeedbackToClient("DOWNLOAD_ERROR:Cannot open stream")
                    return@launch
                }

                synchronized(writeLock) {
                    val outputStream = activeClientSocket?.getOutputStream() ?: return@synchronized
                    val payload = "FILE_START:${target.name}:${target.size}"
                    val header = "--frame\r\nContent-Type: text/plain\r\nContent-Length: ${payload.length}\r\n\r\n"
                    outputStream.write(header.toByteArray(Charsets.UTF_8))
                    outputStream.write(payload.toByteArray(Charsets.UTF_8))
                    outputStream.write("\r\n".toByteArray(Charsets.UTF_8))
                    outputStream.flush()

                    // Stream raw binary file block directly
                    inputStream.use { input ->
                        input.copyTo(outputStream)
                    }
                    outputStream.flush()
                }
                Log.d(TAG, "File sent successfully: $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "File send error", e)
                sendFeedbackToClient("DOWNLOAD_ERROR:${e.localizedMessage}")
            }
        }
    }
}

data class GalleryFile(
    val name: String,
    val size: Long,
    val mime: String,
    val uriString: String
)
