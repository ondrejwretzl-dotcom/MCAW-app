package com.mcaw.ai

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * BYTEBUFFER_PIPELINE:
 * Přímý preprocessing z ImageProxy (YUV_420_888) do modelového vstupu bez Bitmap/JPEG.
 *
 * Pozn:
 * - pracuje v "rotated frame" souřadnicích (stejně jako dříve Bitmap po rotateBitmap()).
 * - ROI je Rect v těchto rotated souřadnicích.
 * - podporuje výstup:
 *   - Float32 NCHW (YOLO ONNX)
 *   - Float32 NHWC (TFLite float)
 *   - UInt8 NHWC (TFLite quant)
 */
object ImagePreprocessor {

    data class CropInfo(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val frameWidthRot: Int,
        val frameHeightRot: Int
    ) {
        val offsetX: Float get() = left.toFloat()
        val offsetY: Float get() = top.toFloat()
    }

    fun rotatedFrameSize(srcW: Int, srcH: Int, rotationDegrees: Int): Pair<Int, Int> {
        return when ((rotationDegrees % 360 + 360) % 360) {
            90, 270 -> srcH to srcW
            else -> srcW to srcH
        }
    }

    fun sanitizeCrop(
        frameWRot: Int,
        frameHRot: Int,
        roi: android.graphics.Rect?
    ): CropInfo {
        val l = (roi?.left ?: 0).coerceIn(0, max(0, frameWRot - 1))
        val t = (roi?.top ?: 0).coerceIn(0, max(0, frameHRot - 1))
        val r = (roi?.right ?: frameWRot).coerceIn(l + 1, frameWRot)
        val b = (roi?.bottom ?: frameHRot).coerceIn(t + 1, frameHRot)
        val w = max(2, r - l)
        val h = max(2, b - t)
        return CropInfo(l, t, w, h, frameWRot, frameHRot)
    }

    /**
     * Fill FloatArray as NCHW (C-major) with RGB normalized to [0..1].
     * out size must be 3*inputSize*inputSize.
     */
    fun fillFloatNchwRgb01(
        image: ImageProxy,
        rotationDegrees: Int,
        crop: CropInfo,
        inputSize: Int,
        out: FloatArray
    ) {
        val w = inputSize
        val h = inputSize
        require(out.size >= 3 * w * h) { "out too small" }

        val sampler = YuvSampler(image, rotationDegrees)

        val roiL = crop.left.toFloat()
        val roiT = crop.top.toFloat()
        val roiW = crop.width.toFloat()
        val roiH = crop.height.toFloat()

        val planeSize = w * h
        var idx = 0
        for (dy in 0 until h) {
            val yRot = roiT + (dy + 0.5f) * (roiH / h.toFloat())
            for (dx in 0 until w) {
                val xRot = roiL + (dx + 0.5f) * (roiW / w.toFloat())
                val rgb = sampler.sampleRgbBilinear(xRot, yRot)
                // NCHW => write into separate planes
                out[idx] = rgb.r
                out[idx + planeSize] = rgb.g
                out[idx + 2 * planeSize] = rgb.b
                idx += 1
            }
        }
    }

    /**
     * Fill ByteBuffer as Float32 NHWC with RGB normalized to [0..1].
     * Buffer must be direct and have enough capacity. Position will be rewound to 0.
     */
    fun fillFloatBufferNhwcRgb01(
        image: ImageProxy,
        rotationDegrees: Int,
        crop: CropInfo,
        inputSize: Int,
        out: ByteBuffer
    ) {
        out.rewind()
        val sampler = YuvSampler(image, rotationDegrees)

        val roiL = crop.left.toFloat()
        val roiT = crop.top.toFloat()
        val roiW = crop.width.toFloat()
        val roiH = crop.height.toFloat()

        val w = inputSize
        val h = inputSize
        for (dy in 0 until h) {
            val yRot = roiT + (dy + 0.5f) * (roiH / h.toFloat())
            for (dx in 0 until w) {
                val xRot = roiL + (dx + 0.5f) * (roiW / w.toFloat())
                val rgb = sampler.sampleRgbBilinear(xRot, yRot)
                out.putFloat(rgb.r)
                out.putFloat(rgb.g)
                out.putFloat(rgb.b)
            }
        }
        out.rewind()
    }

