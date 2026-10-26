"""
SOAR Backend — Email Phishing Analyzer

Dedicated feature extractor for email-based phishing detection.
Analyzes sender authentication (SPF / DKIM / DMARC), sender spoofing,
urgency phrasing, hidden link mismatches, suspicious attachments, and
runs embedded third-party URLs through the triage scoring pipeline.

Design notes (false-positive control):
  * A sender that passes DMARC (or aligned DKIM/SPF) really is who the
    From: header claims.  Newsletters from linkedin.com / substack.com /
    nse.co.in are therefore not "spoofed", and links back to the sender's
    own domain are not scored as third-party threats.
  * URL scoring ignores query strings: marketing trackers stuff words like
    "account", "update", "verify" into parameters, which says nothing
    about the destination.
  * Repeated findings of the same kind (e.g. 15 tracking links) collapse
    into ONE threat so a long newsletter can't rack up a fake threat count.
"""

from __future__ import annotations

import re
import logging
from email.utils import parseaddr
from urllib.parse import urlparse

from models import EmailScanPayload, EmailThreat, EmailScanResult
from models.feature_extractor import extract_domain, registrable_domain, _check_typosquat
from models.triage_scorer import score_url
from models import TriagePayload
from config import BRAND_DOMAINS, BLOCK_THRESHOLD

logger = logging.getLogger("soar.email")

# ── Social-engineering phrases (multi-word only; single words like "urgent"
#    or "expire" appear in every bank statement and receipt) ──────────────
_URGENCY_PHRASES = (
    "account suspended", "account will be suspended", "verify your account",
    "confirm your identity", "verify your identity", "unauthorized access",
    "verify immediately", "click here to verify", "update your payment",
    "confirm your payment", "unauthorized transaction", "your account has been locked",
    "your account has been suspended", "failure to verify", "within 24 hours",
    "reset your password immediately", "login attempt was blocked",
)

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

