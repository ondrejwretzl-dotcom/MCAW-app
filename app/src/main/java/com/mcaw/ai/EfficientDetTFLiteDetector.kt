package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import com.mcaw.model.Box
import com.mcaw.model.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
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
    private val inputSize: Int = 320,
    private val scoreThreshold: Float = 0.30f,
    private val iouThreshold: Float = 0.45f
) {

    private val interpreter: Interpreter

    init {
        val model = FileUtil.loadMappedFile(ctx, "models/$modelName")
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        // Resize input pro inference
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)

        // Výstupy EfficientDet Lite0
        val boxes = Array(1) { Array(1917) { FloatArray(4) } }   // [ymin, xmin, ymax, xmax] (norm.)
        val scores = Array(1) { FloatArray(1917) }
        val classes = Array(1) { FloatArray(1917) }

        val outputs = mapOf(
            0 to boxes,
            1 to classes,
            2 to scores
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        // Pøevod na Detection v rozmìru pùvodního bitmapu
        val dets = mutableListOf<Detection>()
        val scaleX = bitmap.width.toFloat()
        val scaleY = bitmap.height.toFloat()

        for (i in 0 until 1917) {
            val score = scores[0][i]
            if (score >= scoreThreshold) {
                val b = boxes[0][i]  // [ymin, xmin, ymax, xmax] v <0..1>
                val x1 = (b[1] * scaleX)
                val y1 = (b[0] * scaleY)
                val x2 = (b[3] * scaleX)
                val y2 = (b[2] * scaleY)

                dets.add(
                    Detection(
                        box = Box(x1, y1, x2, y2),
                        score = score,
                        // Pokud budeš chtít mapovat ID tøídy na label, mùžeš zde:
                        label = "car"
                    )
                )
            }
        }

        return nonMaxSuppression(dets)
    }

    // ---------------------------------------------------------
    // PREPROCESSING (NHWC, bez mean/std – dle modelu pøípadnì doplnit)
    // ---------------------------------------------------------
    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
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
        return img
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
