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
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

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

    // 4-output layout reuse (common TF2 detection API)
    private val outBoxes4 = Array(1) { Array(100) { FloatArray(4) } }   // [1,100,4] ymin,xmin,ymax,xmax (normalized)
    private val outClasses4 = Array(1) { FloatArray(100) }
    private val outScores4 = Array(1) { FloatArray(100) }
    private val outCount4 = FloatArray(1)

    // EfficientDet "raw" heads (class logits + box regression) need anchor decoding.
    // For input 320, anchors = 19206 (levels 3..7, 3 scales, 3 aspect ratios).
    private var anchorsCache: FloatArray? = null // packed [y, x, h, w] per anchor (all normalized 0..1)

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

        return if (outputCount >= 4) runFourOutput(bitmap, input) else runTwoOutputWithAnchors(bitmap, input)
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
            val box = outBoxes4[0][i] // [ymin,xmin,ymax,xmax] normalized
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
        }
        return result
    }

    /**
     * EfficientDet-Lite "raw" heads:
     * - outScores: [1, anchors, numClasses]  (already in 0..1 for many exports; if logits, values > 1 will appear)
     * - outBoxes:  [1, anchors, 4]          (box regression deltas dy,dx,dh,dw)
     *
     * We must decode using generated anchors (levels 3..7, 3 scales, 3 aspect ratios, anchorScale=4).
     */
    private fun runTwoOutputWithAnchors(bitmap: Bitmap, input: ByteBuffer): List<Detection> {
        val shape0 = interpreter.getOutputTensor(0).shape()
        val shape1 = interpreter.getOutputTensor(1).shape()

        val anchorsN = (shape0.getOrNull(1) ?: 0).coerceAtLeast(shape1.getOrNull(1) ?: 0)
        val classesN = maxOf(shape0.last(), shape1.last())

        // Allocate per-frame arrays (TFLite requires Java arrays), but keep sizes minimal.
        val rawBoxes = Array(1) { Array(anchorsN) { FloatArray(4) } }
        val rawScores = Array(1) { Array(anchorsN) { FloatArray(classesN) } }

        val outputs = mutableMapOf<Int, Any>()
        // Identify which output is boxes vs scores by last dimension.
        if (shape0.last() == 4) {
            outputs[0] = rawBoxes
            outputs[1] = rawScores
        } else {
            outputs[0] = rawScores
            outputs[1] = rawBoxes
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val anchors = getOrCreateAnchorsPacked(anchorsN)

        // COCO "vehicle-like" focus (keeps performance; labels still mapped through DetectionLabelMapper).
        val interestedClassIds = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
            .filter { it < classesN }
            .toIntArray()

        val out = ArrayList<Detection>(16)
        for (i in 0 until anchorsN) {
            // pick best class among interested ones
            var bestClass = -1
            var bestScore = 0f
            val sRow = rawScores[0][i]
            for (c in interestedClassIds) {
                val s = sRow[c]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            // Heuristic: if model gives logits, they are often > 1. Convert with sigmoid.
            val prob = if (bestScore > 1.5f) sigmoid(bestScore) else bestScore
            if (prob < scoreThreshold || bestClass < 0) continue

            val b = rawBoxes[0][i] // dy,dx,dh,dw
            val aOff = i * 4
            val ay = anchors[aOff]
            val ax = anchors[aOff + 1]
            val ah = anchors[aOff + 2]
            val aw = anchors[aOff + 3]

            val yCenter = b[0] * ah + ay
            val xCenter = b[1] * aw + ax
            val h = exp(b[2]) * ah
            val w = exp(b[3]) * aw

            var yMin = yCenter - h / 2f
            var xMin = xCenter - w / 2f
            var yMax = yCenter + h / 2f
            var xMax = xCenter + w / 2f

            // clip normalized
            yMin = yMin.coerceIn(0f, 1f)
            xMin = xMin.coerceIn(0f, 1f)
            yMax = yMax.coerceIn(0f, 1f)
            xMax = xMax.coerceIn(0f, 1f)

            val label = DetectionLabelMapper.cocoLabel(bestClass)

            out.add(
                Detection(
                    box = Box(
                        xMin * bitmap.width,
                        yMin * bitmap.height,
                        xMax * bitmap.width,
                        yMax * bitmap.height
                    ),
                    score = prob,
                    label = label
                )
            )
        }

        if (AppPreferences.debugOverlay && out.isNotEmpty()) {
            // minimal debug for field verification
            val top = out.maxByOrNull { it.score }
            Log.d(
                "EfficientDet",
                "decoded anchors=$anchorsN classes=$classesN top=${top?.label} score=${top?.score} box=${top?.box?.x1},${top?.box?.y1},${top?.box?.x2},${top?.box?.y2}"
            )
        }

        return out
    }

    private fun sigmoid(x: Float): Float {
        // stable-ish sigmoid for small positives/negatives typical of TFLite logits
        return (1f / (1f + exp(-x)))
    }

    private fun getOrCreateAnchorsPacked(expectedAnchors: Int): FloatArray {
        val cached = anchorsCache
        if (cached != null && cached.size == expectedAnchors * 4) return cached

        val packed = generateAnchorsPacked(
            inputSize = inputSize,
            minLevel = 3,
            maxLevel = 7,
            numScales = 3,
            aspectRatios = floatArrayOf(1.0f, 2.0f, 0.5f),
            anchorScale = 4.0f
        )

        // Defensive: if a model variant changes anchors, keep something usable (trim/extend).
        anchorsCache = when {
            packed.size == expectedAnchors * 4 -> packed
            packed.size > expectedAnchors * 4 -> packed.copyOf(expectedAnchors * 4)
            else -> {
                // extend by repeating last anchor (should not happen for lite0 320)
                val ext = FloatArray(expectedAnchors * 4)
                packed.copyInto(ext, 0, 0, packed.size)
                for (i in packed.size until ext.size) ext[i] = packed[packed.size - 1]
                ext
            }
        }

        if (AppPreferences.debugOverlay) {
            Log.d("EfficientDet", "anchors generated=${anchorsCache!!.size / 4} expected=$expectedAnchors input=$inputSize")
        }
        return anchorsCache!!
    }

    private fun generateAnchorsPacked(
        inputSize: Int,
        minLevel: Int,
        maxLevel: Int,
        numScales: Int,
        aspectRatios: FloatArray,
        anchorScale: Float
    ): FloatArray {
        val anchors = ArrayList<Float>(expectedCapacity(inputSize, minLevel, maxLevel, numScales, aspectRatios.size))

        val scalesPerOctave = numScales.coerceAtLeast(1)
        for (level in minLevel..maxLevel) {
            val stride = 2.0.pow(level.toDouble()).toFloat()
            val feat = ceil(inputSize / stride).toInt().coerceAtLeast(1)

            // normalized base scale
            val baseScale = (anchorScale * stride) / inputSize.toFloat()

            for (y in 0 until feat) {
                val yCenter = (y + 0.5f) / feat.toFloat()
                for (x in 0 until feat) {
                    val xCenter = (x + 0.5f) / feat.toFloat()

                    for (s in 0 until scalesPerOctave) {
                        val octaveScale = 2.0.pow(s.toDouble() / scalesPerOctave.toDouble()).toFloat()
                        val scale = baseScale * octaveScale

                        for (ar in aspectRatios) {
                            val ratioSqrt = sqrt(ar)
                            val anchorH = scale / ratioSqrt
                            val anchorW = scale * ratioSqrt

                            anchors.add(yCenter)
                            anchors.add(xCenter)
                            anchors.add(anchorH)
                            anchors.add(anchorW)
                        }
                    }
                }
            }
        }
        // packed [y,x,h,w]
        val out = FloatArray(anchors.size)
        for (i in anchors.indices) out[i] = anchors[i]
        return out
    }

    private fun expectedCapacity(
        inputSize: Int,
        minLevel: Int,
        maxLevel: Int,
        numScales: Int,
        numRatios: Int
    ): Int {
        var total = 0
        for (level in minLevel..maxLevel) {
            val stride = 2.0.pow(level.toDouble()).toFloat()
            val feat = ceil(inputSize / stride).toInt().coerceAtLeast(1)
            total += feat * feat
        }
        return total * numScales * numRatios * 4
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        inputBuffer.rewind()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        var i = 0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF

            if (inputType == DataType.FLOAT32) {
                inputBuffer.putFloat(r / 255f)
                inputBuffer.putFloat(g / 255f)
                inputBuffer.putFloat(b / 255f)
            } else {
                inputBuffer.put(r.toByte())
                inputBuffer.put(g.toByte())
                inputBuffer.put(b.toByte())
            }
            i++
        }

        inputBuffer.rewind()
        return inputBuffer
    }
}
