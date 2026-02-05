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

class EfficientDetTFLiteDetector(
    private val ctx: Context,
    modelName: String = "efficientdet_lite0.tflite",
    val inputSize: Int = 320,
    val scoreThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f
) {

    private val interpreter: Interpreter
    private val inputType: DataType

    init {
        val model = FileUtil.loadMappedFile(ctx, "models/$modelName")
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        inputType = interpreter.getInputTensor(0).dataType()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)
        val outputCount = interpreter.outputTensorCount

        return if (outputCount >= 4) {
            runFourOutput(bitmap, input)
        } else {
            runTwoOutput(bitmap, input)
        }
    }

    private fun runFourOutput(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        val boxes = Array(1) { Array(100) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(100) }
        val scores = Array(1) { FloatArray(100) }
        val count = FloatArray(1)
        val outputs = mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count)
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val validCount = count[0].toInt().coerceIn(0, 100)
        val result = mutableListOf<Detection>()
        for (i in 0 until validCount) {
            val score = scores[0][i]
            if (score < scoreThreshold) continue
            val classId = classes[0][i].toInt()
            val label = DetectionLabelMapper.cocoLabel(classId)
            val box = boxes[0][i]
            result.add(
                Detection(
                    box = Box(
                        (box[1] * bitmap.width).coerceIn(0f, bitmap.width.toFloat()),
                        (box[0] * bitmap.height).coerceIn(0f, bitmap.height.toFloat()),
                        (box[3] * bitmap.width).coerceIn(0f, bitmap.width.toFloat()),
                        (box[2] * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
                    ),
                    score = score,
                    label = label
                )
            )
            if (AppPreferences.debugOverlay && i < 3) {
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
        val out = mutableListOf<Detection>()
        for (i in 0 until anchors) {
            var bestClass = -1
            var bestScore = 0f
            for (c in scores[0][i].indices) {
                val score = scores[0][i][c]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < scoreThreshold) continue
            val label = DetectionLabelMapper.cocoLabel(bestClass)
            val b = boxes[0][i]
            out.add(
                Detection(
                    Box(b[1] * bitmap.width, b[0] * bitmap.height, b[3] * bitmap.width, b[2] * bitmap.height),
                    bestScore,
                    label
                )
            )
        }
        return out
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
