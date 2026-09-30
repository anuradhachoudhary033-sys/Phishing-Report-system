"""
SOAR Backend — Email Phishing Analyzer

Dedicated feature extractor for email-based phishing detection.
Analyzes sender spoofing, urgency keywords, hidden link mismatches,
suspicious attachments, and runs each embedded URL through the
existing triage scoring pipeline.
"""

from __future__ import annotations

import re
import logging
from urllib.parse import urlparse

from models import EmailScanPayload, EmailThreat, EmailScanResult, URLFeatures
from models.feature_extractor import extract_features, extract_domain, _check_typosquat
from models.triage_scorer import score_url
from models import TriagePayload
from config import BRAND_DOMAINS

logger = logging.getLogger("soar.email")

# ── Urgency keywords commonly found in phishing emails ───────────────────
_URGENCY_KEYWORDS = frozenset({
    "account suspended", "verify your account", "confirm your identity",
    "unauthorized access", "unusual activity", "security alert",
    "verify immediately", "action required", "your account has been",
    "click here to verify", "update your payment", "expire",
    "limited time", "act now", "urgent", "immediately",
    "suspended", "locked", "restricted", "disabled",
    "verify your identity", "confirm your payment",
    "unauthorized transaction", "suspicious activity",
})

# ── Suspicious attachment extensions ─────────────────────────────────────
_DANGEROUS_EXTENSIONS = frozenset({
    ".exe", ".scr", ".bat", ".cmd", ".com", ".pif", ".vbs", ".vbe",
    ".js", ".jse", ".wsf", ".wsh", ".msi", ".msp", ".mst",
    ".cpl", ".hta", ".inf", ".reg", ".rgs", ".sct",
    ".ps1", ".psm1", ".psd1",  # PowerShell
    ".jar", ".py",              # Cross-platform
    ".docm", ".xlsm", ".pptm", # Office macros
    ".iso", ".img",             # Disk images (used to bypass Mark of Web)
})

# ── URL extraction regex ─────────────────────────────────────────────────
_URL_PATTERN = re.compile(
    r'https?://[^\s<>"\')\]]+',
    re.IGNORECASE,
)

# ── Hidden link mismatch pattern (HTML) ──────────────────────────────────
_HREF_PATTERN = re.compile(
    r'<a[^>]+href=["\']([^"\']+)["\'][^>]*>(.*?)</a>',
    re.IGNORECASE | re.DOTALL,
)


def _analyze_sender(payload: EmailScanPayload) -> list[EmailThreat]:
    """Check sender address for spoofing indicators."""
    threats = []
    sender = payload.sender_email.lower()
    display_name = payload.sender_display_name.lower()
    sender_domain = sender.split("@")[-1] if "@" in sender else ""

    # Check if sender domain is a typosquat of a known brand
    if sender_domain:
        typo_score, typo_target = _check_typosquat(sender_domain)
        if typo_score > 0.3:
            threats.append(EmailThreat(
                type="sender_spoofing",
                severity="high",
                detail=f"Sender domain '{sender_domain}' is a typosquat of '{typo_target}' "
                       f"(similarity: {typo_score:.0%})",
                indicator=sender,
            ))

    # Check display name vs sender domain mismatch
    for brand in BRAND_DOMAINS:
        brand_name = brand.split(".")[0]  # "paypal" from "paypal.com"
        if brand_name in display_name and brand_name not in sender_domain:
            threats.append(EmailThreat(
                type="display_name_mismatch",
                severity="high",
                detail=f"Display name contains '{brand_name}' but sender domain "
                       f"is '{sender_domain}' (not {brand})",
                indicator=f"{payload.sender_display_name} <{sender}>",
            ))
            break

    # Check reply-to mismatch
    reply_to = payload.headers.get("reply-to", "").lower()
    if reply_to and "@" in reply_to:
        reply_domain = reply_to.split("@")[-1]
        if reply_domain != sender_domain:
            threats.append(EmailThreat(
                type="reply_to_mismatch",
                severity="medium",
                detail=f"Reply-To domain '{reply_domain}' differs from "
                       f"sender domain '{sender_domain}'",
                indicator=reply_to,
            ))

    return threats


def _analyze_content(payload: EmailScanPayload) -> list[EmailThreat]:
    """Check email body for urgency keywords and social engineering."""
    threats = []
    combined_text = f"{payload.subject} {payload.body_text}".lower()

    matched_keywords = []
    for keyword in _URGENCY_KEYWORDS:
        if keyword in combined_text:
            matched_keywords.append(keyword)

    if len(matched_keywords) >= 2:
        threats.append(EmailThreat(
            type="urgency_keywords",
            severity="medium" if len(matched_keywords) < 4 else "high",
            detail=f"Email contains {len(matched_keywords)} urgency keywords: "
                   f"{', '.join(matched_keywords[:5])}",
            indicator=payload.subject,
        ))

    return threats


