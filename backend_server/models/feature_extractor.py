"""
SOAR Backend — URL Feature Extractor

High-speed structural feature extraction for URL risk scoring.
Mirrors the C++ edge engine's heuristics but in Python for
server-side deep analysis.

Architectural constraint: Levenshtein brand comparison is bounded
to the top 30 targets with length-delta pre-filtering to prevent
O(m*n) explosion on every request.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from urllib.parse import urlparse

from Levenshtein import distance as levenshtein_distance

from config import (
    BRAND_DOMAINS,
    LEVENSHTEIN_LENGTH_DELTA,
    LEVENSHTEIN_THRESHOLD,
)
from models import URLFeatures

# ── Suspicious keyword set ───────────────────────────────────────────────
_SUSPICIOUS_KEYWORDS = frozenset({
    "login", "signin", "sign-in", "secure", "verify", "account",
    "update", "confirm", "billing", "password", "credential",
    "suspend", "unusual", "alert", "unlock", "restore", "recover",
    "authenticate", "validate", "wallet", "bank", "payment",
})

# ── High-risk TLDs ──────────────────────────────────────────────────────
_RISKY_TLDS = frozenset({
    ".xyz", ".cc", ".top", ".tk", ".ml", ".ga", ".cf", ".gq",
    ".pw", ".buzz", ".club", ".work", ".info", ".online", ".site",
    ".icu", ".review", ".stream", ".click", ".link",
})

# ── IP address pattern ──────────────────────────────────────────────────
_IP_PATTERN = re.compile(
    r"\b(?:\d{1,3}\.){3}\d{1,3}\b"
)


def compute_entropy(text: str) -> float:
    """Shannon entropy of a string (higher = more random / suspicious)."""
    if not text:
        return 0.0
    freq = Counter(text)
    length = len(text)
    return -sum(
        (count / length) * math.log2(count / length)
        for count in freq.values()
    )


def extract_domain(url: str) -> str:
    """Extract the registrable domain from a URL."""
    try:
        parsed = urlparse(url if "://" in url else f"http://{url}")
        hostname = parsed.hostname or ""
        # Strip www.
        if hostname.startswith("www."):
            hostname = hostname[4:]
        return hostname.lower()
    except Exception:
        return ""


def _check_typosquat(domain: str) -> tuple[float, str | None]:
    """
    Check if a domain is a typosquat of a known brand.

    Uses length-delta pre-filtering to skip expensive Levenshtein
    computations when string lengths are too different to be a
    plausible typosquat.

    Returns (score, matched_brand) where score is 0.0 (no match)
    to 1.0 (exact match).
    """
    if not domain:
        return 0.0, None

    # Strip TLD for comparison
    domain_base = domain.rsplit(".", 1)[0] if "." in domain else domain
    best_score = 0.0
    best_brand: str | None = None

    for brand in BRAND_DOMAINS:
        brand_base = brand.rsplit(".", 1)[0] if "." in brand else brand

        # Length-delta pre-filter: skip if lengths differ too much
        if abs(len(domain_base) - len(brand_base)) > LEVENSHTEIN_LENGTH_DELTA:
            continue

        dist = levenshtein_distance(domain_base, brand_base)

        if dist == 0:
            # Exact match — not a typosquat, it's the real domain
            return 0.0, None

        if dist <= LEVENSHTEIN_THRESHOLD:
            # Convert distance to a 0–1 score (lower distance = higher score)
            score = 1.0 - (dist / (LEVENSHTEIN_THRESHOLD + 1))
            if score > best_score:
                best_score = score
                best_brand = brand

    return best_score, best_brand


def extract_features(url: str) -> URLFeatures:
    """
    Extract all structural features from a URL for risk scoring.

    Returns a URLFeatures object with normalised scores ready
    for the triage scorer to combine.
    """
    parsed = urlparse(url if "://" in url else f"http://{url}")
    hostname = parsed.hostname or ""
    path = parsed.path or ""
    full_url = url.lower()

    # Domain analysis
    domain = extract_domain(url)
    parts = hostname.split(".")
    subdomain_depth = max(0, len(parts) - 2)  # e.g. a.b.example.com = depth 2

    # Path analysis
    path_segments = [s for s in path.split("/") if s]
    path_depth = len(path_segments)

    # Special character ratio in URL
    special_chars = sum(1 for c in full_url if not c.isalnum() and c not in ":/.-_")
    special_char_ratio = special_chars / max(len(full_url), 1)

    # IP address in hostname
    has_ip = bool(_IP_PATTERN.match(hostname))

    # Suspicious keyword count
    keyword_count = sum(1 for kw in _SUSPICIOUS_KEYWORDS if kw in full_url)

    # TLD risk
    tld = "." + parts[-1] if parts else ""
    tld_risk = 0.8 if tld in _RISKY_TLDS else 0.0

    # Entropy
    entropy = compute_entropy(full_url)

    # Typosquatting
    typo_score, typo_target = _check_typosquat(domain)

    return URLFeatures(
        entropy=round(entropy, 4),
        url_length=len(full_url),
        subdomain_depth=subdomain_depth,
        path_depth=path_depth,
        special_char_ratio=round(special_char_ratio, 4),
        has_ip_address=has_ip,
        suspicious_keyword_count=keyword_count,
        tld_risk=tld_risk,
        typosquat_score=round(typo_score, 4),
        typosquat_target=typo_target,
    )