    /**
     * Fill ByteBuffer as UInt8 NHWC with RGB in [0..255].
     * Buffer must be direct and have enough capacity. Position will be rewound to 0.
     */
    fun fillUint8BufferNhwcRgb(
        image: ImageProxy,
        rotationDegrees: Int,
        crop: CropInfo,
        inputSize: Int,
        out: ByteBuffer
    ) {
        out.rewind()
        val sampler = YuvSampler(image, rotationDegrees)

        val roiL = crop.left.toFloat()
        val roiT = crop.top.toFloat()
        val roiW = crop.width.toFloat()
        val roiH = crop.height.toFloat()

        val w = inputSize
        val h = inputSize
        for (dy in 0 until h) {
            val yRot = roiT + (dy + 0.5f) * (roiH / h.toFloat())
            for (dx in 0 until w) {
                val xRot = roiL + (dx + 0.5f) * (roiW / w.toFloat())
                val rgb8 = sampler.sampleRgb8Bilinear(xRot, yRot)
                out.put(rgb8.r.toByte())
                out.put(rgb8.g.toByte())
                out.put(rgb8.b.toByte())
            }
        }
        out.rewind()
    }

    data class Rgb01(val r: Float, val g: Float, val b: Float)
    data class Rgb8(val r: Int, val g: Int, val b: Int)


    /**
     * Lightweight sampler instance for repeated pixel reads (brake cue, debug, etc.).
     * Coordinates are in rotated frame space.
     */
    class Sampler internal constructor(
        private val impl: Any
    ) {
        internal fun yuv(): Any = impl
    }

    fun newSampler(image: ImageProxy, rotationDegrees: Int): Sampler {
        return Sampler(YuvSampler(image, rotationDegrees))
    }

    fun sampleRgb8Bilinear(s: Sampler, xRot: Float, yRot: Float): Rgb8 {
        val ys = s.yuv() as YuvSampler
        return ys.sampleRgb8Bilinear(xRot, yRot)
    }

    fun sampleRgb01Bilinear(s: Sampler, xRot: Float, yRot: Float): Rgb01 {
        val ys = s.yuv() as YuvSampler
        return ys.sampleRgbBilinear(xRot, yRot)
    }

