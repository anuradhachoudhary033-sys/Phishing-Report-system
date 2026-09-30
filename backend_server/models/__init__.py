"""
SOAR Backend — Pydantic Schemas

Shared data models used across triage, sandbox, and reporting layers.
"""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


# ── Layer 1: Triage ──────────────────────────────────────────────────────

class TriagePayload(BaseModel):
    """Inbound telemetry from a mobile edge client."""
    url: str
    headers: dict = Field(default_factory=dict)
    client_score: float = 0.0
    source_ip: str = "0.0.0.0"
    peer_node: str = "unknown"


class URLFeatures(BaseModel):
    """Extracted structural features from a URL."""
    entropy: float = 0.0
    url_length: int = 0
    subdomain_depth: int = 0
    path_depth: int = 0
    special_char_ratio: float = 0.0
    has_ip_address: bool = False
    suspicious_keyword_count: int = 0
    tld_risk: float = 0.0
    typosquat_score: float = 0.0
    typosquat_target: Optional[str] = None


class TriageResult(BaseModel):
    """Scoring output from the triage pipeline."""
    url: str
    risk_score: float
    action: str               # BLOCK_INSTANT | QUEUE_SANDBOX | ALLOW
    features: URLFeatures
    is_phishing: bool = False
    bloom_match_count: int = 0


# ── Layer 2: Sandbox ─────────────────────────────────────────────────────

class DetonationRequest(BaseModel):
    """Request to detonate a suspicious URL in the sandbox."""
    url: str
    triage_score: float
    source_ip: str = "0.0.0.0"
    peer_node: str = "unknown"


class DetonationResult(BaseModel):
    """Structured output from a sandbox detonation."""
    url: str
    final_url: str = ""
    redirect_chain: list[str] = Field(default_factory=list)
    screenshot_path: Optional[str] = None
    has_login_form: bool = False
    downloaded_files: list[dict] = Field(default_factory=list)
    external_ips: list[str] = Field(default_factory=list)
    brand_match: Optional[str] = None
    brand_confidence: float = 0.0
    verdict: str = "clean"         # malicious | suspicious | clean
    confidence_score: float = 0.0
    detonated_at: datetime = Field(default_factory=datetime.utcnow)
    error: Optional[str] = None


# ── Layer 3: Reporting ───────────────────────────────────────────────────

class MITRETechnique(BaseModel):
    """A single MITRE ATT&CK technique mapping."""
    technique_id: str              # e.g. T1566.002
    technique_name: str
    tactic: str                    # e.g. Initial Access
    description: str = ""


class IntelReport(BaseModel):
    """A CERT-In compliant intelligence report."""
    report_id: str
    cert_in_ref_no: str
    status: str = "generated"      # generated | submitted | acknowledged
    generated_at: datetime = Field(default_factory=datetime.utcnow)
    download_url: str = ""
    campaign_domain: str = ""
    campaign_ip: str = ""
    incident_count: int = 0
    mitre_techniques: list[MITRETechnique] = Field(default_factory=list)
    detonation_results: list[DetonationResult] = Field(default_factory=list)


# ── WebSocket Events ─────────────────────────────────────────────────────

class PhishingEvent(BaseModel):
    """Live event streamed to connected Flutter clients via WebSocket."""
    id: str
    url: str
    risk_score: float
    is_phishing: bool
    bloom_match_count: int = 0
    source_ip: str = "0.0.0.0"
    peer_node: str = "unknown"
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class IOCUpdate(BaseModel):
    """New IOC pushed to mobile clients to update Bloom Filters."""
    url: str
    risk_score: float
    c2_ip: Optional[str] = None
    payload_hash: Optional[str] = None
    timestamp: datetime = Field(default_factory=datetime.utcnow)


# ── Email Scanning ───────────────────────────────────────────────────────

class EmailScanPayload(BaseModel):
    """Inbound email data for phishing analysis."""
    sender_email: str
    sender_display_name: str = ""
    subject: str = ""
    body_text: str = ""
    body_html: str = ""
    urls: list[str] = Field(default_factory=list)
    headers: dict = Field(default_factory=dict)
    attachment_names: list[str] = Field(default_factory=list)
    message_id: str = ""


class EmailThreat(BaseModel):
    """A single threat finding from email analysis."""
    type: str          # sender_spoofing | display_name_mismatch | reply_to_mismatch
                       # | urgency_keywords | hidden_link_mismatch | dangerous_attachment
                       # | malicious_url
    severity: str      # high | medium | low
    detail: str
    indicator: str = ""


class EmailScanResult(BaseModel):
    """Complete email scan result."""
    verdict: str = "safe"          # safe | suspicious | phishing
    confidence: float = 0.0
    threats: list[EmailThreat] = Field(default_factory=list)
    url_scores: dict[str, float] = Field(default_factory=dict)
    urls_extracted: int = 0
    sender_email: str = ""
    subject: str = ""
    scanned_at: datetime = Field(default_factory=datetime.utcnow)

