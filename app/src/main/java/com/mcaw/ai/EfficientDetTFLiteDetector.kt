package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.mcaw.config.AppPreferences
import com.mcaw.model.Box
import com.mcaw.model.Detection
import com.mcaw.util.PublicLogWriter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

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

    // Debug logging to file (so it ends up in your exported logs, not only Logcat).
    private val detLogFileName: String = "mcaw_effdet_${System.currentTimeMillis()}.txt"
    private fun dlog(msg: String) {
        if (!AppPreferences.debugOverlay) return
        PublicLogWriter.appendLogLine(ctx, detLogFileName, msg)
    }

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
        inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)
        val outputCount = interpreter.outputTensorCount

        if (AppPreferences.debugOverlay && !modelInfoLogged) {
            modelInfoLogged = true
            val outputs = (0 until outputCount).joinToString { idx ->
                "out$idx=${interpreter.getOutputTensor(idx).shape().contentToString()}"
            }
            val msg =
                "EfficientDet model=$modelLabel input=${interpreter.getInputTensor(0).shape().contentToString()} type=$inputType $outputs"
            Log.d("EfficientDet", msg)
            dlog(msg)
        }

        return when {
            outputCount >= 4 -> runFlexibleFourOutput(bitmap, input)
            outputCount == 2 -> runTwoOutput(bitmap, input)
            else -> emptyList()
        }
    }

    /**
     * Fix pro posunuté boxy (vlevo nahoře):
     * Na různých TF Lite exportech se liší pořadí výstupů (indexy), ale tvůj kód je měl natvrdo 0..3.
     * Když se zamění (např. boxes <-> scores), vzniknou malé hodnoty a po clampu skončí box u (0,0).
     *
     * Proto výstupy mapujeme podle SHAPE, ne podle indexu.
     */
    private fun runFlexibleFourOutput(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        // Find tensors by shape signature.
        var boxesIdx = -1
        var scoresIdx = -1
        var classesIdx = -1
        var countIdx = -1

        for (i in 0 until interpreter.outputTensorCount) {
            val sh = interpreter.getOutputTensor(i).shape()
            // count: [1] or [1,1]
            if (countIdx < 0 && (sh.contentEquals(intArrayOf(1)) || sh.contentEquals(intArrayOf(1, 1)))) {
                countIdx = i
                continue
            }
            // boxes: [1,N,4]
            if (boxesIdx < 0 && sh.size == 3 && sh[0] == 1 && sh[2] == 4) {
                boxesIdx = i
                continue
            }
            // scores/classes: [1,N]
            if (sh.size == 2 && sh[0] == 1) {
                if (scoresIdx < 0) scoresIdx = i else if (classesIdx < 0) classesIdx = i
            }
        }

        // Fallback (older assumption).
        if (boxesIdx < 0 || scoresIdx < 0 || classesIdx < 0 || countIdx < 0) {
            boxesIdx = 0
            classesIdx = 1
            scoresIdx = 2
            countIdx = 3
        }

        val boxesShape = interpreter.getOutputTensor(boxesIdx).shape()
        val n = boxesShape[1].coerceIn(1, 1000) // usually 100
        val outBoxes = Array(1) { Array(n) { FloatArray(4) } }
        val outScores = Array(1) { FloatArray(n) }
        val outClasses = Array(1) { FloatArray(n) }
        val outCount = FloatArray(1)

        val outputs = hashMapOf<Int, Any>(
            boxesIdx to outBoxes,
            scoresIdx to outScores,
            classesIdx to outClasses,
            countIdx to outCount
        )
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val validCount = outCount[0].toInt().coerceIn(0, n)
        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()

        val result = ArrayList<Detection>(max(4, validCount))

        for (i in 0 until validCount) {
            val score = outScores[0][i]
            if (score < scoreThreshold) continue

            val classId = outClasses[0][i].toInt()
            val label = DetectionLabelMapper.cocoLabel(classId)
            val raw = outBoxes[0][i]

            // Official TF Object Detection API EfficientDet Lite: boxes are normalized corners [ymin,xmin,ymax,xmax].
            val decoded = decodeOfficialCornersOrPixels(raw, frameW, frameH) ?: continue

            result.add(
                Detection(
                    box = decoded,
                    score = score,
                    label = label
                )
            )

            if (AppPreferences.debugOverlay && i < 5) {
                dlog("i=$i score=$score classId=$classId raw=${raw.contentToString()} box=${decoded.x1},${decoded.y1},${decoded.x2},${decoded.y2}")
            }
        }

        return result
    }

    private fun runTwoOutput(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        // Some exports: [1,N,4] + [1,N,C] (scores per class) or swapped order.
        val sh0 = interpreter.getOutputTensor(0).shape()
        val sh1 = interpreter.getOutputTensor(1).shape()

        val boxesIdx = if (sh0.size >= 3 && sh0.last() == 4) 0 else 1
        val scoresIdx = 1 - boxesIdx

        val boxesShape = interpreter.getOutputTensor(boxesIdx).shape()
        val n = boxesShape.getOrNull(1)?.coerceIn(1, 20000) ?: 1
        val classesN = interpreter.getOutputTensor(scoresIdx).shape().last().coerceAtLeast(1)

        val outBoxes = Array(1) { Array(n) { FloatArray(4) } }
        val outScores = Array(1) { Array(n) { FloatArray(classesN) } }

        val outputs = hashMapOf<Int, Any>(
            boxesIdx to outBoxes,
            scoresIdx to outScores
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()

        val interested = listOf(0, 1, 2, 3, 5, 7, 4, 6, 8).filter { it < classesN }
        val result = ArrayList<Detection>(64)

        for (i in 0 until n) {
            var bestC = -1
            var bestS = 0f
            val row = outScores[0][i]
            for (c in interested) {
                val s = row[c]
                if (s > bestS) {
                    bestS = s
                    bestC = c
                }
            }
            if (bestS < scoreThreshold || bestC < 0) continue

            val raw = outBoxes[0][i]
            val decoded = decodeOfficialCornersOrPixels(raw, frameW, frameH) ?: continue

            result.add(
                Detection(
                    box = decoded,
                    score = bestS,
                    label = DetectionLabelMapper.cocoLabel(bestC)
                )
            )

            if (AppPreferences.debugOverlay && result.size <= 5) {
                dlog("i=$i score=$bestS classId=$bestC raw=${raw.contentToString()} box=${decoded.x1},${decoded.y1},${decoded.x2},${decoded.y2}")
            }
        }

        return result
    }

    /**
     * Deterministický dekodér pro oficiální EfficientDet Lite exporty:
     * - očekává [ymin,xmin,ymax,xmax] NORMALIZED 0..1
     * - nebo stejný formát v PIXELECH 0..inputSize (některé konverze)
     *
     * Nezkouší "center-size" (to by byl jiný export). Když se ukáže, že raw nesedí,
     * logy v mcaw_effdet_*.txt to prozradí a upravíme přesně.
     */
    private fun decodeOfficialCornersOrPixels(raw: FloatArray, frameW: Float, frameH: Float): Box? {
        if (raw.size < 4) return null
        val inputSizeF = inputSize.toFloat().coerceAtLeast(1f)

        val a0 = raw[0]
        val a1 = raw[1]
        val a2 = raw[2]
        val a3 = raw[3]

        // Detect pixels-vs-normalized.
        val maxV = max(max(a0, a1), max(a2, a3))
        val minV = minOf(a0, a1, a2, a3)
        val looksPixel = minV >= 0f && maxV > 2f && maxV <= inputSizeF * 2.0f

        val y1n = if (looksPixel) a0 / inputSizeF else a0
        val x1n = if (looksPixel) a1 / inputSizeF else a1
        val y2n = if (looksPixel) a2 / inputSizeF else a2
        val x2n = if (looksPixel) a3 / inputSizeF else a3

        if (!x1n.isFinite() || !y1n.isFinite() || !x2n.isFinite() || !y2n.isFinite()) return null
        if (x2n - x1n <= 0f || y2n - y1n <= 0f) return null

        // Reject wild values (helps avoid swapped outputs creating nonsense).
        val maxAbs = maxOf(abs(x1n), abs(y1n), abs(x2n), abs(y2n))
        if (maxAbs > 3f) return null

        val left = (x1n * frameW).coerceIn(0f, frameW)
        val top = (y1n * frameH).coerceIn(0f, frameH)
        val right = (x2n * frameW).coerceIn(0f, frameW)
        val bottom = (y2n * frameH).coerceIn(0f, frameH)

        if (right - left <= 1f || bottom - top <= 1f) return null
        return Box(left, top, right, bottom)
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        inputBuffer.rewind()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        if (inputType == DataType.FLOAT32) {
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                inputBuffer.putFloat(r / 255f)
                inputBuffer.putFloat(g / 255f)
                inputBuffer.putFloat(b / 255f)
            }
        } else {
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                inputBuffer.put(r.toByte())
                inputBuffer.put(g.toByte())
                inputBuffer.put(b.toByte())
            }
        }

        inputBuffer.rewind()
        return inputBuffer
    }
}