# ── Infrastructure domains that appear in almost every HTML email ───────
_BENIGN_INFRA = frozenset({
    "w3.org", "googleapis.com", "gstatic.com", "googleusercontent.com",
    "google.com", "youtube.com", "schema.org", "outlook.com", "office.com",
    "apple.com", "icloud.com", "cloudfront.net", "akamaihd.net",
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

# Link text that *looks like a domain/URL* (so "Mr. Smith" or "e.g." is ignored)
_DOMAIN_TEXT = re.compile(
    r'^(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,24}(?:[/?#]\S*)?$',
    re.IGNORECASE,
)


# ── Sender authentication ────────────────────────────────────────────────

def _auth_result(auth_header: str, method: str) -> str:
    """Return 'pass' / 'fail' / 'softfail' / 'none' … for spf|dkim|dmarc."""
    m = re.search(rf'\b{method}=(\w+)', auth_header, re.IGNORECASE)
    return m.group(1).lower() if m else "none"


def _auth_domains(auth_header: str, key: str) -> set[str]:
    """Collect registrable domains from e.g. header.i=@x.com / header.d=x.com / smtp.mailfrom=."""
    out = set()
    for m in re.finditer(rf'{re.escape(key)}=@?([^\s;]+)', auth_header, re.IGNORECASE):
        val = m.group(1).strip().lower()
        if "@" in val:
            val = val.split("@", 1)[1]
        out.add(registrable_domain(val))
    return out


def _sender_authentication(payload: EmailScanPayload) -> tuple[bool, list[EmailThreat]]:
    """
    Decide whether the From: domain is cryptographically vouched for.

    Returns (authenticated, threats). Only explicit failures produce threats;
    a missing Authentication-Results header is neutral.
    """
    auth = payload.headers.get("authentication-results", "")
    sender_reg = registrable_domain(payload.sender_email.split("@")[-1]) if "@" in payload.sender_email else ""
    if not auth or not sender_reg:
        return False, []

    dmarc = _auth_result(auth, "dmarc")
    dkim = _auth_result(auth, "dkim")
    spf = _auth_result(auth, "spf")

    dkim_aligned = dkim == "pass" and sender_reg in (
        _auth_domains(auth, "header.i") | _auth_domains(auth, "header.d")
    )
    spf_aligned = spf == "pass" and sender_reg in _auth_domains(auth, "smtp.mailfrom")

    authenticated = dmarc == "pass" or dkim_aligned or spf_aligned

    threats: list[EmailThreat] = []
    if dmarc == "fail" or (not authenticated and spf in ("fail", "softfail") and dkim in ("fail", "none")):
        threats.append(EmailThreat(
            type="auth_failure",
            severity="high",
            detail=f"Sender authentication failed for '{sender_reg}' "
                   f"(SPF={spf}, DKIM={dkim}, DMARC={dmarc})",
            indicator=payload.sender_email,
        ))
    return authenticated, threats


# ── Individual analyzers ─────────────────────────────────────────────────

def _analyze_sender(payload: EmailScanPayload, authenticated: bool) -> list[EmailThreat]:
    """Check sender address for spoofing indicators."""
    threats = []
    sender = payload.sender_email.lower()
    display_name = payload.sender_display_name.lower()
    sender_domain = sender.split("@")[-1] if "@" in sender else ""
    sender_reg = registrable_domain(sender_domain) if sender_domain else ""

    # Lookalike of a known brand (paypa1.com, micros0ft.com, paypal-secure.com).
    # Applies even when authenticated: an attacker can set up DMARC on a squat.
    if sender_domain:
        typo_score, typo_target = _check_typosquat(sender_domain)
        if typo_score > 0.5:
            threats.append(EmailThreat(
                type="sender_spoofing",
                severity="high",
                detail=f"Sender domain '{sender_reg}' imitates '{typo_target}' "
                       f"(similarity: {typo_score:.0%})",
                indicator=sender,
            ))

    # Display name claims a brand the sending domain doesn't belong to.
    # Skipped for authenticated senders: "Amazon <store-news@amazon.in>" etc.
    if not authenticated:
        for brand in BRAND_DOMAINS:
            brand_name = brand.split(".")[0]
            if re.search(rf'\b{re.escape(brand_name)}\b', display_name) and brand_name not in sender_reg:
                threats.append(EmailThreat(
                    type="display_name_mismatch",
                    severity="high",
                    detail=f"Display name mentions '{brand_name}' but the unauthenticated "
                           f"sender domain is '{sender_reg}'",
                    indicator=f"{payload.sender_display_name} <{sender}>",
                ))
                break

    # Reply-To pointing somewhere else — only meaningful for unauthenticated mail
    # (authenticated newsletters/support desks legitimately use other reply hosts).
    if not authenticated:
        _, reply_addr = parseaddr(payload.headers.get("reply-to", ""))
        if reply_addr and "@" in reply_addr and sender_reg:
            reply_reg = registrable_domain(reply_addr.split("@", 1)[1])
            if reply_reg != sender_reg:
                threats.append(EmailThreat(
                    type="reply_to_mismatch",
                    severity="medium",
                    detail=f"Replies go to '{reply_reg}', not the sender's domain '{sender_reg}'",
                    indicator=reply_addr,
                ))

    return threats


def _analyze_content(payload: EmailScanPayload) -> list[EmailThreat]:
    """Check subject/body for social-engineering phrasing."""
    combined_text = f"{payload.subject} {payload.body_text}".lower()
    matched = [p for p in _URGENCY_PHRASES if p in combined_text]

    if len(matched) >= 2:
        return [EmailThreat(
            type="urgency_keywords",
            severity="medium" if len(matched) < 4 else "high",
            detail=f"Pressure phrasing detected: {', '.join(matched[:5])}",
            indicator=payload.subject,
        )]
    return []


def _analyze_links(payload: EmailScanPayload, trusted_regs: set[str]) -> list[EmailThreat]:
    """Detect anchors whose visible text shows one domain but link to another."""
    if not payload.body_html:
        return []

    mismatches: list[str] = []
    for match in _HREF_PATTERN.finditer(payload.body_html):
        href = match.group(1).strip()
        link_text = re.sub(r'<[^>]+>', '', match.group(2)).strip()

        if not link_text or not _DOMAIN_TEXT.match(link_text) or not href.lower().startswith("http"):
            continue

        text_reg = registrable_domain(extract_domain(link_text))
        href_reg = registrable_domain(extract_domain(href))

        if not text_reg or not href_reg or text_reg == href_reg:
            continue
        # Authenticated sender redirecting through its own click-tracker is normal.
        if href_reg in trusted_regs:
            continue
        mismatches.append(f"{link_text} → {href_reg}")

    if not mismatches:
        return []
    return [EmailThreat(
        type="hidden_link_mismatch",
        severity="high",
        detail=f"{len(mismatches)} link(s) display one domain but open another",
        indicator="; ".join(mismatches[:3]),
    )]


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


def _strip_query(url: str) -> str:
    """Drop query string + fragment: tracking params aren't the destination."""
    p = urlparse(url)
    return f"{p.scheme}://{p.netloc}{p.path}"


# ── Pipeline ─────────────────────────────────────────────────────────────

async def analyze_email(payload: EmailScanPayload) -> EmailScanResult:
    """
    Full email phishing analysis pipeline.

    1. Verify sender authentication (SPF / DKIM / DMARC)
    2. Analyze sender for spoofing
    3. Check content for social-engineering phrasing
    4. Detect hidden link mismatches
    5. Check attachments for dangerous extensions
    6. Triage-score embedded third-party URLs
    7. Compute overall verdict
    """
    threats: list[EmailThreat] = []

    authenticated, auth_threats = _sender_authentication(payload)
    threats.extend(auth_threats)

    sender_reg = (
        registrable_domain(payload.sender_email.split("@")[-1])
        if "@" in payload.sender_email else ""
    )
    trusted_regs = set(_BENIGN_INFRA)
    if authenticated and sender_reg:
        trusted_regs.add(sender_reg)

    threats.extend(_analyze_sender(payload, authenticated))
    threats.extend(_analyze_content(payload))
    threats.extend(_analyze_links(payload, trusted_regs))
    threats.extend(_analyze_attachments(payload))

    # Score third-party URLs (deduplicated by destination, query stripped)
    urls = extract_urls_from_email(payload)
    url_scores: dict[str, float] = {}
    max_url_score = 0.0
    malicious: list[tuple[str, float]] = []
    seen_destinations: set[str] = set()

    for url in urls:
        dest = _strip_query(url)
        host_reg = registrable_domain(extract_domain(dest))
        if not host_reg or host_reg in trusted_regs or dest in seen_destinations:
            continue
        seen_destinations.add(dest)
        if len(seen_destinations) > 20:  # Cap to prevent abuse
            break
        try:
            result = await score_url(TriagePayload(
                url=dest,
                client_score=0.0,
                source_ip="email-scanner",
                peer_node="email-scanner",
            ))
            url_scores[url] = result.risk_score
            max_url_score = max(max_url_score, result.risk_score)
            if result.risk_score >= BLOCK_THRESHOLD:
                malicious.append((dest, result.risk_score))
        except Exception as e:
            logger.warning("Failed to score URL %s: %s", url, e)

    if malicious:
        malicious.sort(key=lambda x: -x[1])
        threats.append(EmailThreat(
            type="malicious_url",
            severity="high",
            detail=f"{len(malicious)} embedded link(s) scored as high risk "
                   f"(max {malicious[0][1]:.2f})",
            indicator=malicious[0][0],
        ))

    # ── Verdict ──────────────────────────────────────────────────────────
    high = [t for t in threats if t.severity == "high"]
    high_count = len(high)
    medium_count = sum(1 for t in threats if t.severity == "medium")
    high_types = {t.type for t in high}

    if authenticated:
        # The sender is verified.  Only attacker-controlled lookalike domains,
        # weaponised attachments, or a pile-up of independent signals qualify.
        is_phishing = (
            "sender_spoofing" in high_types
            or "dangerous_attachment" in high_types
            or high_count >= 3
        )
    else:
        is_phishing = high_count >= 2 or (high_count >= 1 and max_url_score >= 0.5)

    if is_phishing:
        verdict = "phishing"
        confidence = min(0.97, 0.55 + high_count * 0.12 + max_url_score * 0.2)
    elif high_count >= 1 or medium_count >= 2 or (not authenticated and max_url_score >= BLOCK_THRESHOLD):
        verdict = "suspicious"
        confidence = min(0.69, 0.3 + high_count * 0.12 + medium_count * 0.08 + max_url_score * 0.15)
    else:
        verdict = "safe"
        confidence = max(0.5, 0.95 - medium_count * 0.1 - max_url_score * 0.3)

    logger.info(
        "Email scan: sender=%s auth=%s subject='%s' verdict=%s threats=%d urls=%d",
        payload.sender_email, "pass" if authenticated else "no",
        payload.subject[:40], verdict, len(threats), len(urls),
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
