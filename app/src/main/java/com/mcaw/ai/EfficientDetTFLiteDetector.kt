package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.mcaw.config.AppPreferences
import com.mcaw.model.Box
import com.mcaw.model.Detection
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EfficientDetTFLiteDetector(
    private val ctx: Context,
    modelName: String = "efficientdet_lite0.tflite",
    val inputSize: Int = 320,
    val scoreThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f
) {

    private val interpreter: Interpreter
    private val inputType: DataType
    private val modelLabel: String = modelName
    private var modelInfoLogged = false

    // Reuse buffers to reduce GC.
    private val pixels = IntArray(inputSize * inputSize)
    private val inputBuffer: ByteBuffer

    // 4-output layout reuse (typical TFLite Detection API)
    private val outBoxes4 = Array(1) { Array(100) { FloatArray(4) } }
    private val outClasses4 = Array(1) { FloatArray(100) }
    private val outScores4 = Array(1) { FloatArray(100) }
    private val outCount4 = FloatArray(1)

    init {
        val model = FileUtil.loadMappedFile(ctx, "models/$modelName")

        val options = Interpreter.Options()
            .setNumThreads(4)

        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { options.addDelegate(NnApiDelegate()) }
        }

        interpreter = Interpreter(model, options)
        inputType = interpreter.getInputTensor(0).dataType()

        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        inputBuffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)
        val outputCount = interpreter.outputTensorCount

        if (AppPreferences.debugOverlay && !modelInfoLogged) {
            modelInfoLogged = true
            val outputs = (0 until outputCount)
                .joinToString { idx -> "out$idx=${interpreter.getOutputTensor(idx).shape().contentToString()}" }
            Log.d(
                "EfficientDet",
                "model=$modelLabel input=${interpreter.getInputTensor(0).shape().contentToString()} type=$inputType $outputs"
            )
        }

        return if (outputCount >= 4) runFourOutput(bitmap, input) else runTwoOutput(bitmap, input)
    }

    private fun runFourOutput(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        outCount4[0] = 0f
        val outputs = mapOf(0 to outBoxes4, 1 to outClasses4, 2 to outScores4, 3 to outCount4)
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val validCount = outCount4[0].toInt().coerceIn(0, 100)
        val result = ArrayList<Detection>(validCount)

        for (i in 0 until validCount) {
            val score = outScores4[0][i]
            if (score < scoreThreshold) continue

            val classId = outClasses4[0][i].toInt()
            val label = DetectionLabelMapper.cocoLabel(classId)
            val raw = outBoxes4[0][i]

            val box = decodeBoxToFrame(raw, bitmap.width, bitmap.height) ?: continue

            result.add(
                Detection(
                    box = box,
                    score = score,
                    label = label
                )
            )

            if (AppPreferences.debugOverlay && i < 3) {
                Log.d("EfficientDet", "raw box[$i]=${raw.contentToString()} -> box=${box.x1},${box.y1},${box.x2},${box.y2}")
                Log.d("EfficientDet", "classId=$classId score=$score label=$label")
            }
        }
        return result
    }

    private fun runTwoOutput(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        val shape0 = interpreter.getOutputTensor(0).shape()
        val shape1 = interpreter.getOutputTensor(1).shape()
        val anchors = (shape0.getOrNull(1) ?: 0).coerceAtLeast(shape1.getOrNull(1) ?: 0)
        val classesN = maxOf(shape0.last(), shape1.last())

        val boxes = Array(1) { Array(anchors) { FloatArray(4) } }
        val scores = Array(1) { Array(anchors) { FloatArray(classesN) } }

        val outputs = mutableMapOf<Int, Any>()
        if (shape0.last() == 4) {
            outputs[0] = boxes
            outputs[1] = scores
        } else {
            outputs[0] = scores
            outputs[1] = boxes
        }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        // COCO-ish interested IDs (guarded by classesN)
        val interestedClassIds = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8).filter { it < classesN }

        val out = ArrayList<Detection>(8)
        for (i in 0 until anchors) {
            var bestClass = -1
            var bestScore = 0f
            for (c in interestedClassIds) {
                val s = scores[0][i][c]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < scoreThreshold || bestClass < 0) continue

            val label = DetectionLabelMapper.cocoLabel(bestClass)
            val raw = boxes[0][i]
            val box = decodeBoxToFrame(raw, bitmap.width, bitmap.height) ?: continue

            out.add(Detection(box, bestScore, label))

            if (AppPreferences.debugOverlay && out.size <= 3) {
                Log.d("EfficientDet", "raw2 box=${raw.contentToString()} -> box=${box.x1},${box.y1},${box.x2},${box.y2}")
                Log.d("EfficientDet", "raw2 classId=$bestClass score=$bestScore label=$label")
            }
        }
        return out
    }

    /**
     * EfficientDet models vary in box ordering and scale.
     *
     * Most common:
     *  - normalized [ymin, xmin, ymax, xmax] in 0..1
     * Sometimes:
     *  - normalized [xmin, ymin, xmax, ymax]
     *  - or pixel coords in 0..inputSize
     *
     * We decode both candidate orderings and choose the one that is "more valid"
     * (proper ordering, positive area, and closer to the image center as a tie-break).
     */
    private fun decodeBoxToFrame(raw: FloatArray, frameW: Int, frameH: Int): Box? {
        if (raw.size < 4 || frameW <= 0 || frameH <= 0) return null

        // If values look like pixels (e.g., up to ~inputSize), normalize to 0..1.
        val maxAbs = max(max(abs(raw[0]), abs(raw[1])), max(abs(raw[2]), abs(raw[3])))
        val norm = if (maxAbs > 1.5f) {
            // treat as pixels in model input space
            floatArrayOf(
                raw[0] / inputSize.toFloat(),
                raw[1] / inputSize.toFloat(),
                raw[2] / inputSize.toFloat(),
                raw[3] / inputSize.toFloat()
            )
        } else {
            raw
        }

        val candA = decode(norm, frameW, frameH, order = Order.YX_YX) // [ymin,xmin,ymax,xmax]
        val candB = decode(norm, frameW, frameH, order = Order.XY_XY) // [xmin,ymin,xmax,ymax]

        val scoreA = candidateScore(candA, frameW.toFloat(), frameH.toFloat())
        val scoreB = candidateScore(candB, frameW.toFloat(), frameH.toFloat())

        val chosen = when {
            scoreA <= 0f && scoreB <= 0f -> null
            scoreA >= scoreB -> candA
            else -> candB
        }

        return chosen?.let { clampBox(it, frameW.toFloat(), frameH.toFloat()) }
    }

    private enum class Order { YX_YX, XY_XY }

    private fun decode(n: FloatArray, frameW: Int, frameH: Int, order: Order): Box? {
        val fw = frameW.toFloat()
        val fh = frameH.toFloat()

        val (x1n, y1n, x2n, y2n) = when (order) {
            Order.YX_YX -> Quad(n[1], n[0], n[3], n[2])
            Order.XY_XY -> Quad(n[0], n[1], n[2], n[3])
        }

        if (x1n.isNaN() || y1n.isNaN() || x2n.isNaN() || y2n.isNaN()) return null

        val x1 = x1n * fw
        val y1 = y1n * fh
        val x2 = x2n * fw
        val y2 = y2n * fh

        // normalize ordering
        val left = min(x1, x2)
        val top = min(y1, y2)
        val right = max(x1, x2)
        val bottom = max(y1, y2)

        return Box(left, top, right, bottom)
    }

    private data class Quad(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private fun candidateScore(b: Box?, frameW: Float, frameH: Float): Float {
        if (b == null) return 0f
        val w = b.x2 - b.x1
        val h = b.y2 - b.y1
        if (w <= 2f || h <= 2f) return 0f

        // Penalize if far outside frame (before clamp)
        val outPenalty = (if (b.x1 < -0.05f * frameW) 1f else 0f) +
            (if (b.y1 < -0.05f * frameH) 1f else 0f) +
            (if (b.x2 > 1.05f * frameW) 1f else 0f) +
            (if (b.y2 > 1.05f * frameH) 1f else 0f)

        val area = (w * h).coerceAtMost(frameW * frameH)
        val areaNorm = (area / (frameW * frameH)).coerceIn(0f, 1f)

        // Slight preference for reasonable boxes near the center to break ties deterministically.
        val cx = (b.x1 + b.x2) * 0.5f
        val cy = (b.y1 + b.y2) * 0.5f
        val centerDist = ((cx - frameW * 0.5f) * (cx - frameW * 0.5f) +
            (cy - frameH * 0.5f) * (cy - frameH * 0.5f))
        val centerBias = 1f / (1f + centerDist / (frameW * frameW + frameH * frameH))

        val base = 0.7f * areaNorm + 0.3f * centerBias
        return base * (1f - 0.35f * outPenalty)
    }

    private fun clampBox(b: Box, w: Float, h: Float): Box {
        val x1 = b.x1.coerceIn(0f, w)
        val y1 = b.y1.coerceIn(0f, h)
        val x2 = b.x2.coerceIn(0f, w)
        val y2 = b.y2.coerceIn(0f, h)
        return Box(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        inputBuffer.clear()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        if (inputType == DataType.FLOAT32) {
            for (i in pixels.indices) {
                val p = pixels[i]
                inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((p and 0xFF) / 255f)
            }
        } else {
            for (i in pixels.indices) {
                val p = pixels[i]
                inputBuffer.put(((p shr 16) and 0xFF).toByte())
                inputBuffer.put(((p shr 8) and 0xFF).toByte())
                inputBuffer.put((p and 0xFF).toByte())
            }
        }
        inputBuffer.rewind()
        return inputBuffer
    }
}
