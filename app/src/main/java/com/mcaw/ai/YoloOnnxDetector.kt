package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mcaw.config.AppPreferences
import com.mcaw.model.Box
import com.mcaw.model.Detection
import java.nio.FloatBuffer

class YoloOnnxDetector(
    private val context: Context,
    private val modelName: String = "yolov8n.onnx",
    val inputSize: Int = 640,
    val scoreThreshold: Float = 0.25f,
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
        val input = preprocessNchw(resized)
        val inputName = session.inputNames.iterator().next()
        val outputs = session.run(mapOf(inputName to input))
        val out = outputs[0] as OnnxTensor
        val arr = out.floatBuffer.toArray()
        return parseYoloOutput(arr, out.info.shape, bitmap.width, bitmap.height)
    }

    private fun preprocessNchw(bmp: Bitmap): OnnxTensor {
        val hw = inputSize * inputSize
        val data = FloatArray(3 * hw)
        var pixelIndex = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = bmp.getPixel(x, y)
                data[pixelIndex] = ((px shr 16) and 0xFF) / 255f
                data[hw + pixelIndex] = ((px shr 8) and 0xFF) / 255f
                data[2 * hw + pixelIndex] = (px and 0xFF) / 255f
                pixelIndex++
            }
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
    }

    private fun parseYoloOutput(arr: FloatArray, shape: LongArray, frameW: Int, frameH: Int): List<Detection> {
        if (shape.size < 3) return emptyList()
        val rows: Int
        val cols: Int
        val channelFirst = shape[1] < shape[2]
        if (channelFirst) {
            cols = shape[1].toInt()
            rows = shape[2].toInt()
        } else {
            rows = shape[1].toInt()
            cols = shape[2].toInt()
        }
        if (cols < 6) return emptyList()

        val out = mutableListOf<Detection>()
        for (i in 0 until rows) {
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            val obj: Float
            var bestClass = -1
            var bestClassScore = 0f
            if (channelFirst) {
                cx = arr[i]
                cy = arr[rows + i]
                w = arr[2 * rows + i]
                h = arr[3 * rows + i]
                obj = arr[4 * rows + i]
                for (c in 5 until cols) {
                    val classScore = arr[c * rows + i]
                    if (classScore > bestClassScore) {
                        bestClassScore = classScore
                        bestClass = c - 5
                    }
                }
            } else {
                val base = i * cols
                cx = arr[base]
                cy = arr[base + 1]
                w = arr[base + 2]
                h = arr[base + 3]
                obj = arr[base + 4]
                for (c in 5 until cols) {
                    val classScore = arr[base + c]
                    if (classScore > bestClassScore) {
                        bestClassScore = classScore
                        bestClass = c - 5
                    }
                }
            }
            val score = obj * bestClassScore
            if (score < scoreThreshold || bestClass < 0) continue

            val x1 = ((cx - w / 2f) / inputSize.toFloat() * frameW).coerceIn(0f, frameW.toFloat())
            val y1 = ((cy - h / 2f) / inputSize.toFloat() * frameH).coerceIn(0f, frameH.toFloat())
            val x2 = ((cx + w / 2f) / inputSize.toFloat() * frameW).coerceIn(0f, frameW.toFloat())
            val y2 = ((cy + h / 2f) / inputSize.toFloat() * frameH).coerceIn(0f, frameH.toFloat())
            val label = DetectionLabelMapper.cocoLabel(bestClass)
            out.add(Detection(Box(x1, y1, x2, y2), score, label))

            if (AppPreferences.debugOverlay && out.size <= 3) {
                Log.d("YoloOnnxDetector", "raw classId=$bestClass obj=$obj class=$bestClassScore score=$score label=$label")
            }
        }
        return out
    }

    private fun FloatBuffer.toArray(): FloatArray {
        val dup = duplicate()
        dup.rewind()
        return FloatArray(dup.remaining()).also { dup.get(it) }
    }
}
