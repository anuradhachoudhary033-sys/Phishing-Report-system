"""
SOAR Backend — Triage Scorer

Orchestrates URL feature extraction and (optionally) ONNX model
inference to produce a final 0.0–1.0 risk score.

When an ONNX model file is present in models/weights/, its output
is blended with heuristic scores. Otherwise, the heuristic pipeline
runs standalone.
"""

from __future__ import annotations

import logging
from pathlib import Path

from config import BLOCK_THRESHOLD, SANDBOX_THRESHOLD, ONNX_MODEL_PATH
from models import TriagePayload, TriageResult, URLFeatures
from models.feature_extractor import extract_features

logger = logging.getLogger("soar.triage")

# ── Optional ONNX runtime ───────────────────────────────────────────────
_onnx_session = None

try:
    import onnxruntime as ort

    if Path(ONNX_MODEL_PATH).exists():
        _onnx_session = ort.InferenceSession(
            str(ONNX_MODEL_PATH),
            providers=["CPUExecutionProvider"],
        )
        logger.info("ONNX model loaded from %s", ONNX_MODEL_PATH)
    else:
        logger.info(
            "No ONNX model found at %s — using heuristic scoring only",
            ONNX_MODEL_PATH,
        )
except ImportError:
    logger.warning("onnxruntime not installed — heuristic scoring only")


# ── Feature weights (calibrated to edge thresholds) ─────────────────────
# Each weight represents how much a normalised feature contributes
# to the final risk score.  Weights sum to ~1.0.
_WEIGHTS = {
    "entropy":            0.10,   # High entropy → suspicious random strings
    "url_length":         0.08,   # Very long URLs → obfuscation
    "subdomain_depth":    0.10,   # Deep subdomains → trust dilution
    "path_depth":         0.05,   # Deep paths → redirect chains
    "special_char_ratio": 0.08,   # Excessive encoding → evasion
    "has_ip_address":     0.12,   # IP in hostname → classic phishing
    "keyword_score":      0.20,   # Suspicious keywords → direct signal
    "tld_risk":           0.10,   # Risky TLD → disposable domains
    "typosquat":          0.17,   # Brand impersonation → high intent
}


def _normalise_entropy(entropy: float) -> float:
    """Normalise Shannon entropy to 0–1 (typical URL entropy 3–5 bits)."""
    return min(max((entropy - 3.0) / 3.0, 0.0), 1.0)


def _normalise_length(length: int) -> float:
    """Normalise URL length to 0–1 (suspicious above ~80 chars)."""
    return min(max((length - 30) / 150.0, 0.0), 1.0)


def _normalise_subdomain(depth: int) -> float:
    """Normalise subdomain depth to 0–1."""
    return min(depth / 4.0, 1.0)


def _normalise_path(depth: int) -> float:
    """Normalise path depth to 0–1."""
    return min(depth / 6.0, 1.0)


def _compute_heuristic_score(features: URLFeatures) -> float:
    """
    Compute a weighted heuristic risk score from extracted features.

    Returns a float in [0.0, 1.0].
    """
    score = 0.0

    score += _WEIGHTS["entropy"] * _normalise_entropy(features.entropy)
    score += _WEIGHTS["url_length"] * _normalise_length(features.url_length)
    score += _WEIGHTS["subdomain_depth"] * _normalise_subdomain(features.subdomain_depth)
    score += _WEIGHTS["path_depth"] * _normalise_path(features.path_depth)
    score += _WEIGHTS["special_char_ratio"] * min(features.special_char_ratio * 5, 1.0)
    score += _WEIGHTS["has_ip_address"] * (1.0 if features.has_ip_address else 0.0)

    # Keyword score: each keyword adds weight, capped at 3
    keyword_norm = min(features.suspicious_keyword_count / 3.0, 1.0)
    score += _WEIGHTS["keyword_score"] * keyword_norm

    score += _WEIGHTS["tld_risk"] * features.tld_risk
    score += _WEIGHTS["typosquat"] * features.typosquat_score

    return round(min(max(score, 0.0), 1.0), 4)


def _determine_action(score: float) -> str:
    """Map a risk score to an action based on configured thresholds."""
    if score >= BLOCK_THRESHOLD:
        return "BLOCK_INSTANT"
    if score >= SANDBOX_THRESHOLD:
        return "QUEUE_SANDBOX"
    return "ALLOW"


async def score_url(payload: TriagePayload) -> TriageResult:
    """
    Full triage pipeline: extract features → score → classify.

    If an ONNX model is loaded, blends model output (70%) with
    heuristic score (30%). Otherwise, uses heuristics alone.
    """
    features = extract_features(payload.url)
    heuristic_score = _compute_heuristic_score(features)

    final_score = heuristic_score

    # Blend with ONNX model if available
    if _onnx_session is not None:
        try:
            import numpy as np

            feature_vector = np.array([[
                features.entropy,
                features.url_length / 200.0,
                features.subdomain_depth / 4.0,
                features.path_depth / 6.0,
                features.special_char_ratio,
                1.0 if features.has_ip_address else 0.0,
                features.suspicious_keyword_count / 3.0,
                features.tld_risk,
                features.typosquat_score,
            ]], dtype=np.float32)

            input_name = _onnx_session.get_inputs()[0].name
            onnx_output = _onnx_session.run(None, {input_name: feature_vector})
            model_score = float(onnx_output[0][0])

            # Blend: 70% model, 30% heuristic
            final_score = round(0.7 * model_score + 0.3 * heuristic_score, 4)
        except Exception as e:
            logger.warning("ONNX inference failed, falling back to heuristic: %s", e)
            final_score = heuristic_score

    # Also incorporate the client's edge score if provided
    if payload.client_score > 0:
        # Weighted blend: 60% server, 40% client edge
        final_score = round(0.6 * final_score + 0.4 * payload.client_score, 4)

    action = _determine_action(final_score)

    return TriageResult(
        url=payload.url,
        risk_score=final_score,
        action=action,
        features=features,
        is_phishing=final_score >= SANDBOX_THRESHOLD,
        bloom_match_count=0,
    )
