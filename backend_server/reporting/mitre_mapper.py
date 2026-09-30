"""
SOAR Backend — MITRE ATT&CK Mapper

Maps observed phishing behaviors to MITRE ATT&CK technique IDs.
Covers the most common techniques seen in phishing campaigns.
"""

from __future__ import annotations

from models import DetonationResult, MITRETechnique


# ── ATT&CK Technique Database ───────────────────────────────────────────
# Curated subset relevant to phishing campaigns

_TECHNIQUES = {
    "spearphishing_link": MITRETechnique(
        technique_id="T1566.002",
        technique_name="Phishing: Spearphishing Link",
        tactic="Initial Access",
        description="Adversary sends a link to a malicious site to harvest credentials or deliver malware.",
    ),
    "credential_harvesting": MITRETechnique(
        technique_id="T1056.003",
        technique_name="Input Capture: Web Portal Capture",
        tactic="Collection",
        description="Fake login portal captures user credentials via form submission.",
    ),
    "drive_by_download": MITRETechnique(
        technique_id="T1189",
        technique_name="Drive-by Compromise",
        tactic="Initial Access",
        description="Visiting the malicious URL triggers an automatic file download.",
    ),
    "brand_impersonation": MITRETechnique(
        technique_id="T1583.001",
        technique_name="Acquire Infrastructure: Domains",
        tactic="Resource Development",
        description="Adversary registers a domain visually similar to a legitimate brand.",
    ),
    "redirect_chain": MITRETechnique(
        technique_id="T1608.005",
        technique_name="Stage Capabilities: Link Target",
        tactic="Resource Development",
        description="URL passes through multiple redirects to evade detection.",
    ),
    "typosquatting": MITRETechnique(
        technique_id="T1583.001",
        technique_name="Acquire Infrastructure: Typosquatting",
        tactic="Resource Development",
        description="Domain registered with a minor spelling variation of a known brand.",
    ),
    "obfuscated_url": MITRETechnique(
        technique_id="T1027",
        technique_name="Obfuscated Files or Information",
        tactic="Defense Evasion",
        description="URL uses encoding, excessive subdomains, or IP addresses to obscure its true destination.",
    ),
    "c2_communication": MITRETechnique(
        technique_id="T1071.001",
        technique_name="Application Layer Protocol: Web Protocols",
        tactic="Command and Control",
        description="Phishing kit communicates with a C2 server over HTTP/HTTPS.",
    ),
}


def map_techniques(
    detonation: DetonationResult | None = None,
    *,
    has_typosquat: bool = False,
    has_ip_in_url: bool = False,
    has_excessive_redirects: bool = False,
) -> list[MITRETechnique]:
    """
    Determine which MITRE ATT&CK techniques apply based on
    observed behaviors from triage and detonation results.

    Always includes T1566.002 (Spearphishing Link) as the
    primary mapping since this is a phishing detection pipeline.
    """
    techniques: list[MITRETechnique] = []

    # Primary: this is always a phishing link scenario
    techniques.append(_TECHNIQUES["spearphishing_link"])

    if detonation:
        # Credential harvesting via fake login form
        if detonation.has_login_form:
            techniques.append(_TECHNIQUES["credential_harvesting"])

        # Drive-by download
        if detonation.downloaded_files:
            techniques.append(_TECHNIQUES["drive_by_download"])

        # Brand impersonation via visual spoofing
        if detonation.brand_match:
            techniques.append(_TECHNIQUES["brand_impersonation"])

        # Redirect chain evasion (>3 redirects is suspicious)
        if len(detonation.redirect_chain) > 3 or has_excessive_redirects:
            techniques.append(_TECHNIQUES["redirect_chain"])

        # C2 communication (external IPs contacted)
        if detonation.external_ips:
            techniques.append(_TECHNIQUES["c2_communication"])

    # Feature-based mappings (from triage layer)
    if has_typosquat:
        techniques.append(_TECHNIQUES["typosquatting"])

    if has_ip_in_url:
        techniques.append(_TECHNIQUES["obfuscated_url"])

    return techniques
