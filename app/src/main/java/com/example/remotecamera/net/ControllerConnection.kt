package com.example.remotecamera.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class ControllerConnection(private val context: Context) {
    private var socket: Socket? = null
    private val connectionJob = SupervisorJob()
    private val connectionScope = CoroutineScope(Dispatchers.IO + connectionJob)

    private val _viewfinderState = MutableStateFlow<Bitmap?>(null)
    val viewfinderState = _viewfinderState.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _cameraStatus = MutableStateFlow<String>("Idle")
    val cameraStatus = _cameraStatus.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<String>>(emptyList())
    val availableCameras = _availableCameras.asStateFlow()

    private val _activeCameraIndex = MutableStateFlow<Int>(0)
    val activeCameraIndex = _activeCameraIndex.asStateFlow()

    private val _zoomLimits = MutableStateFlow<Pair<Float, Float>>(1.0f to 1.0f)
    val zoomLimits = _zoomLimits.asStateFlow()

    private val _currentZoom = MutableStateFlow<Float>(1.0f)
    val currentZoom = _currentZoom.asStateFlow()

    private val _cameraRotation = MutableStateFlow<Int>(0)
    val cameraRotation = _cameraRotation.asStateFlow()

    private val _isTorchOn = MutableStateFlow<Boolean>(false)
    val isTorchOn = _isTorchOn.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int>(100)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow<Boolean>(false)
    val isCharging = _isCharging.asStateFlow()

    private val _diskSpace = MutableStateFlow<String>("")
    val diskSpace = _diskSpace.asStateFlow()

    private val _galleryFiles = MutableStateFlow<List<GalleryFile>>(emptyList())
    val galleryFiles = _galleryFiles.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float>(0f)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow<Boolean>(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError = _downloadError.asStateFlow()

    sealed interface ConnectionState {
        object Disconnected : ConnectionState
        object Connecting : ConnectionState
        object Connected : ConnectionState
        data class Error(val message: String) : ConnectionState
    }

    companion object {
        private const val TAG = "ControllerConnection"
    }

    fun connect(ip: String, port: Int) {
        disconnect()

        _connectionStatus.value = ConnectionState.Connecting
        connectionScope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 5000)
                socket = s
                _connectionStatus.value = ConnectionState.Connected
                Log.d(TAG, "Connected to remote camera at $ip:$port")

                // Ingest MJPEG stream
                readMjpegStream(s)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionStatus.value = ConnectionState.Error(e.localizedMessage ?: "Connection failed")
            }
        }
    }

    private suspend fun readMjpegStream(s: Socket) {
        val inputStream = BufferedInputStream(s.getInputStream())
        val boundary = "--frame".toByteArray(Charsets.UTF_8)
        
        fun findSequence(data: ByteArray, sequence: ByteArray, start: Int, end: Int): Int {
            if (end - start < sequence.size) return -1
            for (i in start..end - sequence.size) {
                var found = true
                for (j in sequence.indices) {
                    if (data[i + j] != sequence[j]) {
                        found = false
                        break
                    }
                }
                if (found) return i
            }
            return -1
        }

        val buffer = ByteArray(2 * 1024 * 1024) // 2MB buffer
        var bufferLength = 0
        val temp = ByteArray(4096)

        try {
            while (connectionScope.isActive && !s.isClosed) {
                val read = inputStream.read(temp)
                if (read == -1) break

                if (bufferLength + read > buffer.size) {
                    Log.w(TAG, "Buffer overflow: resetting bufferLength")
                    bufferLength = 0 // safeguard against overflow
                }
                System.arraycopy(temp, 0, buffer, bufferLength, read)
                bufferLength += read

                var processedIndex = 0
                while (true) {
                    val firstBoundaryIndex = findSequence(buffer, boundary, processedIndex, bufferLength)
                    if (firstBoundaryIndex == -1) break

                    // Check for FILE_START marker right after firstBoundaryIndex (within 150 bytes)
                    val fileStartBytes = "FILE_START:".toByteArray(Charsets.UTF_8)
                    val markerIndex = findSequence(buffer, fileStartBytes, firstBoundaryIndex, minOf(firstBoundaryIndex + 150, bufferLength))
                    if (markerIndex != -1) {
                        val crlf = "\r\n".toByteArray(Charsets.UTF_8)
                        val lineEndIndex = findSequence(buffer, crlf, markerIndex, bufferLength)
                        if (lineEndIndex == -1) {
                            // We don't have the full FILE_START line yet, wait for more data
                            break
                        }

                        val metaStr = String(buffer, markerIndex, lineEndIndex - markerIndex, Charsets.UTF_8)
                        val nameEnd = metaStr.lastIndexOf(':')
                        if (nameEnd != -1 && nameEnd > "FILE_START:".length) {
                            val fileName = metaStr.substring("FILE_START:".length, nameEnd)
                            val fileSize = metaStr.substring(nameEnd + 1).toLongOrNull() ?: 0L

                            val fileDataStart = lineEndIndex + 2
                            val bytesAlreadyRead = bufferLength - fileDataStart
                            val bytesToWriteFromBuffer = minOf(bytesAlreadyRead.toLong(), fileSize).toInt()

                            _isDownloading.value = true
                            _downloadError.value = null
                            _downloadProgress.value = 0f

                            try {
                                val resolver = context.contentResolver
                                val contentValues = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    val mimeType = when {
                                        fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                                        fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                                        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
                                        else -> "image/jpeg"
                                    }
                                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                                    }
                                }

                                val collectionUri = if (fileName.endsWith(".mp4", ignoreCase = true)) {
                                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                } else {
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                }

                                val uri = resolver.insert(collectionUri, contentValues)
                                if (uri == null) {
                                    throw IOException("Failed to create MediaStore entry")
                                }

                                try {
                                    resolver.openOutputStream(uri)?.use { fos ->
                                        if (bytesToWriteFromBuffer > 0) {
                                            fos.write(buffer, fileDataStart, bytesToWriteFromBuffer)
                                        }

                                        var remaining = fileSize - bytesToWriteFromBuffer
                                        val downloadBuffer = ByteArray(64 * 1024)
                                        while (remaining > 0) {
                                            val toRead = minOf(remaining, downloadBuffer.size.toLong()).toInt()
                                            val readBytes = inputStream.read(downloadBuffer, 0, toRead)
                                            if (readBytes == -1) {
                                                throw IOException("Socket closed during file transfer")
                                            }
                                            fos.write(downloadBuffer, 0, readBytes)
                                            remaining -= readBytes
                                            _downloadProgress.value = (fileSize - remaining).toFloat() / fileSize
                                        }
                                    } ?: throw IOException("Failed to open output stream")

                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        contentValues.clear()
                                        contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                                        resolver.update(uri, contentValues, null, null)
                                    }
                                } catch (e: Exception) {
                                    resolver.delete(uri, null, null)
                                    throw e
                                }

                                _downloadProgress.value = 1.0f
                                Log.d(TAG, "File downloaded successfully via MediaStore to DCIM/Camera: $fileName")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error downloading file", e)
                                _downloadError.value = e.localizedMessage ?: "Failed to save file"
                            } finally {
                                _isDownloading.value = false
                            }

                            processedIndex = fileDataStart + bytesToWriteFromBuffer
                            continue
                        }
                    }

                    val nextBoundaryIndex = findSequence(buffer, boundary, firstBoundaryIndex + boundary.size, bufferLength)
                    if (nextBoundaryIndex == -1) break

                    val frameLength = nextBoundaryIndex - firstBoundaryIndex
                    val frameBytes = ByteArray(frameLength)
                    System.arraycopy(buffer, firstBoundaryIndex, frameBytes, 0, frameLength)

                    // Find where JPEG data starts (SOI marker: 0xFF, 0xD8)
                    val jpegStartIndex = findSequence(frameBytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte()), 0, frameBytes.size)
                    if (jpegStartIndex != -1) {
                        val jpegBytes = ByteArray(frameBytes.size - jpegStartIndex)
                        System.arraycopy(frameBytes, jpegStartIndex, jpegBytes, 0, jpegBytes.size)

                        val bitmap = withContext(Dispatchers.Default) {
                            try {
                                val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                if (raw != null) {
                                    val matrix = android.graphics.Matrix().apply { postRotate(_cameraRotation.value.toFloat()) }
                                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                } else {
                                    null
                                }
                            } catch (e: OutOfMemoryError) {
                                null
                            }
                        }
                        if (bitmap != null) {
                            _viewfinderState.value = bitmap
                        }
                    } else {
                        // Check for inline status feedback
                        try {
                            val text = String(frameBytes, Charsets.UTF_8)
                            if (text.contains("STATUS:")) {
                                val statusMessage = text.substringAfter("STATUS:").substringBefore("\r").substringBefore("\n").trim()
                                Log.d(TAG, "Parsed inline status message: $statusMessage")
                                when {
                                    statusMessage.startsWith("CAMERA_LIST:") -> {
                                        val listStr = statusMessage.removePrefix("CAMERA_LIST:")
                                        _availableCameras.value = listStr.split(",").filter { it.isNotEmpty() }
                                    }
                                    statusMessage.startsWith("CAMERA_INDEX:") -> {
                                        val idx = statusMessage.removePrefix("CAMERA_INDEX:").toIntOrNull()
                                        if (idx != null) {
                                            _activeCameraIndex.value = idx
                                        }
                                    }
                                    statusMessage.startsWith("CAMERA_ROTATION:") -> {
                                        val rot = statusMessage.removePrefix("CAMERA_ROTATION:").toIntOrNull()
                                        if (rot != null) {
                                            _cameraRotation.value = rot
                                        }
                                    }
                                    statusMessage.startsWith("ZOOM_LIMITS:") -> {
                                        val parts = statusMessage.removePrefix("ZOOM_LIMITS:").split(":")
                                        val min = parts.getOrNull(0)?.toFloatOrNull() ?: 1.0f
                                        val max = parts.getOrNull(1)?.toFloatOrNull() ?: 1.0f
                                        _zoomLimits.value = min to max
                                    }
                                    statusMessage.startsWith("ZOOM_VAL:") -> {
                                        val zoomVal = statusMessage.removePrefix("ZOOM_VAL:").toFloatOrNull()
                                        if (zoomVal != null) {
                                            _currentZoom.value = zoomVal
                                        }
                                    }
                                    statusMessage.startsWith("TORCH_STATE:") -> {
                                        val isOn = statusMessage.removePrefix("TORCH_STATE:").toBoolean()
                                        _isTorchOn.value = isOn
                                    }
                                    statusMessage.startsWith("TELEMETRY:") -> {
                                        val parts = statusMessage.removePrefix("TELEMETRY:").split(":")
                                        _batteryLevel.value = parts.getOrNull(0)?.toIntOrNull() ?: 100
                                        _isCharging.value = parts.getOrNull(1)?.toBoolean() ?: false
                                        _diskSpace.value = parts.getOrNull(2) ?: ""
                                    }
                                    statusMessage.startsWith("GALLERY_LIST:") -> {
                                        val listStr = statusMessage.removePrefix("GALLERY_LIST:")
                                        _galleryFiles.value = if (listStr.isEmpty()) emptyList() else {
                                            listStr.split(",").filter { it.isNotEmpty() }.map { fileStr ->
                                                val fileParts = fileStr.split("|")
                                                val name = fileParts.getOrNull(0) ?: ""
                                                val size = fileParts.getOrNull(1)?.toLongOrNull() ?: 0L
                                                val mime = fileParts.getOrNull(2) ?: ""
                                                GalleryFile(name, size, mime)
                                            }
                                        }
                                    }
                                    statusMessage.startsWith("DOWNLOAD_ERROR:") -> {
                                        val errorMsg = statusMessage.removePrefix("DOWNLOAD_ERROR:")
                                        _downloadError.value = errorMsg
                                        _isDownloading.value = false
                                    }
                                    else -> {
                                        _cameraStatus.value = statusMessage
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse inline text message", e)
                        }
                    }

                    processedIndex = nextBoundaryIndex
                }

                // Shift remaining unprocessed data to the start of the buffer
                if (processedIndex > 0) {
                    val remaining = bufferLength - processedIndex
                    if (remaining > 0) {
                        System.arraycopy(buffer, processedIndex, buffer, 0, remaining)
                    }
                    bufferLength = remaining
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "MJPEG stream read error", e)
        } finally {
            _connectionStatus.value = ConnectionState.Disconnected
        }
    }

    fun sendCommand(command: String) {
        val s = socket
        if (s == null || s.isClosed) {
            Log.w(TAG, "Cannot send command, socket not connected")
            return
        }
        connectionScope.launch {
            try {
                val writer = s.getOutputStream().bufferedWriter()
                writer.write("$command\n")
                writer.flush()
                Log.d(TAG, "Sent command: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send command $command", e)
                _connectionStatus.value = ConnectionState.Error("Send failed: ${e.localizedMessage}")
            }
        }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket during disconnect", e)
        }
        socket = null
        _viewfinderState.value = null
        _connectionStatus.value = ConnectionState.Disconnected
        _cameraStatus.value = "Idle"
    }

    fun setRotation(rotation: Int) {
        _cameraRotation.value = rotation
    }
}

data class GalleryFile(
    val name: String,
    val size: Long,
    val mime: String
)