def _analyze_links(payload: EmailScanPayload) -> list[EmailThreat]:
    """Check for hidden/mismatched links in HTML body."""
    threats = []

    if not payload.body_html:
        return threats

    for match in _HREF_PATTERN.finditer(payload.body_html):
        href = match.group(1).strip()
        link_text = re.sub(r'<[^>]+>', '', match.group(2)).strip()

        # Skip non-URL link text
        if not link_text or not ("." in link_text):
            continue

        # Check if the link text looks like a URL but points elsewhere
        if link_text.startswith("http") or "." in link_text:
            text_domain = extract_domain(
                link_text if "://" in link_text else f"http://{link_text}"
            )
            href_domain = extract_domain(href)

            if text_domain and href_domain and text_domain != href_domain:
                threats.append(EmailThreat(
                    type="hidden_link_mismatch",
                    severity="high",
                    detail=f"Link text shows '{text_domain}' but actually "
                           f"links to '{href_domain}'",
                    indicator=f"Text: {link_text} → Href: {href}",
                ))

    return threats


def _analyze_attachments(payload: EmailScanPayload) -> list[EmailThreat]:
    """Check attachment filenames for dangerous extensions."""
    threats = []

    for filename in payload.attachment_names:
        lower_name = filename.lower()
        for ext in _DANGEROUS_EXTENSIONS:
            if lower_name.endswith(ext):
                threats.append(EmailThreat(
                    type="dangerous_attachment",
                    severity="high",
                    detail=f"Attachment '{filename}' has a dangerous "
                           f"extension ({ext})",
                    indicator=filename,
                ))
                break

    return threats


def extract_urls_from_email(payload: EmailScanPayload) -> list[str]:
    """Extract all unique URLs from email body (text + HTML)."""
    urls = set()

    # From explicit URLs field
    for url in payload.urls:
        urls.add(url)

    # From body text
    for match in _URL_PATTERN.finditer(payload.body_text):
        urls.add(match.group(0).rstrip(".,;:!?)"))

    # From HTML body
    if payload.body_html:
        for match in _URL_PATTERN.finditer(payload.body_html):
            urls.add(match.group(0).rstrip(".,;:!?)"))
        # Also extract from href attributes
        for match in _HREF_PATTERN.finditer(payload.body_html):
            href = match.group(1).strip()
            if href.startswith("http"):
                urls.add(href.rstrip(".,;:!?)"))

    return list(urls)


async def analyze_email(payload: EmailScanPayload) -> EmailScanResult:
    """
    Full email phishing analysis pipeline.

    1. Analyze sender for spoofing
    2. Check content for urgency keywords
    3. Detect hidden link mismatches
    4. Check attachments for dangerous extensions
    5. Triage-score every embedded URL
    6. Compute overall verdict
    """
    threats: list[EmailThreat] = []

    # Run all analyzers
    threats.extend(_analyze_sender(payload))
    threats.extend(_analyze_content(payload))
    threats.extend(_analyze_links(payload))
    threats.extend(_analyze_attachments(payload))

    # Extract and score URLs
    urls = extract_urls_from_email(payload)
    url_scores: dict[str, float] = {}
    max_url_score = 0.0

    for url in urls[:20]:  # Cap at 20 URLs to prevent abuse
        try:
            triage_payload = TriagePayload(
                url=url,
                client_score=0.0,
                source_ip="email-scanner",
                peer_node="email-scanner",
            )
            result = await score_url(triage_payload)
            url_scores[url] = result.risk_score
            max_url_score = max(max_url_score, result.risk_score)

            if result.is_phishing:
                threats.append(EmailThreat(
                    type="malicious_url",
                    severity="high",
                    detail=f"Embedded URL scored {result.risk_score:.2f} "
                           f"(action: {result.action})",
                    indicator=url,
                ))
        except Exception as e:
            logger.warning("Failed to score URL %s: %s", url, e)

    # Compute overall verdict
    high_count = sum(1 for t in threats if t.severity == "high")
    medium_count = sum(1 for t in threats if t.severity == "medium")

    if high_count >= 2 or (high_count >= 1 and max_url_score > 0.6):
        verdict = "phishing"
        confidence = min(0.95, 0.5 + high_count * 0.15 + max_url_score * 0.3)
    elif high_count >= 1 or medium_count >= 2 or max_url_score > 0.4:
        verdict = "suspicious"
        confidence = min(0.8, 0.3 + high_count * 0.1 + medium_count * 0.1 + max_url_score * 0.2)
    else:
        verdict = "safe"
        confidence = max(0.1, 1.0 - high_count * 0.2 - medium_count * 0.1 - max_url_score)

    logger.info(
        "Email scan: sender=%s subject='%s' verdict=%s threats=%d urls=%d",
        payload.sender_email, payload.subject[:40], verdict,
        len(threats), len(urls),
    )

    return EmailScanResult(
        verdict=verdict,
        confidence=round(confidence, 4),
        threats=threats,
        url_scores=url_scores,
        urls_extracted=len(urls),
        sender_email=payload.sender_email,
        subject=payload.subject,
    )
