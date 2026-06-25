package com.example.remotecamera.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class YuvToJpegConverterTest {

    @Test
    fun testNonYuvFormatReturnsNull() {
        val fakeImage = mock(ImageProxy::class.java)
        `when`(fakeImage.format).thenReturn(ImageFormat.JPEG)
        `when`(fakeImage.width).thenReturn(100)
        `when`(fakeImage.height).thenReturn(100)

        val result = YuvToJpegConverter.convertYuvToJpeg(fakeImage)
        assertNull(result)
    }
}
