package com.soar.client.nativecore

import android.util.Log

/**
 * JNI Bridge to high-speed C++ Native Core (soar_core).
 * Used for on-device heuristic URL feature extraction and local IOC bloom filter lookups.
 */
object NativeCore {

    private var isLoaded = false

    init {
        try {
            System.loadLibrary("soar_core")
            isLoaded = true
            Log.i("NativeCore", "Successfully loaded native soar_core.so")
        } catch (t: Throwable) {
            Log.w("NativeCore", "Could not load native soar_core library: ${t.message}")
            isLoaded = false
        }
    }

    fun isNativeReady(): Boolean = isLoaded

    /**
     * Evaluates a URL locally using the C++ core engine.
     * Returns a fast risk score [0.0 - 1.0].
     */
    fun evaluateUrl(url: String): Double {
        return if (isLoaded) {
            try {
                evaluateUrlFastNative(url)
            } catch (e: UnsatisfiedLinkError) {
                fallbackEvaluateUrl(url)
            }
        } else {
            fallbackEvaluateUrl(url)
        }
    }

    private external fun evaluateUrlFastNative(url: String): Double

    /**
     * Fallback Kotlin evaluation if native binary is unavailable.
     */
    private fun fallbackEvaluateUrl(url: String): Double {
        var score = 0.0
        val lower = url.lowercase()
        if (url.length > 50) score += 0.4
        if (lower.contains("login") || lower.contains("secure") || lower.contains("verify") || lower.contains("account")) {
            score += 0.4
        }
        return score.coerceAtMost(1.0)
    }
}
