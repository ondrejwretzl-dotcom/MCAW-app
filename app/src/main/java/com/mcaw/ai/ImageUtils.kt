package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import androidx.camera.core.internal.utils.ImageUtil
import androidx.camera.core.internal.utils.YuvToRgbConverter

/**
 * ImageUtils
 * ----------
 * Bezpečná konverze ImageProxy (YUV_420_888) -> Bitmap pomocí CameraX YuvToRgbConverter.
 * Opravuje problémy se stride/pixelStride, které způsobují posun/rozpad obrazu a špatné boxy.
 */
object ImageUtils {

    @Volatile
    private var converter: YuvToRgbConverter? = null

    fun imageProxyToBitmap(image: ImageProxy, context: Context): Bitmap? {
        return try {
            val c = converter ?: YuvToRgbConverter(context).also { converter = it }
            val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            c.yuvToRgb(image.image ?: return null, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

