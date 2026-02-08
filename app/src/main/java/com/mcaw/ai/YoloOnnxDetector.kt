package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mcaw.config.AppPreferences
import com.mcaw.model.Box
import com.mcaw.model.Detection
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloOnnxDetector(
    private val context: Context,
    private val modelName: String = "yolov8n.onnx",
    val inputSize: Int = 640,
    val scoreThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // Reuse buffers to reduce GC / CPU.
    private val pixels = IntArray(inputSize * inputSize)
    private val hw = inputSize * inputSize
    private val chw = 3 * hw
    private val data = FloatArray(chw)

    init {
        val bytes = context.assets.open("models/$modelName").readBytes()
        session = env.createSession(bytes)
    }

    fun detect(bitmap: Bitmap): List<Detection> = detect(bitmap, null)

    /**
     * ROI crop in pixel coordinates of the provided bitmap (after rotation).
     * Speeds up preprocessing and reduces "noise" outside ROI.
     *
     * Returned boxes are always in coordinates of the original [bitmap] (full frame).
     */
    fun detect(bitmap: Bitmap, roiPx: Rect?): List<Detection> {
        val crop = cropToRoi(bitmap, roiPx)
        val cropBitmap = crop.bitmap
        val resized = Bitmap.createScaledBitmap(cropBitmap, inputSize, inputSize, false)

        val inputName = session.inputNames.iterator().next()

        preprocessNchw(resized).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val out = result[0] as OnnxTensor
                out.use {
                    val arr = it.floatBuffer.toArray()
                    val dets = parseYoloOutput(arr, it.info.shape, cropBitmap.width, cropBitmap.height)
                    if (crop.offsetX == 0 && crop.offsetY == 0) return dets
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
            }
        }
    }

    private data class CropResult(val bitmap: Bitmap, val offsetX: Float, val offsetY: Float)

    private fun cropToRoi(src: Bitmap, roiPx: Rect?): CropResult {
        if (roiPx == null) return CropResult(src, 0f, 0f)

        val l = roiPx.left.coerceIn(0, max(0, src.width - 1))
        val t = roiPx.top.coerceIn(0, max(0, src.height - 1))
        val r = roiPx.right.coerceIn(l + 1, src.width)
        val b = roiPx.bottom.coerceIn(t + 1, src.height)

        val w = max(1, r - l)
        val h = max(1, b - t)

        // createBitmap may share pixels depending on config; ok for our use.
        val cropped = Bitmap.createBitmap(src, l, t, w, h)
        return CropResult(cropped, l.toFloat(), t.toFloat())
    }

    private fun preprocessNchw(bmp: Bitmap): OnnxTensor {
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // NCHW float32 normalized 0..1
        var i = 0
        val offG = hw
        val offB = 2 * hw
        while (i < hw) {
            val px = pixels[i]
            data[i] = ((px shr 16) and 0xFF) / 255f
            data[offG + i] = ((px shr 8) and 0xFF) / 255f
            data[offB + i] = (px and 0xFF) / 255f
            i++
        }

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )
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

        val out = ArrayList<Detection>(16)

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
