package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mcaw.config.AppPreferences
import com.mcaw.model.Box
import com.mcaw.model.Detection
import java.nio.FloatBuffer

class YoloOnnxDetector(
    private val context: Context,
    private val modelName: String = "yolov8n.onnx",
    val inputSize: Int = AppPreferences.yoloInputSize,
    val scoreThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open("models/$modelName").readBytes()
        session = env.createSession(bytes)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val inputName = session.inputNames.iterator().next()

        preprocessNchw(resized).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                val out = outputs[0] as? OnnxTensor ?: return emptyList()
                val arr = out.floatBuffer.toArray()
                val shape = squeezeShape(out.info.shape)
                return parseYoloOutput(arr, shape, bitmap.width, bitmap.height)
            }
        }
    }

    private fun preprocessNchw(bmp: Bitmap): OnnxTensor {
        val hw = inputSize * inputSize
        val data = FloatArray(3 * hw)
        var pixelIndex = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = bmp.getPixel(x, y)
                data[pixelIndex] = ((px shr 16) and 0xFF) / 255f
                data[hw + pixelIndex] = ((px shr 8) and 0xFF) / 255f
                data[2 * hw + pixelIndex] = (px and 0xFF) / 255f
                pixelIndex++
            }
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )
    }

    private fun squeezeShape(shape: LongArray): LongArray {
        // Some exports add singleton dims, e.g. [1,1,84,8400]. We squeeze only while >3 dims.
        if (shape.size <= 3) return shape
        val s = shape.toMutableList()
        for (i in s.size - 1 downTo 0) {
            if (s.size <= 3) break
            if (s[i] == 1L) s.removeAt(i)
        }
        return s.toLongArray()
    }

    private fun parseYoloOutput(arr: FloatArray, shape: LongArray, frameW: Int, frameH: Int): List<Detection> {
        if (shape.size < 3) return emptyList()

        val channelFirst = shape[1] < shape[2]
        val cols = if (channelFirst) shape[1].toInt() else shape[2].toInt()   // attributes
        val rows = if (channelFirst) shape[2].toInt() else shape[1].toInt()   // boxes
        if (cols < 6 || rows <= 0) return emptyList()

        // YOLOv8 ONNX typicky:
        // - 84 = 4 box (cx,cy,w,h) + 80 class conf (bez obj)
        // - 85 = 4 box + obj + 80 class conf
        val hasObjectness = (cols == 85) || (cols > 85 && (cols - 5) >= 1)
        val classStart = if (hasObjectness) 5 else 4
        val objIndex = 4

        fun get(attr: Int, i: Int): Float {
            return if (channelFirst) {
                arr[attr * rows + i]
            } else {
                arr[i * cols + attr]
            }
        }

        val out = mutableListOf<Detection>()

        for (i in 0 until rows) {
            val cx = get(0, i)
            val cy = get(1, i)
            val w = get(2, i)
            val h = get(3, i)

            val obj = if (hasObjectness) get(objIndex, i) else 1f

            var bestClass = -1
            var bestClassScore = 0f
            for (c in classStart until cols) {
                val s = get(c, i)
                if (s > bestClassScore) {
                    bestClassScore = s
                    bestClass = c - classStart
                }
            }

            val score = obj * bestClassScore
            if (score < scoreThreshold || bestClass < 0) continue

            // Některé exporty dávají xywh v rozsahu 0..1, jiné 0..inputSize
            val scale = if (cx <= 2f && cy <= 2f && w <= 2f && h <= 2f) inputSize.toFloat() else 1f
            val cxPx = cx * scale
            val cyPx = cy * scale
            val wPx = w * scale
            val hPx = h * scale

            val x1 = ((cxPx - wPx / 2f) / inputSize.toFloat() * frameW).coerceIn(0f, frameW.toFloat())
            val y1 = ((cyPx - hPx / 2f) / inputSize.toFloat() * frameH).coerceIn(0f, frameH.toFloat())
            val x2 = ((cxPx + wPx / 2f) / inputSize.toFloat() * frameW).coerceIn(0f, frameW.toFloat())
            val y2 = ((cyPx + hPx / 2f) / inputSize.toFloat() * frameH).coerceIn(0f, frameH.toFloat())

            val label = DetectionLabelMapper.cocoLabel(bestClass)
            out.add(Detection(Box(x1, y1, x2, y2), score, label))

            if (AppPreferences.debugOverlay && out.size <= 3) {
                Log.d(
                    "YoloOnnxDetector",
                    "yolo cols=$cols rows=$rows chFirst=$channelFirst hasObj=$hasObjectness cls=$bestClass score=$score box=($x1,$y1,$x2,$y2)"
                )
            }
        }

        return out
    }

    private fun FloatBuffer.toArray(): FloatArray {
        val dup = duplicate()
        dup.rewind()
        return FloatArray(dup.remaining()).also { dup.get(it) }
    }
}

