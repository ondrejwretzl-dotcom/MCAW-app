package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import com.mcaw.ai.Box
import com.mcaw.ai.Detection
import ai.onnxruntime.*
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 ONNX Detector
 * --------------------
 * - Používá ONNX Runtime
 * - Podporuje modely typu YOLOv8n.onnx, YOLOv8s.onnx, atd.
 * - Vstup musí být 640x640, RGB, normalizovaný 0..1
 * - Výstup je Nx(4+1+classCount)
 */
class YoloOnnxDetector(
    private val context: Context,
    private val modelName: String = "yolov8n.onnx",
    private val inputSize: Int = 640,
    private val scoreThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f
) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("models/$modelName").readBytes()
        session = env.createSession(modelBytes)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        // Resize + normalize
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        //val inputTensor = preprocess(resized)
        val inputTensor = OnnxInput.fromCHW(env, chwFloatArray, /*C*/3, /*H*/inputH, /*W*/inputW

        val inputName = session.inputNames.iterator().next()
        val outputs = session.run(mapOf(inputName to inputTensor))

        val output = outputs[0].value as Array<FloatArray>
        val predictions = output[0]

        session.close()

        return postprocess(predictions)
    }

    // ---------------------------------------------------------------------------
    // PREPROCESS
    // ---------------------------------------------------------------------------
    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val imgData = FloatArray(1 * 3 * inputSize * inputSize)
        var idx = 0

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = bitmap.getPixel(x, y)
                imgData[idx++] = ((px shr 16) and 0xFF) / 255f
                imgData[idx++] = ((px shr 8) and 0xFF) / 255f
                imgData[idx++] = (px and 0xFF) / 255f
            }
        }

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        return OnnxTensor.createTensor(env, imgData, shape)
    }

    // ---------------------------------------------------------------------------
    // POSTPROCESS (YOLOv8 standard output)
    // ---------------------------------------------------------------------------
    private fun postprocess(pred: FloatArray): List<Detection> {
        val classCount = (pred.size - 4) / 1  // YOLOv8 export - single class per row
        val objs = mutableListOf<Detection>()

        var i = 0
        while (i < pred.size) {
            val cx = pred[i]
            val cy = pred[i + 1]
            val w = pred[i + 2]
            val h = pred[i + 3]
            val conf = pred[i + 4]

            if (conf > scoreThreshold) {

                val left = cx - w / 2
                val top = cy - h / 2
                val right = cx + w / 2
                val bottom = cy + h / 2

                objs.add(
                    Detection(
                        label = "car",
                        score = conf,
                        box = Box(left, top, right, bottom)
                    )
                )
            }

            i += (5 + classCount)
        }

        return nonMaxSuppression(objs)
    }

    // ---------------------------------------------------------------------------
    // NMS
    // ---------------------------------------------------------------------------
    private fun nonMaxSuppression(boxes: MutableList<Detection>): List<Detection> {

        val picked = mutableListOf<Detection>()
        boxes.sortByDescending { it.score }

        while (boxes.isNotEmpty()) {
            val best = boxes.removeAt(0)
            picked.add(best)

            val it = boxes.iterator()
            while (it.hasNext()) {
                val other = it.next()
                val iou = iou(best.box, other.box)
                if (iou > iouThreshold) it.remove()
            }
        }

        return picked
    }

    private fun iou(a: Box, b: Box): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)

        val interArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val unionArea = a.area() + b.area() - interArea

        return if (unionArea <= 0) 0f else interArea / unionArea
    }
}
