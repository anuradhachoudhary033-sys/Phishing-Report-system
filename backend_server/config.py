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
PORT = int(os.getenv("PORT", os.getenv("SOAR_PORT", "8000")))

# ── Triage Thresholds ────────────────────────────────────────────────────
BLOCK_THRESHOLD = 0.70      # Score > this → instant block at edge
SANDBOX_THRESHOLD = 0.35    # Score > this but < BLOCK → forward to sandbox
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

# ── Gmail OAuth / Pub/Sub ────────────────────────────────────────────────
GMAIL_CLIENT_SECRETS_FILE = os.getenv(
    "GMAIL_CLIENT_SECRETS", str(BASE_DIR / "client_secret.json")
)
# Auto-create or sync client_secret.json on Render / Cloud from environment secret
client_secret_env = os.getenv("CLIENT_SECRET_JSON")
if client_secret_env:
    try:
        secret_path = Path(GMAIL_CLIENT_SECRETS_FILE)
        clean_content = client_secret_env.strip()
        if not secret_path.exists() or secret_path.read_text(encoding="utf-8").strip() != clean_content:
            secret_path.write_text(clean_content, encoding="utf-8")
    except Exception as e:
        print(f"[config] Warning: Failed to write CLIENT_SECRET_JSON: {e}")
# gmail.modify allows auto-labeling detected phishing emails in the user's mailbox
GMAIL_SCOPES = ["https://www.googleapis.com/auth/gmail.modify"]
# ⚠️ REPLACE with your actual GCP project ID after creating the Pub/Sub topic
GMAIL_PUBSUB_TOPIC = os.getenv(
    "GMAIL_PUBSUB_TOPIC",
    "projects/anti-phishingml/topics/gmail-phishing-alerts",
)

# Allow HTTP for local OAuth testing (remove in production with HTTPS)
if os.getenv("OAUTHLIB_INSECURE_TRANSPORT") is None:
    os.environ["OAUTHLIB_INSECURE_TRANSPORT"] = "1"

# Allow Google Sign-In on Android to include openid/email/profile alongside gmail.modify
if os.getenv("OAUTHLIB_RELAX_TOKEN_SCOPE") is None:
    os.environ["OAUTHLIB_RELAX_TOKEN_SCOPE"] = "1"

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
