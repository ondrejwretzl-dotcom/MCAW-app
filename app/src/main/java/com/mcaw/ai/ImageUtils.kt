package com.mcaw.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ImageUtils {

    // Reuse to reduce allocations in fallback YUV->JPEG path.
    private val jpegStream = ByteArrayOutputStream(1024 * 256)

    /**
     * Fast path for ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888.
     * Fallback path for YUV_420_888 uses YuvImage->JPEG (slower).
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            when (image.format) {
                PixelFormat.RGBA_8888 -> rgba8888ToBitmap(image)
                ImageFormat.YUV_420_888 -> yuvToBitmapFallback(image)
                else -> yuvToBitmapFallback(image)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rgba8888ToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // If rowStride == width*4 we can copy directly. Otherwise, compact rows.
        if (rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        val tmp = ByteArray(rowStride * height)
        buffer.get(tmp)

        val out = ByteBuffer.allocateDirect(width * height * 4)
        var src = 0
        val rowBytes = width * 4
        for (row in 0 until height) {
            out.put(tmp, src, rowBytes)
            src += rowStride
        }
        out.rewind()
        bitmap.copyPixelsFromBuffer(out)
        return bitmap
    }

    private fun yuvToBitmapFallback(image: ImageProxy): Bitmap? {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        synchronized(jpegStream) {
            jpegStream.reset()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, jpegStream)
            val jpegBytes = jpegStream.toByteArray()
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        }
    }

    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * YUV_420_888 (CameraX) -> NV21 (Y + VU interleaved), respects rowStride/pixelStride.
     */
    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val width = image.width
        val height = image.height

        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        // Copy Y
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var outIndex = 0

        for (row in 0 until height) {
            var inIndex = row * yRowStride
            for (col in 0 until width) {
                out[outIndex++] = yBuffer.get(inIndex)
                inIndex += yPixelStride
            }
        }

        // Copy VU (NV21)
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        var uvOutIndex = ySize
        for (row in 0 until chromaHeight) {
            var uIn = row * uRowStride
            var vIn = row * vRowStride
            for (col in 0 until chromaWidth) {
                out[uvOutIndex++] = vBuffer.get(vIn)
                out[uvOutIndex++] = uBuffer.get(uIn)
                uIn += uPixelStride
                vIn += vPixelStride
            }
        }

        return out
    }
}
