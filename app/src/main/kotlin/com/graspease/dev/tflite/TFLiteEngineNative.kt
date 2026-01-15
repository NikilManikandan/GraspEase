package com.graspease.dev.tflite

import android.util.Log

object TFLiteEngineNative {
    private var loaded = false

    init {
        try {
            System.loadLibrary("audioEngine")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("TFLiteEngineNative", "audioEngine native lib missing for this ABI", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    external fun createEngine(): Long
    external fun initVocab(ptr: Long, multilingual: Boolean): Int
    external fun computeMel(ptr: Long, samples: FloatArray): FloatArray
    external fun decodeTokens(ptr: Long, tokens: IntArray): String
    external fun free(ptr: Long)
}
