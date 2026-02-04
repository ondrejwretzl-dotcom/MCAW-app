package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import com.mcaw.model.Box
import com.mcaw.model.Detection
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * EfficientDet Lite0 (TFLite) Detector
 * ------------------------------------
 * - Model: efficientdet_lite0.tflite
 * - Vstup: 320x320 RGB (NHWC), normalizováno 0..1
 * - Výstup: boxes [1, 1917, 4], scores [1, 1917], classes [1, 1917]
 * - Vrací List<Detection> (stejná struktura jako YOLO detektor)
 */
class EfficientDetTFLiteDetector(
    ctx: Context,
    modelName: String = "efficientdet_lite0.tflite",
    val inputSize: Int = 320,
    val scoreThreshold: Float = 0.30f,
    val iouThreshold: Float = 0.45f
) {

    private val interpreter: Interpreter

    init {
        val model = FileUtil.loadMappedFile(ctx, "models/$modelName")
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        // Resize input pro inference
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized, interpreter.getInputTensor(0).dataType())

        // Výstupy EfficientDet Lite0
        val boxes = Array(1) { Array(1917) { FloatArray(4) } }   // [ymin, xmin, ymax, xmax] (norm.)
        val scores = Array(1) { FloatArray(1917) }
        val classes = Array(1) { FloatArray(1917) }

        val outputs = mutableMapOf(
            0 to boxes,
            1 to classes,
            2 to scores
        )
        val numDetections = if (interpreter.outputTensorCount > 3) {
            Array(1) { FloatArray(1) }
        } else {
            null
        }
        if (numDetections != null) {
            outputs[3] = numDetections
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        // Převod na Detection v rozměru původního bitmapu
        val dets = mutableListOf<Detection>()
        val scaleX = bitmap.width.toFloat()
        val scaleY = bitmap.height.toFloat()
        val limit = numDetections?.get(0)?.get(0)?.toInt()?.coerceAtMost(1917) ?: 1917

        for (i in 0 until limit) {
            val score = scores[0][i]
            if (score >= scoreThreshold) {
                val b = boxes[0][i]  // [ymin, xmin, ymax, xmax] v <0..1>
                val x1 = (b[1] * scaleX)
                val y1 = (b[0] * scaleY)
                val x2 = (b[3] * scaleX)
                val y2 = (b[2] * scaleY)
                val classId = classes[0][i].toInt()
                val label = labelForClassId(classId)

                dets.add(
                    Detection(
                        box = Box(x1, y1, x2, y2),
                        score = score,
                        label = label
                    )
                )
            }
        }

        return nonMaxSuppression(dets)
    }

    // ---------------------------------------------------------
    // PREPROCESSING (NHWC, bez mean/std – dle modelu případně doplnit)
    // ---------------------------------------------------------
    private fun preprocess(bitmap: Bitmap, dataType: DataType): Any {
        return when (dataType) {
            DataType.UINT8 -> {
                val buffer =
                    ByteBuffer.allocateDirect(inputSize * inputSize * 3)
                        .order(ByteOrder.nativeOrder())
                for (y in 0 until inputSize) {
                    for (x in 0 until inputSize) {
                        val px = bitmap.getPixel(x, y)
                        buffer.put(((px shr 16) and 0xFF).toByte())
                        buffer.put(((px shr 8) and 0xFF).toByte())
                        buffer.put((px and 0xFF).toByte())
                    }
                }
                buffer.rewind()
                buffer
            }
            else -> {
                val img = Array(1) {
                    Array(inputSize) {
                        Array(inputSize) {
                            FloatArray(3)
                        }
                    }
                }

                for (y in 0 until inputSize) {
                    for (x in 0 until inputSize) {
                        val px = bitmap.getPixel(x, y)
                        img[0][y][x][0] = ((px shr 16) and 0xFF) / 255f // R
                        img[0][y][x][1] = ((px shr 8) and 0xFF) / 255f  // G
                        img[0][y][x][2] = (px and 0xFF) / 255f          // B
                    }
                }
                img
            }
        }
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
}
