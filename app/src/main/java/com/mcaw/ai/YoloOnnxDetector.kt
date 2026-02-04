package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mcaw.model.Box
import com.mcaw.model.Detection
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloOnnxDetector(
    private val context: Context,
    private val modelName: String = "yolov8n.onnx",
    val inputSize: Int = 640,
    val scoreThreshold: Float = 0.35f,
    val iouThreshold: Float = 0.45f
) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open("models/$modelName").readBytes()
        session = env.createSession(bytes)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val input = preprocess(resized)

        val inputName = session.inputNames.iterator().next()
        val outputs = session.run(mapOf(inputName to input))

        val out = outputs[0] as OnnxTensor
        val fb = out.floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)

        val detections = parseYoloOutput(arr, out.info.shape)
        val scaleX = bitmap.width.toFloat() / inputSize
        val scaleY = bitmap.height.toFloat() / inputSize
        val scaled = detections.map {
            val b = it.box
            it.copy(
                box = Box(
                    b.x1 * scaleX,
                    b.y1 * scaleY,
                    b.x2 * scaleX,
                    b.y2 * scaleY
                )
            )
        }

        return nonMaxSuppression(scaled.toMutableList())
    }

    private fun preprocess(bmp: Bitmap): OnnxTensor {
        val data = FloatArray(1 * 3 * inputSize * inputSize)
        var i = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = bmp.getPixel(x, y)
                data[i++] = ((px shr 16) and 0xFF) / 255f
                data[i++] = ((px shr 8) and 0xFF) / 255f
                data[i++] = (px and 0xFF) / 255f
            }
        }
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    private fun parseYoloOutput(arr: FloatArray, shape: LongArray): List<Detection> {
        if (shape.size < 3) return emptyList()
        val dim1 = shape[1].toInt()
        val dim2 = shape[2].toInt()
        val interleaved = dim1 > dim2
        val channels = if (interleaved) dim2 else dim1
        val elements = if (interleaved) dim1 else dim2
        if (channels < 5) return emptyList()

        val results = mutableListOf<Detection>()
        for (i in 0 until elements) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            val bestScore: Float
            val bestClass: Int
            if (interleaved) {
                val base = i * channels
                cx = arr[base]
                cy = arr[base + 1]
                w = arr[base + 2]
                h = arr[base + 3]
                val (score, cls) = bestClassScoreInterleaved(arr, base + 4, channels - 4)
                bestScore = score
                bestClass = cls
            } else {
                cx = arr[i]
                cy = arr[elements + i]
                w = arr[(2 * elements) + i]
                h = arr[(3 * elements) + i]
                val (score, cls) = bestClassScoreChannelFirst(arr, elements, i, channels - 4)
                bestScore = score
                bestClass = cls
            }

            if (bestScore <= scoreThreshold) continue

            val normalized = cx <= 1f && cy <= 1f && w <= 1f && h <= 1f
            val scale = if (normalized) inputSize.toFloat() else 1f
            val x1 = (cx - w / 2f) * scale
            val y1 = (cy - h / 2f) * scale
            val x2 = (cx + w / 2f) * scale
            val y2 = (cy + h / 2f) * scale
            val label = labelForClassId(bestClass.toInt())

            results.add(Detection(Box(x1, y1, x2, y2), bestScore, label))
        }
        return results
    }

    private fun bestClassScoreInterleaved(
        arr: FloatArray,
        start: Int,
        count: Int
    ): Pair<Float, Int> {
        var bestScore = 0f
        var bestClass = -1
        for (i in 0 until count) {
            val score = arr[start + i]
            if (score > bestScore) {
                bestScore = score
                bestClass = i
            }
        }
        return bestScore to bestClass
    }

    private fun bestClassScoreChannelFirst(
        arr: FloatArray,
        elements: Int,
        index: Int,
        classCount: Int
    ): Pair<Float, Int> {
        var bestScore = 0f
        var bestClass = -1
        for (cls in 0 until classCount) {
            val score = arr[(4 + cls) * elements + index]
            if (score > bestScore) {
                bestScore = score
                bestClass = cls
            }
        }
        return bestScore to bestClass
    }

    private fun labelForClassId(classId: Int): String {
        return when (classId) {
            1 -> "bicycle"
            2 -> "car"
            3 -> "motorcycle"
            5 -> "bus"
            7 -> "truck"
            else -> "unknown"
        }
    }

    private fun nonMaxSuppression(list: MutableList<Detection>): List<Detection> {
        val out = mutableListOf<Detection>()
        list.sortByDescending { it.score }
        while (list.isNotEmpty()) {
            val best = list.removeAt(0)
            out.add(best)
            val it = list.iterator()
            while (it.hasNext()) {
                val other = it.next()
                if (iou(best.box, other.box) > iouThreshold) it.remove()
            }
        }
        return out
    }

    private fun iou(a: Box, b: Box): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}
