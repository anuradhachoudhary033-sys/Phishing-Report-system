// soar_core.h
#ifndef SOAR_CORE_H
#define SOAR_CORE_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    double risk_score;
    bool is_phishing;
    uint32_t bloom_match_count;
} TriageResult;

// Initialize the counting bloom filter
void init_bloom_filter(size_t capacity);

// High-speed URL feature extraction and local IOC lookup
TriageResult evaluate_url_fast(const char* url);

#ifdef __cplusplus
}
#endif

#endif // SOAR_CORE_H
