// soar_core.cpp
#include "soar_core.h"
#include <string.h>

#ifdef __ANDROID__
#include <jni.h>
#endif

// Dummy implementation for bloom filter
void init_bloom_filter(size_t capacity) {
    // Counting Bloom Filter allocation
}

TriageResult evaluate_url_fast(const char* url) {
    TriageResult result;
    result.risk_score = 0.0;
    result.is_phishing = false;
    result.bloom_match_count = 0;

    if (url == nullptr) {
        return result;
    }

    size_t len = strlen(url);

    // Length-based heuristics
    if (len > 50) result.risk_score += 0.15;
    if (len > 80) result.risk_score += 0.15;

    // Additive keyword detection
    const char* keywords[] = {"login", "secure", "verify", "account", "update", "banking", "signin", "support"};
    int matches = 0;
    for (const char* kw : keywords) {
        if (strstr(url, kw) != nullptr) {
            matches++;
            result.risk_score += 0.2; // Each keyword increases suspicion
        }
    }
    result.bloom_match_count = matches;

    // Detect high hyphen density (common in phishing URLs)
    int hyphens = 0;
    for (size_t i = 0; i < len; ++i) {
        if (url[i] == '-') hyphens++;
    }
    if (hyphens > 2) result.risk_score += 0.1;
    if (hyphens > 4) result.risk_score += 0.2;

    // Cap the score at 1.0
    if (result.risk_score > 1.0) {
        result.risk_score = 1.0;
    }

    if (result.risk_score > 0.5) {
        result.is_phishing = true;
    }

    return result;
}

#ifdef __ANDROID__
extern "C" JNIEXPORT jdouble JNICALL
Java_com_soar_client_nativecore_NativeCore_evaluateUrlFastNative(
    JNIEnv* env,
    jobject /* thiz */,
    jstring url_str
) {
    if (!url_str) {
        return 0.0;
    }
    const char* native_url = env->GetStringUTFChars(url_str, nullptr);
    TriageResult res = evaluate_url_fast(native_url);
    env->ReleaseStringUTFChars(url_str, native_url);
    return static_cast<jdouble>(res.risk_score);
}
#endif
