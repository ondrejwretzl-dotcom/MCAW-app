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

    // 4-output layout reuse
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
        inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * bytesPerChannel)
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
            val box = outBoxes4[0][i]
            result.add(
                Detection(
                    box = run {
                        val x1a = (box[1] * bitmap.width).coerceIn(0f, bitmap.width.toFloat())
                        val y1a = (box[0] * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
                        val x2a = (box[3] * bitmap.width).coerceIn(0f, bitmap.width.toFloat())
                        val y2a = (box[2] * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
                        val areaA = kotlin.math.max(0f, x2a - x1a) * kotlin.math.max(0f, y2a - y1a)

                        val x1b = (box[0] * bitmap.width).coerceIn(0f, bitmap.width.toFloat())
                        val y1b = (box[1] * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
                        val x2b = (box[2] * bitmap.width).coerceIn(0f, bitmap.width.toFloat())
                        val y2b = (box[3] * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
                        val areaB = kotlin.math.max(0f, x2b - x1b) * kotlin.math.max(0f, y2b - y1b)

                        if (areaB > areaA * 1.2f) Box(x1b, y1b, x2b, y2b) else Box(x1a, y1a, x2a, y2a)
                    },
                    score = score,
                    label = label
                )
            )
            if (AppPreferences.debugOverlay && i < 3) {
                Log.d("EfficientDet", "raw box=$i vals=${box.contentToString()}")
                Log.d("EfficientDet", "raw classId=$classId score=$score label=$label")
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

        val interestedClassIds = listOf(0, 1, 2, 3, 5, 7, 4, 6, 8).filter { it < classesN }
        val out = ArrayList<Detection>(8)
        for (i in 0 until anchors) {
            var bestClass = -1
            var bestScore = 0f
            for (c in interestedClassIds) {
                val score = scores[0][i][c]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < scoreThreshold || bestClass < 0) continue
            val label = DetectionLabelMapper.cocoLabel(bestClass)
            val b = boxes[0][i]
            out.add(
                Detection(
                    Box(b[1] * bitmap.width, b[0] * bitmap.height, b[3] * bitmap.width, b[2] * bitmap.height),
                    bestScore,
                    label
                )
            )
            if (AppPreferences.debugOverlay && out.size <= 3) {
                Log.d("EfficientDet", "raw2 classId=$bestClass score=$bestScore label=$label")
            }
        }
        return out
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        inputBuffer.clear()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        if (inputType == DataType.FLOAT32) {
            for (px in pixels) {
                inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((px shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((px and 0xFF) / 255f)
            }
        } else {
            for (px in pixels) {
                inputBuffer.put(((px shr 16) and 0xFF).toByte())
                inputBuffer.put(((px shr 8) and 0xFF).toByte())
                inputBuffer.put((px and 0xFF).toByte())
            }
        }

        inputBuffer.rewind()
        return inputBuffer
    }
}
