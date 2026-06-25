package com.example.remotecamera.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object YuvToJpegConverter {
    fun convertYuvToJpeg(image: ImageProxy, quality: Int = 80): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        // Prepare NV21 output array: Y followed by interleaved V and U.
        val nv21Bytes = ByteArray(width * height + (width * height / 2))

        // Copy Y data
        var nv21Offset = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21Bytes, 0, width * height)
            nv21Offset = width * height
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                if (yPixelStride == 1) {
                    yBuffer.get(nv21Bytes, nv21Offset, width)
                    nv21Offset += width
                } else {
                    for (col in 0 until width) {
                        nv21Bytes[nv21Offset++] = yBuffer.get(col * yPixelStride)
                    }
                }
            }
        }

        // Interleave V and U data
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var nvIndex = width * height

        for (row in 0 until chromaHeight) {
            val uRowPos = row * uRowStride
            val vRowPos = row * vRowStride

            for (col in 0 until chromaWidth) {
                // Buffer position checks are safer with absolute get(index)
                val uVal = uBuffer.get(uRowPos + col * uPixelStride)
                val vVal = vBuffer.get(vRowPos + col * vPixelStride)

                // NV21 format starts with V, then U (V, U, V, U...)
                nv21Bytes[nvIndex++] = vVal
                nv21Bytes[nvIndex++] = uVal
            }
        }

        // Compress NV21 to JPEG using YuvImage
        val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        val rect = Rect(0, 0, width, height)
        return try {
            yuvImage.compressToJpeg(rect, quality, out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
