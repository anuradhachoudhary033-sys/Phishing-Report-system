"""
SOAR Backend — Centralized Configuration

All tunable parameters in one place. Environment variables
override defaults when set.
"""

import os
from pathlib import Path

# ── Paths ────────────────────────────────────────────────────────────────
BASE_DIR = Path(__file__).resolve().parent
MODELS_DIR = BASE_DIR / "models"
WEIGHTS_DIR = MODELS_DIR / "weights"
SANDBOX_DIR = BASE_DIR / "sandbox"
BASELINES_DIR = SANDBOX_DIR / "baselines"
REPORTING_DIR = BASE_DIR / "reporting"
QUARANTINE_DIR = BASE_DIR / "quarantine"  # Isolated download folder
DB_PATH = BASE_DIR / "campaign.db"

# Ensure critical directories exist
QUARANTINE_DIR.mkdir(exist_ok=True)
WEIGHTS_DIR.mkdir(parents=True, exist_ok=True)
BASELINES_DIR.mkdir(parents=True, exist_ok=True)

# ── Server ───────────────────────────────────────────────────────────────
HOST = os.getenv("SOAR_HOST", "0.0.0.0")
PORT = int(os.getenv("SOAR_PORT", "8000"))

# ── Triage Thresholds ────────────────────────────────────────────────────
BLOCK_THRESHOLD = 0.95      # Score > this → instant block at edge
SANDBOX_THRESHOLD = 0.50    # Score > this but < BLOCK → forward to sandbox
ONNX_MODEL_PATH = WEIGHTS_DIR / "phishing_minilm.onnx"

# ── Sandbox ──────────────────────────────────────────────────────────────
SANDBOX_TIMEOUT_MS = 30_000          # Max page load time
SANDBOX_USE_DOCKER = os.getenv("SOAR_USE_DOCKER", "false").lower() == "true"
DOCKER_IMAGE = "mcr.microsoft.com/playwright:v1.48.0-jammy"

# ── Brand Detection ─────────────────────────────────────────────────────
# Hamming distance threshold: lower = stricter match
BRAND_HASH_THRESHOLD = 12

# ── Campaign Aggregation ────────────────────────────────────────────────
CAMPAIGN_REPORT_THRESHOLD = 20  # Instances before auto-generating CERT-In report

# ── Top 30 Phishing Targets (bounded Levenshtein whitelist) ──────────────
# Restricted to avoid O(m*n) explosion on every triage request
BRAND_DOMAINS = [
    "microsoft.com", "google.com", "apple.com", "amazon.com",
    "paypal.com", "netflix.com", "facebook.com", "instagram.com",
    "linkedin.com", "twitter.com", "dropbox.com", "adobe.com",
    "chase.com", "wellsfargo.com", "bankofamerica.com", "citibank.com",
    "hsbc.com", "barclays.com", "americanexpress.com", "usbank.com",
    "sbi.co.in", "hdfcbank.com", "icicibank.com", "axisbank.com",
    "yahoo.com", "outlook.com", "icloud.com", "github.com",
    "steam.com", "ebay.com",
]

# Length-delta pre-filter: skip Levenshtein if domain lengths differ by more than this
LEVENSHTEIN_LENGTH_DELTA = 3
LEVENSHTEIN_THRESHOLD = 3  # Max edit distance to flag as typosquat
