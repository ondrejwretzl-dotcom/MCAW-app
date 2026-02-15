package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageUtils {

    fun imageProxyToBitmap(image: ImageProxy, _context: Context): Bitmap? {
        return try {
            val nv21 = yuv420888ToNv21(image)
            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Korektní převod YUV_420_888 (CameraX) -> NV21 (Y + VU interleaved),
     * respektuje rowStride/pixelStride a tím opravuje posuny/artefakty.
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

        // --- Copy Y ---
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride // typicky 1
        var outIndex = 0

        for (row in 0 until height) {
            var inIndex = row * yRowStride
            for (col in 0 until width) {
                out[outIndex++] = yBuffer.get(inIndex)
                inIndex += yPixelStride
            }
        }

        // --- Copy VU (NV21) ---
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // UV je subsamplované 2x2, takže poloviční rozměry
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        var uvOutIndex = ySize

        for (row in 0 until chromaHeight) {
            var uIn = row * uRowStride
            var vIn = row * vRowStride
            for (col in 0 until chromaWidth) {
                // NV21 chce V potom U
                out[uvOutIndex++] = vBuffer.get(vIn)
                out[uvOutIndex++] = uBuffer.get(uIn)
                uIn += uPixelStride
                vIn += vPixelStride
            }
        }

        return out
    }
}

