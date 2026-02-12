package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageProxy
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

    // Reuse raw output arrays for the 2-output (anchors) variant to avoid per-frame huge allocations.
    private var rawBoxes2: Array<Array<FloatArray>>? = null // [1,anchors,4]
    private var rawScores2: Array<Array<FloatArray>>? = null // [1,anchors,classes]
    private var rawBoxesAnchorsN: Int = -1
    private var rawScoresClassesN: Int = -1

    // EfficientDet "raw" heads need anchor decoding.
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

    fun detect(bitmap: Bitmap): List<Detection> = detect(bitmap, null)

    /**
     * ROI crop in pixel coordinates of the provided bitmap (after rotation).
     * Returned boxes are always in coordinates of the original [bitmap] (full frame).
     */
    fun detect(bitmap: Bitmap, roiPx: Rect?): List<Detection> {
        val crop = cropToRoi(bitmap, roiPx)
        val cropBitmap = crop.bitmap

        val resized = Bitmap.createScaledBitmap(cropBitmap, inputSize, inputSize, true)
        val input = preprocessBitmap(resized)
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

        val dets = if (outputCount >= 4) {
            runFourOutput(cropBitmap.width, cropBitmap.height, input)
        } else {
            runTwoOutputWithAnchors(cropBitmap.width, cropBitmap.height, input)
        }

        if (crop.offsetX == 0f && crop.offsetY == 0f) return dets

        return dets.map { d ->
            d.copy(
                box = Box(
                    d.box.x1 + crop.offsetX,
                    d.box.y1 + crop.offsetY,
                    d.box.x2 + crop.offsetX,
                    d.box.y2 + crop.offsetY
                )
            )
        }
    }

    // ---- BYTEBUFFER_PIPELINE: ImageProxy -> inputBuffer ----
    /**
     * Přímá detekce z ImageProxy bez Bitmap/JPEG.
     * ROI je v rotated frame souřadnicích (po aplikaci [rotationDegrees]).
     *
     * Returned boxes are always in coordinates of the full rotated frame (tj. už s offsetem ROI).
     */
    fun detect(image: ImageProxy, roiPx: Rect?, rotationDegrees: Int): List<Detection> {
        val (frameWRot, frameHRot) = ImagePreprocessor.rotatedFrameSize(image.width, image.height, rotationDegrees)
        val crop = ImagePreprocessor.sanitizeCrop(frameWRot, frameHRot, roiPx)

        // Fill inputBuffer (float or uint8) directly from ImageProxy
        if (inputType == DataType.FLOAT32) {
            ImagePreprocessor.fillFloatBufferNhwcRgb01(
                image = image,
                rotationDegrees = rotationDegrees,
                crop = crop,
                inputSize = inputSize,
                out = inputBuffer
            )
        } else {
            ImagePreprocessor.fillUint8BufferNhwcRgb(
                image = image,
                rotationDegrees = rotationDegrees,
                crop = crop,
                inputSize = inputSize,
                out = inputBuffer
            )
        }

        val outputCount = interpreter.outputTensorCount
        val dets = if (outputCount >= 4) {
            runFourOutput(crop.width, crop.height, inputBuffer)
        } else {
            runTwoOutputWithAnchors(crop.width, crop.height, inputBuffer)
        }

        if (crop.left == 0 && crop.top == 0) return dets

        return dets.map { d ->
            d.copy(
                box = Box(
                    d.box.x1 + crop.offsetX,
                    d.box.y1 + crop.offsetY,
                    d.box.x2 + crop.offsetX,
                    d.box.y2 + crop.offsetY
                )
            )
        }
    }

    private fun runFourOutput(frameW: Int, frameH: Int, input: ByteBuffer): List<Detection> {
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
                        (box[1] * frameW).coerceIn(0f, frameW.toFloat()),
                        (box[0] * frameH).coerceIn(0f, frameH.toFloat()),
                        (box[3] * frameW).coerceIn(0f, frameW.toFloat()),
                        (box[2] * frameH).coerceIn(0f, frameH.toFloat())
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
     * - outScores: [1, anchors, numClasses]
     * - outBoxes:  [1, anchors, 4] (dy,dx,dh,dw)
     */
    private fun runTwoOutputWithAnchors(frameW: Int, frameH: Int, input: ByteBuffer): List<Detection> {
        val shape0 = interpreter.getOutputTensor(0).shape()
        val shape1 = interpreter.getOutputTensor(1).shape()

        val anchorsN = (shape0.getOrNull(1) ?: 0).coerceAtLeast(shape1.getOrNull(1) ?: 0)
        val classesN = maxOf(shape0.last(), shape1.last())

        // Reuse huge arrays to avoid per-frame allocations.
        if (rawBoxes2 == null || rawBoxesAnchorsN != anchorsN) {
            rawBoxes2 = Array(1) { Array(anchorsN) { FloatArray(4) } }
            rawBoxesAnchorsN = anchorsN
        }
        if (rawScores2 == null || rawBoxesAnchorsN != anchorsN || rawScoresClassesN != classesN) {
            rawScores2 = Array(1) { Array(anchorsN) { FloatArray(classesN) } }
            rawScoresClassesN = classesN
        }

        val rawBoxes = rawBoxes2!!
        val rawScores = rawScores2!!

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

        // COCO "vehicle-like" focus (keeps performance)
        val interestedClassIds = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
            .filter { it < classesN }
            .toIntArray()

        val out = ArrayList<Detection>(16)
        for (i in 0 until anchorsN) {
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

            yMin = yMin.coerceIn(0f, 1f)
            xMin = xMin.coerceIn(0f, 1f)
            yMax = yMax.coerceIn(0f, 1f)
            xMax = xMax.coerceIn(0f, 1f)

            val label = DetectionLabelMapper.cocoLabel(bestClass)

            out.add(
                Detection(
                    box = Box(
                        xMin * frameW,
                        yMin * frameH,
                        xMax * frameW,
                        yMax * frameH
                    ),
                    score = prob,
                    label = label
                )
            )
        }

        if (AppPreferences.debugOverlay && out.isNotEmpty()) {
            val top = out.maxByOrNull { it.score }
            Log.d(
                "EfficientDet",
                "decoded anchors=$anchorsN classes=$classesN top=${top?.label} score=${top?.score} box=${top?.box?.x1},${top?.box?.y1},${top?.box?.x2},${top?.box?.y2}"
            )
        }

        return out
    }

    private fun sigmoid(x: Float): Float {
        return (1f / (1f + kotlin.math.exp(-x)))
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

        anchorsCache = when {
            packed.size == expectedAnchors * 4 -> packed
            packed.size > expectedAnchors * 4 -> packed.copyOf(expectedAnchors * 4)
            else -> {
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

    private data class CropResult(val bitmap: Bitmap, val offsetX: Float, val offsetY: Float)

    private fun cropToRoi(src: Bitmap, roiPx: Rect?): CropResult {
        if (roiPx == null) return CropResult(src, 0f, 0f)

        val l = roiPx.left.coerceIn(0, kotlin.math.max(0, src.width - 1))
        val t = roiPx.top.coerceIn(0, kotlin.math.max(0, src.height - 1))
        val r = roiPx.right.coerceIn(l + 1, src.width)
        val b = roiPx.bottom.coerceIn(t + 1, src.height)

        val w = kotlin.math.max(1, r - l)
        val h = kotlin.math.max(1, b - t)

        val cropped = Bitmap.createBitmap(src, l, t, w, h)
        return CropResult(cropped, l.toFloat(), t.toFloat())
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        inputBuffer.rewind()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

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
        }

        inputBuffer.rewind()
        return inputBuffer
    }

}
