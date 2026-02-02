package com.mcaw.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mcaw.model.Box
import com.mcaw.model.Detection
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 ONNX Detector
 * --------------------
 * - ONNX Runtime na Androidu
 * - Vstup: 640x640 RGB, normalizováno do 0..1 (NCHW)
 * - Výstup: nejèastìji [1, 84, 8400] (D,N) nebo [1, 8400, 84] (N,D)
 *   - prvních 4 složek: cx, cy, w, h
 *   - zbytek: class scores (bereme max class jako conf)
 */
class YoloOnnxDetector(
    private val context: Context,
    private val modelName: String = "yolov8n.onnx",
    private val inputSize: Int = 640,
    private val scoreThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f
) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("models/$modelName").readBytes()
        session = env.createSession(modelBytes)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        // 1) Resize + normalize -> Tensor (NCHW: 1x3xH xW)
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val inputTensor = preprocess(resized)

        // 2) Inference
        val inputName = session.inputNames.iterator().next()
        val outputs = session.run(mapOf(inputName to inputTensor))

        // 3) Výstupní tensor + shape + buffer
        val out0 = outputs[0] as OnnxTensor
        val shape = out0.info.shape // napø. [1,84,8400] nebo [1,8400,84]
        val fb: FloatBuffer = out0.floatBuffer
        val total = fb.remaining()
        val flat = FloatArray(total)
        fb.get(flat)

        // 4) Post-process podle shape
        val detections = when (shape.size) {
            3 -> postprocess3D(flat, shape)            // [1, D, N] nebo [1, N, D]
            2 -> postprocess2D(flat, shape)            // [N, 5+C] nebo [N, 5]
            1 -> postprocess1D(flat)                   // [k*5] fallback
            else -> emptyList()
        }

        // 5) NMS
        return nonMaxSuppression(detections.toMutableList())
    }

    // ---------------------------------------------------------------------------
    // PREPROCESS (NCHW, bez mean/std normalizace – lze doplnit podle modelu)
    // ---------------------------------------------------------------------------
    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val imgData = FloatArray(1 * 3 * inputSize * inputSize)
        var idx = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = bitmap.getPixel(x, y)
                // R, G, B v rozsahu 0..1
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                imgData[idx++] = r
                imgData[idx++] = g
                imgData[idx++] = b
            }
        }
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        return OnnxTensor.createTensor(env, imgData, shape)
    }

    // ---------------------------------------------------------------------------
    // POSTPROCESS – 3D výstup
    // ---------------------------------------------------------------------------
    private fun postprocess3D(flat: FloatArray, shape: LongArray): List<Detection> {
        // shape: [1, A, B] – A a B jsou zamìnitelné, zjistíme, co dává smysl
        val a = shape[1].toInt()
        val b = shape[2].toInt()
        val list = ArrayList<Detection>(max(a, b))

        // Pokus A: [1, D, N] (D=5+C, N=pøedpovìdi)
        if (a >= 5) {
            // indexování: (0, c, n) -> ((0*a + c)*b + n)
            val d = a
            val n = b
            for (j in 0 until n) {
                val cx = flat[j + 0 * n]
                val cy = flat[j + 1 * n]
                val w  = flat[j + 2 * n]
                val h  = flat[j + 3 * n]
                // konf z klasí (max od indexu 4)
                var conf = 0f
                var cIdx = 4
                while (cIdx < d) {
                    val v = flat[j + cIdx * n]
                    if (v > conf) conf = v
                    cIdx++
                }
                if (conf > scoreThreshold) {
                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    val x2 = cx + w / 2f
                    val y2 = cy + h / 2f
                    list.add(Detection(box = Box(x1, y1, x2, y2), score = conf, label = "car"))
                }
            }
            return list
        }

        // Pokus B: [1, N, D] (N=pøedpovìdi, D=5+C)
        if (b >= 5) {
            // indexování: (0, n, c) -> ((0*b + n)*b? != správnì) › pøepoèet:
            // Pro [1, N, D]: lineární index (0*N + n)*D + c
            val n = a
            val d = b
            var base = 0
            for (nIdx in 0 until n) {
                val cx = flat[base + 0]
                val cy = flat[base + 1]
                val w  = flat[base + 2]
                val h  = flat[base + 3]
                var conf = 0f
                var cIdx = 4
                while (cIdx < d) {
                    val v = flat[base + cIdx]
                    if (v > conf) conf = v
                    cIdx++
                }
                if (conf > scoreThreshold) {
                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    val x2 = cx + w / 2f
                    val y2 = cy + h / 2f
                    list.add(Detection(box = Box(x1, y1, x2, y2), score = conf, label = "car"))
                }
                base += d
            }
            return list
        }

        return emptyList()
    }

    // ---------------------------------------------------------------------------
    // POSTPROCESS – 2D výstup (øádky jsou detekce, sloupce 5(+C))
    // ---------------------------------------------------------------------------
    private fun postprocess2D(flat: FloatArray, shape: LongArray): List<Detection> {
        val n = shape[0].toInt()
        val d = shape[1].toInt()
        if (d < 5) return emptyList()

        val out = ArrayList<Detection>(n)
        var base = 0
        for (i in 0 until n) {
            val cx = flat[base + 0]
            val cy = flat[base + 1]
            val w  = flat[base + 2]
            val h  = flat[base + 3]
            val conf = if (d == 5) flat[base + 4] else {
                var mx = 0f
                var c = 4
                while (c < d) {
                    val v = flat[base + c]
                    if (v > mx) mx = v
                    c++
                }
                mx
            }
            if (conf > scoreThreshold) {
                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f
                out.add(Detection(box = Box(x1, y1, x2, y2), score = conf, label = "car"))
            }
            base += d
        }
        return out
    }

    // ---------------------------------------------------------------------------
    // POSTPROCESS – 1D fallback: [k*5] => k detekcí (cx,cy,w,h,conf) opakovanì
    // ---------------------------------------------------------------------------
    private fun postprocess1D(flat: FloatArray): List<Detection> {
        if (flat.size < 5) return emptyList()
        val out = ArrayList<Detection>(flat.size / 5)
        var i = 0
        while (i + 4 < flat.size) {
            val cx = flat[i]
            val cy = flat[i + 1]
            val w  = flat[i + 2]
            val h  = flat[i + 3]
            val conf = flat[i + 4]
            if (conf > scoreThreshold) {
                val x1 = cx - w / 2f
                val y1 = cy - h / 2f
                val x2 = cx + w / 2f
                val y2 = cy + h / 2f
                out.add(Detection(box = Box(x1, y1, x2, y2), score = conf, label = "car"))
            }
            i += 5
        }
        return out
    }

    // ---------------------------------------------------------------------------
    // NMS + IoU – pro com.mcaw.model.Box
    // ---------------------------------------------------------------------------
    private fun nonMaxSuppression(boxes: MutableList<Detection>): List<Detection> {
        val picked = mutableListOf<Detection>()
        boxes.sortByDescending { it.score }

        while (boxes.isNotEmpty()) {
            val best = boxes.removeAt(0)
            picked.add(best)

            val it = boxes.iterator()
            while (it.hasNext()) {
                val other = it.next()
                val iou = iou(best.box, other.box)
                if (iou > iouThreshold) it.remove()
            }
        }
        return picked
    }

    private fun iou(a: Box, b: Box): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)

        val interArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val unionArea = a.area + b.area - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }
}
