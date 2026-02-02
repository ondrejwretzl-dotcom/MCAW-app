package com.mcaw.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vytvoøení OnnxTensoru z CHW float pole (NCHW -> [1,C,H,W]) pøes direct ByteBuffer.
 * Viz oficiální Javadoc/ukázky ORT pro Java/Android.
 */
object OnnxInput {

    /**
     * @param env OrtEnvironment
     * @param chw FloatArray velikosti C*H*W (kanály-first)
     * @param c poèet kanálù
     * @param h výška
     * @param w šíøka
     */
    fun fromCHW(env: OrtEnvironment, chw: FloatArray, c: Int, h: Int, w: Int): OnnxTensor {
        val direct = ByteBuffer.allocateDirect(4 * c * h * w).order(ByteOrder.nativeOrder())
        direct.asFloatBuffer().put(chw)
        val shape = longArrayOf(1L, c.toLong(), h.toLong(), w.toLong())
        return OnnxTensor.createTensor(env, direct, shape)
    }
}
