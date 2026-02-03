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
    private val inputSize: Int = 640,
    private val scoreThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f
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

        return parseFlatArray(arr)
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

    // Jednoduchý fallback parser:
    // očekává řady: cx, cy, w, h, conf, opakovaně
    private fun parseFlatArray(arr: FloatArray): List<Detection> {
        val out = mutableListOf<Detection>()
        var i = 0
        while (i + 4 < arr.size) {
            val cx = arr[i]
            val cy = arr[i + 1]
            val w  = arr[i + 2]
            val h  = arr[i + 3]
            val conf = arr[i + 4]

            if (conf > scoreThreshold) {
                val x1 = cx - w / 2
                val y1 = cy - h / 2
                val x2 = cx + w / 2
                val y2 = cy + h / 2
                out.add(Detection(Box(x1, y1, x2, y2), conf, "car"))
            }
            i += 5
        }
        return nonMaxSuppression(out)
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
