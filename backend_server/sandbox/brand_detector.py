"""
SOAR Backend — Visual Brand Spoofing Detector

Uses perceptual hashing (pHash) to compare detonated page screenshots
against a baseline set of known brand login pages.

Perceptual hashing is used instead of pixel-exact comparison because
phishing kits frequently introduce minor visual variations (different
background colors, slightly different logos, added fields) that would
defeat exact matching but preserve the overall visual structure.
"""

from __future__ import annotations

import logging
from pathlib import Path

from PIL import Image
import imagehash

from config import BASELINES_DIR, BRAND_HASH_THRESHOLD

logger = logging.getLogger("soar.brand")

# ── Baseline hash cache ─────────────────────────────────────────────────
# Loaded once at module import; maps brand_name → pHash
_baseline_hashes: dict[str, imagehash.ImageHash] = {}


def _load_baselines():
    """
    Load and hash all baseline brand screenshots from the baselines/ dir.

    Expected naming convention: baselines/microsoft.png, baselines/paypal.png, etc.
    """
    global _baseline_hashes

    if not BASELINES_DIR.exists():
        logger.info("No baselines directory found at %s", BASELINES_DIR)
        return

    for img_path in BASELINES_DIR.glob("*.png"):
        try:
            img = Image.open(img_path)
            phash = imagehash.phash(img)
            brand_name = img_path.stem.lower()
            _baseline_hashes[brand_name] = phash
            logger.info("Loaded baseline hash for '%s'", brand_name)
        except Exception as e:
            logger.warning("Failed to load baseline %s: %s", img_path, e)

    if _baseline_hashes:
        logger.info("Loaded %d brand baselines", len(_baseline_hashes))
    else:
        logger.info("No brand baselines loaded — brand detection disabled")


# Load on import
_load_baselines()


def detect_brand_spoofing(
    screenshot_path: str | Path,
) -> tuple[str | None, float]:
    """
    Compare a detonation screenshot against known brand baselines.

    Returns (brand_name, confidence) where:
    - brand_name is the matched brand (or None if no match)
    - confidence is a 0.0–1.0 score (higher = closer match)

    Uses Hamming distance between perceptual hashes. A distance
    below BRAND_HASH_THRESHOLD indicates a visual match.
    """
    if not _baseline_hashes:
        return None, 0.0

    screenshot_path = Path(screenshot_path)
    if not screenshot_path.exists():
        logger.warning("Screenshot not found: %s", screenshot_path)
        return None, 0.0

    try:
        target_img = Image.open(screenshot_path)
        target_hash = imagehash.phash(target_img)
    except Exception as e:
        logger.warning("Failed to hash screenshot %s: %s", screenshot_path, e)
        return None, 0.0

    best_brand: str | None = None
    best_distance = float("inf")

    for brand_name, baseline_hash in _baseline_hashes.items():
        distance = target_hash - baseline_hash  # Hamming distance
        if distance < best_distance:
            best_distance = distance
            best_brand = brand_name

    if best_distance <= BRAND_HASH_THRESHOLD:
        # Convert distance to confidence (0 distance = 1.0 confidence)
        confidence = max(0.0, 1.0 - (best_distance / (BRAND_HASH_THRESHOLD * 2)))
        logger.info(
            "Brand match: %s (distance=%d, confidence=%.2f)",
            best_brand, best_distance, confidence,
        )
        return best_brand, round(confidence, 4)

    return None, 0.0


def add_baseline(brand_name: str, image_path: str | Path) -> bool:
    """
    Add a new brand baseline image to the detection set.

    Saves the hash in memory and copies the image to the baselines dir.
    """
    try:
        img = Image.open(image_path)
        phash = imagehash.phash(img)
        _baseline_hashes[brand_name.lower()] = phash

        # Copy to baselines directory for persistence
        dest = BASELINES_DIR / f"{brand_name.lower()}.png"
        img.save(str(dest))

        logger.info("Added baseline for '%s' (hash=%s)", brand_name, phash)
        return True
    except Exception as e:
        logger.error("Failed to add baseline for '%s': %s", brand_name, e)
        return False