    /**
     * Fast YUV sampler with rotation handling (rotated coords -> source coords).
     * Bilinear in rotated coordinate system.
     */
    private class YuvSampler(
        private val image: ImageProxy,
        private val rotationDegrees: Int
    ) {
        private val wSrc = image.width
        private val hSrc = image.height

        private val yPlane = image.planes[0]
        private val uPlane = image.planes[1]
        private val vPlane = image.planes[2]

        private val yBuf = yPlane.buffer
        private val uBuf = uPlane.buffer
        private val vBuf = vPlane.buffer

        private val yRowStride = yPlane.rowStride
        private val yPixelStride = yPlane.pixelStride

        private val uRowStride = uPlane.rowStride
        private val uPixelStride = uPlane.pixelStride

        private val vRowStride = vPlane.rowStride
        private val vPixelStride = vPlane.pixelStride

        private fun rotToSrc(xRot: Int, yRot: Int): Pair<Int, Int> {
            val rot = ((rotationDegrees % 360) + 360) % 360
            return when (rot) {
                0 -> xRot to yRot
                90 -> {
                    // dest(w=hSrc, h=wSrc) => src = (y, hSrc-1-x)
                    yRot to (hSrc - 1 - xRot)
                }
                180 -> (wSrc - 1 - xRot) to (hSrc - 1 - yRot)
                270 -> {
                    // dest(w=hSrc,h=wSrc) => src = (wSrc-1-y, x)
                    (wSrc - 1 - yRot) to xRot
                }
                else -> xRot to yRot
            }
        }

        private fun clampRot(xRotF: Float, yRotF: Float): Pair<Float, Float> {
            val (wRot, hRot) = rotatedFrameSize(wSrc, hSrc, rotationDegrees)
            val x = xRotF.coerceIn(0f, (wRot - 1).toFloat())
            val y = yRotF.coerceIn(0f, (hRot - 1).toFloat())
            return x to y
        }

        fun sampleRgbBilinear(xRotF: Float, yRotF: Float): Rgb01 {
            val (xC, yC) = clampRot(xRotF, yRotF)
            val x0 = floor(xC).toInt()
            val y0 = floor(yC).toInt()
            val (wRot, hRot) = rotatedFrameSize(wSrc, hSrc, rotationDegrees)
            val x1 = min(x0 + 1, wRot - 1)
            val y1 = min(y0 + 1, hRot - 1)

            val xx1 = x1
            val yy1 = y1

            val fx = xC - x0.toFloat()
            val fy = yC - y0.toFloat()

            val c00 = sampleRgb01Nearest(x0, y0)
            val c10 = sampleRgb01Nearest(xx1, y0)
            val c01 = sampleRgb01Nearest(x0, yy1)
            val c11 = sampleRgb01Nearest(xx1, yy1)

            val w00 = (1f - fx) * (1f - fy)
            val w10 = fx * (1f - fy)
            val w01 = (1f - fx) * fy
            val w11 = fx * fy

            return Rgb01(
                r = c00.r * w00 + c10.r * w10 + c01.r * w01 + c11.r * w11,
                g = c00.g * w00 + c10.g * w10 + c01.g * w01 + c11.g * w11,
                b = c00.b * w00 + c10.b * w10 + c01.b * w01 + c11.b * w11
            )
        }

        fun sampleRgb8Bilinear(xRotF: Float, yRotF: Float): Rgb8 {
            val c = sampleRgbBilinear(xRotF, yRotF)
            return Rgb8(
                r = (c.r * 255f).toInt().coerceIn(0, 255),
                g = (c.g * 255f).toInt().coerceIn(0, 255),
                b = (c.b * 255f).toInt().coerceIn(0, 255)
            )
        }

        private fun sampleRgb01Nearest(xRot: Int, yRot: Int): Rgb01 {
            val (xs0, ys0) = rotToSrc(xRot, yRot)
            val xs = xs0.coerceIn(0, wSrc - 1)
            val ys = ys0.coerceIn(0, hSrc - 1)

            val y = readY(xs, ys)
            val u = readU(xs, ys)
            val v = readV(xs, ys)

            val rgb = yuvToRgb(y, u, v)
            return Rgb01(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f)
        }

        private fun readY(x: Int, y: Int): Int {
            val idx = y * yRowStride + x * yPixelStride
            return (yBuf.get(idx).toInt() and 0xFF)
        }

        private fun readU(x: Int, y: Int): Int {
            val x2 = x / 2
            val y2 = y / 2
            val idx = y2 * uRowStride + x2 * uPixelStride
            return (uBuf.get(idx).toInt() and 0xFF)
        }

        private fun readV(x: Int, y: Int): Int {
            val x2 = x / 2
            val y2 = y / 2
            val idx = y2 * vRowStride + x2 * vPixelStride
            return (vBuf.get(idx).toInt() and 0xFF)
        }

        private fun yuvToRgb(y: Int, u: Int, v: Int): IntArray {
            // ITU-R BT.601
            val c = y - 16
            val d = u - 128
            val e = v - 128
            val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
            return intArrayOf(r, g, b)
        }
    }
}
