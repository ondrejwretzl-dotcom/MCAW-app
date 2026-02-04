package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mcaw.model.Box
import com.mcaw.model.Detection
import com.mcaw.util.PublicLogWriter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * EfficientDet Lite0 (TFLite) Detector
 * ------------------------------------
 * - Model: efficientdet_lite0.tflite
 * - Vstup: 320x320 RGB (NHWC)
 * - Výstup: scores [1, N, C], boxes [1, N, 4]
 * - Vrací List<Detection> (stejná struktura jako YOLO detektor)
 */
class EfficientDetTFLiteDetector(
    private val ctx: Context,
    modelName: String = "efficientdet_lite0.tflite",
    val inputSize: Int = 320,
    val scoreThreshold: Float = 0.30f,
    val iouThreshold: Float = 0.45f
) {

    private val interpreter: Interpreter
    private val inputType: DataType
    private val numAnchors: Int
    private val numClasses: Int
    private val boxesOutputIndex: Int
    private val scoresOutputIndex: Int

    init {
        val model = FileUtil.loadMappedFile(ctx, "models/$modelName")
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        inputType = interpreter.getInputTensor(0).dataType()
        val out0 = interpreter.getOutputTensor(0).shape()
        val out1 = interpreter.getOutputTensor(1).shape()
        val out0Last = out0.lastOrNull() ?: 0
        val out1Last = out1.lastOrNull() ?: 0
        if (out0Last == 4 && out1Last != 4) {
            boxesOutputIndex = 0
            scoresOutputIndex = 1
            numAnchors = out0.getOrNull(1) ?: 0
            numClasses = out1.getOrNull(2) ?: 0
        } else if (out1Last == 4 && out0Last != 4) {
            boxesOutputIndex = 1
            scoresOutputIndex = 0
            numAnchors = out1.getOrNull(1) ?: 0
            numClasses = out0.getOrNull(2) ?: 0
        } else {
            boxesOutputIndex = 0
            scoresOutputIndex = 1
            numAnchors = out0.getOrNull(1) ?: 0
            numClasses = out1.getOrNull(2) ?: 0
            Log.w(
                "EfficientDet",
                "Unexpected output shapes out0=${out0.contentToString()} out1=${out1.contentToString()}"
            )
        }
        logModelConfig(out0, out1)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        // Resize input pro inference
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)

        // Výstupy EfficientDet Lite0
        val boxes = Array(1) { Array(numAnchors) { FloatArray(4) } }   // [ymin, xmin, ymax, xmax] (norm.)
        val classScores = Array(1) { Array(numAnchors) { FloatArray(numClasses) } }

        val outputs: MutableMap<Int, Any> = mutableMapOf(
            boxesOutputIndex to boxes,
            scoresOutputIndex to classScores
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        // Převod na Detection v rozměru původního bitmapu
        val dets = mutableListOf<Detection>()
        val scaleX = bitmap.width.toFloat()
        val scaleY = bitmap.height.toFloat()

        val vehicleLabelByClassId = mapOf(
            2 to "bicycle",
            3 to "car",
            4 to "motorcycle",
            6 to "bus",
            8 to "truck"
        )
        val validClassIds = vehicleLabelByClassId.keys.filter { it < numClasses }

        for (i in 0 until numAnchors) {
            val scores = classScores[0][i]
            var bestScore = 0f
            var bestClass = -1
            for (classId in validClassIds) {
                val score = scores[classId]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }
            if (bestClass >= 0 && bestScore >= scoreThreshold) {
                val b = boxes[0][i]  // [ymin, xmin, ymax, xmax] v <0..1>
                val x1 = (b[1] * scaleX).coerceIn(0f, scaleX)
                val y1 = (b[0] * scaleY).coerceIn(0f, scaleY)
                val x2 = (b[3] * scaleX).coerceIn(0f, scaleX)
                val y2 = (b[2] * scaleY).coerceIn(0f, scaleY)

                dets.add(
                    Detection(
                        box = Box(x1, y1, x2, y2),
                        score = bestScore,
                        label = vehicleLabelByClassId[bestClass] ?: "vehicle"
                    )
                )
            }
        }

        return nonMaxSuppression(dets)
    }

    // ---------------------------------------------------------
    // PREPROCESSING (NHWC, bez mean/std – dle modelu případně doplnit)
    // ---------------------------------------------------------
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

    // ---------------------------------------------------------
    // NMS
    // ---------------------------------------------------------
    private fun nonMaxSuppression(dets: MutableList<Detection>): List<Detection> {
        val results = mutableListOf<Detection>()
        dets.sortByDescending { it.score }

        while (dets.isNotEmpty()) {
            val best = dets.removeAt(0)
            results.add(best)

            val it = dets.iterator()
            while (it.hasNext()) {
                val other = it.next()
                if (iou(best.box, other.box) > iouThreshold) {
                    it.remove()
                }
            }
        }
        return results
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

    private fun logModelConfig(out0: IntArray, out1: IntArray) {
        val timestamp = System.currentTimeMillis()
        val content = buildString {
            append("ts=")
            append(timestamp)
            append(" event=effdet_model")
            append(" input_type=")
            append(inputType)
            append(" input_size=")
            append(inputSize)
            append(" out0=")
            append(out0.contentToString())
            append(" out1=")
            append(out1.contentToString())
            append(" anchors=")
            append(numAnchors)
            append(" classes=")
            append(numClasses)
        }
        PublicLogWriter.appendLogLine(
            ctx,
            "mcaw_model_${dateStamp(timestamp)}.txt",
            content
        )
    }

    private fun dateStamp(timestamp: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(timestamp)
    }
}
