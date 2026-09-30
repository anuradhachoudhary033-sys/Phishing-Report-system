// soar_core.cpp
#include "soar_core.h"
#include <string.h>

// Dummy implementation for bloom filter
void init_bloom_filter(size_t capacity) {
    // TODO: Implement actual Counting Bloom Filter allocation
}

TriageResult evaluate_url_fast(const char* url) {
    TriageResult result;
    result.risk_score = 0.0;
    result.is_phishing = false;
    result.bloom_match_count = 0;

    if (url == nullptr) {
        return result;
    }

    // Basic heuristic: check length or specific keywords
    size_t len = strlen(url);
    if (len > 50) {
        result.risk_score += 0.4;
    }
    
    // Simulate finding a phishing keyword
    if (strstr(url, "login") != nullptr || strstr(url, "secure") != nullptr) {
        result.risk_score += 0.4;
        result.bloom_match_count = 1;
    }

    if (result.risk_score > 0.5) {
        result.is_phishing = true;
    }

    return result;
}
