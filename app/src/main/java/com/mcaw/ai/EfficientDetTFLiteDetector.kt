package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mcaw.config.AppPreferences
import com.mcaw.model.Box
import com.mcaw.model.Detection
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

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

    init {
        val model = FileUtil.loadMappedFile(ctx, "models/$modelName")
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        inputType = interpreter.getInputTensor(0).dataType()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        // EfficientDet expects square input. Project uses stretch-resize (no letterbox),
        // so mapping back is linear to bitmap w/h.
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)

        val outputCount = interpreter.outputTensorCount

        if (AppPreferences.debugOverlay && !modelInfoLogged) {
            modelInfoLogged = true
            val outs = (0 until outputCount)
                .joinToString { idx -> "out$idx=${interpreter.getOutputTensor(idx).shape().contentToString()}" }
            Log.d(
                "EfficientDet",
                "model=$modelLabel input=${interpreter.getInputTensor(0).shape().contentToString()} type=$inputType $outs"
            )
        }

        return if (outputCount >= 4) {
            runFourOutput(bitmap, input)
        } else {
            runTwoOutput(bitmap, input)
        }
    }

    private fun runFourOutput(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        // Typical TF Object Detection TFLite export:
        // boxes [1,100,4], classes [1,100], scores [1,100], count [1]
        val boxes = Array(1) { Array(100) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(100) }
        val scores = Array(1) { FloatArray(100) }
        val count = FloatArray(1)

        val outputs = mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count)
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val validCount = count[0].toInt().coerceIn(0, 100)
        val result = ArrayList<Detection>(validCount)

        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()

        for (i in 0 until validCount) {
            val score = scores[0][i]
            if (score < scoreThreshold) continue

            val classId = classes[0][i].toInt()
            val label = DetectionLabelMapper.cocoLabel(classId)
            val raw = boxes[0][i]

            val decoded = decodeBox(raw, frameW, frameH) ?: continue

            result.add(
                Detection(
                    box = decoded,
                    score = score,
                    label = label
                )
            )

            if (AppPreferences.debugOverlay && i < 3) {
                Log.d(
                    "EfficientDet",
                    "raw4 classId=$classId score=$score label=$label boxRaw=${raw.contentToString()} boxPx=${decoded.x1},${decoded.y1},${decoded.x2},${decoded.y2}"
                )
            }
        }

        return result
    }

    private fun runTwoOutput(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        // Some exports provide only 2 outputs. We detect which one is boxes by last dim == 4.
        val shape0 = interpreter.getOutputTensor(0).shape()
        val shape1 = interpreter.getOutputTensor(1).shape()

        val anchors =
            (shape0.getOrNull(1) ?: 0).coerceAtLeast(shape1.getOrNull(1) ?: 0).coerceAtLeast(1)
        val classesN = maxOf(shape0.last(), shape1.last()).coerceAtLeast(1)

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

        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()

        val interestedClassIds = listOf(0, 1, 2, 3, 5, 7, 4, 6, 8).filter { it < classesN }

        val out = ArrayList<Detection>(64)
        for (i in 0 until anchors) {
            var bestClass = -1
            var bestScore = 0f

            val row = scores[0][i]
            for (c in interestedClassIds) {
                val s = row[c]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < scoreThreshold || bestClass < 0) continue

            val label = DetectionLabelMapper.cocoLabel(bestClass)
            val raw = boxes[0][i]
            val decoded = decodeBox(raw, frameW, frameH) ?: continue

            out.add(
                Detection(
                    box = decoded,
                    score = bestScore,
                    label = label
                )
            )

            if (AppPreferences.debugOverlay && out.size <= 3) {
                Log.d(
                    "EfficientDet",
                    "raw2 classId=$bestClass score=$bestScore label=$label boxRaw=${raw.contentToString()} boxPx=${decoded.x1},${decoded.y1},${decoded.x2},${decoded.y2}"
                )
            }
        }

        return out
    }

    /**
     * Robust bbox decoding for different EfficientDet/TF-OD exports.
     *
     * Common formats for 4 floats:
     *  - corners normalized: [ymin, xmin, ymax, xmax]
     *  - corners normalized: [xmin, ymin, xmax, ymax]
     *  - corners in input pixels: same orders but 0..inputSize
     *  - center-size normalized: [cy, cx, h, w]  (or [cx, cy, w, h] in some exports)
     *  - center-size in input pixels: same but 0..inputSize
     *
     * We generate candidates and pick the most plausible one.
     */
    private fun decodeBox(raw: FloatArray, frameW: Float, frameH: Float): Box? {
        if (raw.size < 4) return null
        if (frameW <= 1f || frameH <= 1f) return null

        val inputSizeF = inputSize.toFloat().coerceAtLeast(1f)

        data class Candidate(val box: Box, val score: Float)

        fun clampBox(left: Float, top: Float, right: Float, bottom: Float): Box? {
            val l = left.coerceIn(0f, frameW)
            val t = top.coerceIn(0f, frameH)
            val r = right.coerceIn(0f, frameW)
            val b = bottom.coerceIn(0f, frameH)
            if (r - l <= 1f || b - t <= 1f) return null
            return Box(l, t, r, b)
        }

        fun cornersToPx(x1n: Float, y1n: Float, x2n: Float, y2n: Float): Box? {
            // allow slight out-of-range
            val maxAbs = maxOf(abs(x1n), abs(y1n), abs(x2n), abs(y2n))
            if (maxAbs > 5f) return null
            if (x2n - x1n <= 0f || y2n - y1n <= 0f) return null
            return clampBox(x1n * frameW, y1n * frameH, x2n * frameW, y2n * frameH)
        }

        fun centerToPx(cxn: Float, cyn: Float, wn: Float, hn: Float): Box? {
            val maxAbs = maxOf(abs(cxn), abs(cyn), abs(wn), abs(hn))
            if (maxAbs > 5f) return null
            if (wn <= 0f || hn <= 0f) return null
            val x1 = (cxn - wn / 2f)
            val y1 = (cyn - hn / 2f)
            val x2 = (cxn + wn / 2f)
            val y2 = (cyn + hn / 2f)
            return cornersToPx(x1, y1, x2, y2)
        }

        val a0 = raw[0]
        val a1 = raw[1]
        val a2 = raw[2]
        val a3 = raw[3]

        // Heuristic: looks like pixel coords if all non-negative and max is in ~[2..inputSize*2]
        val maxV = maxOf(a0, a1, a2, a3)
        val minV = minOf(a0, a1, a2, a3)
        val looksPixel = minV >= 0f && maxV > 2f && maxV <= inputSizeF * 2.0f

        fun norm(v: Float): Float = if (looksPixel) v / inputSizeF else v

        val cands = ArrayList<Candidate>(8)

        // corners: [ymin,xmin,ymax,xmax]
        run {
            val y1 = norm(a0)
            val x1 = norm(a1)
            val y2 = norm(a2)
            val x2 = norm(a3)
            cornersToPx(x1, y1, x2, y2)?.let { cands.add(Candidate(it, candidateScore(it, frameW, frameH))) }
        }
        // corners: [xmin,ymin,xmax,ymax]
        run {
            val x1 = norm(a0)
            val y1 = norm(a1)
            val x2 = norm(a2)
            val y2 = norm(a3)
            cornersToPx(x1, y1, x2, y2)?.let { cands.add(Candidate(it, candidateScore(it, frameW, frameH))) }
        }
        // center-size: [cy,cx,h,w]
        run {
            val cy = norm(a0)
            val cx = norm(a1)
            val h = norm(a2)
            val w = norm(a3)
            centerToPx(cx, cy, w, h)?.let { cands.add(Candidate(it, candidateScore(it, frameW, frameH))) }
        }
        // center-size: [cx,cy,w,h]
        run {
            val cx = norm(a0)
            val cy = norm(a1)
            val w = norm(a2)
            val h = norm(a3)
            centerToPx(cx, cy, w, h)?.let { cands.add(Candidate(it, candidateScore(it, frameW, frameH))) }
        }

        if (cands.isEmpty()) return null

        // pick best candidate (closest to center + reasonable size)
        return cands.maxByOrNull { it.score }?.box
    }

    private fun candidateScore(b: Box, frameW: Float, frameH: Float): Float {
        // Prefer boxes that:
        // - are not ultra-tiny
        // - are not almost full-screen
        // - are not stuck in corner (common symptom of wrong decode)
        val areaN = (b.area / (frameW * frameH)).coerceIn(0f, 1f)
        val cxN = ((b.x1 + b.x2) * 0.5f / frameW).coerceIn(0f, 1f)
        val cyN = ((b.y1 + b.y2) * 0.5f / frameH).coerceIn(0f, 1f)

        val sizeScore =
            when {
                areaN < 0.0005f -> -2.0f
                areaN < 0.003f -> -0.5f
                areaN > 0.90f -> -2.0f
                areaN > 0.50f -> -0.5f
                else -> 1.0f
            }

        val centerDist = kotlin.math.sqrt((cxN - 0.5f) * (cxN - 0.5f) + (cyN - 0.5f) * (cyN - 0.5f))
        val centerScore = 1.0f - centerDist // 1 at center, ~0.3 at corners

        return sizeScore + centerScore
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = bitmap.getPixel(x, y)
                val r = ((px shr 16) and 0xFF)
                val g = ((px shr 8) and 0xFF)
                val b = (px and 0xFF)

                if (inputType == DataType.FLOAT32) {
                    buffer.putFloat(r / 255f)
                    buffer.putFloat(g / 255f)
                    buffer.putFloat(b / 255f)
                } else {
                    buffer.put(r.toByte())
                    buffer.put(g.toByte())
                    buffer.put(b.toByte())
                }
            }
        }

        buffer.rewind()
        return buffer
    }
}
