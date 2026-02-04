package com.mcaw.ai

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ImageUtils
 * ----------
 * - Konverze YUV_420_888 � Bitmap
 * - Pou��v� JPEG kompresn� transformaci (rychl� a stabiln�)
 */
object ImageUtils {

    fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            yuv420ToBitmap(image)
        } catch (e: Exception) {
            null
        }
    }

    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = android.graphics.Matrix().apply {
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    // ---------------------------------------------------------
    // YUV � Bitmap (univerz�ln� metoda pro CameraX)
    // ---------------------------------------------------------
    private fun yuv420ToBitmap(image: ImageProxy): Bitmap? {
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 = Y + VU
        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        // V a U jsou prohozen� oproti NV21, proto je ukl�d�me opa�n�
        val chromaStart = ySize
        val vPos = chromaStart
        val uPos = chromaStart + 1

        var vIndex = 0
        var uIndex = 0

        while (vIndex < vSize) {
            nv21[vPos + vIndex] = vBuffer[vIndex]
            vIndex += 2
        }

        while (uIndex < uSize) {
            nv21[uPos + uIndex] = uBuffer[uIndex]
            uIndex += 2
        }

        // Te� m�me NV21 � zkomprimujeme do JPEG � dek�dujeme na Bitmap
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

        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
}
