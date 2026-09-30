"""
SOAR Backend — CERT-In Compliant Report Generator

Generates structured incident reports matching the JSON schema
expected by Indian Computer Emergency Response Team (CERT-In)
and related national cyber portals.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from config import REPORTING_DIR
from models import DetonationResult, IntelReport, MITRETechnique

logger = logging.getLogger("soar.reporting")

# Ensure output directory exists
_REPORTS_DIR = REPORTING_DIR / "generated"
_REPORTS_DIR.mkdir(parents=True, exist_ok=True)


def _generate_ref_no() -> str:
    """Generate a CERT-In style reference number."""
    now = datetime.now(timezone.utc)
    seq = int(now.timestamp()) % 100000
    return f"CERT-IN/{now.year}/PHISH/{seq:05d}"


def _generate_report_id() -> str:
    """Generate a unique report ID."""
    now = datetime.now(timezone.utc)
    return f"RPT-{now.year}-{int(now.timestamp()) % 10000:04d}"


def generate_certin_json(
    campaign_domain: str,
    campaign_ip: str,
    incident_count: int,
    detonation_results: list[DetonationResult],
    mitre_techniques: list[MITRETechnique],
) -> dict[str, Any]:
    """
    Build a CERT-In compliant JSON incident report.

    The schema follows the standard incident reporting format:
    - Incident metadata (type, severity, timestamps)
    - Affected infrastructure details
    - Technical indicators (IOCs)
    - MITRE ATT&CK mappings
    - Evidence chain (detonation results)
    """
    now = datetime.now(timezone.utc)

    # Aggregate IOCs from all detonation results
    all_ips = set()
    all_hashes = []
    all_urls = set()

    for det in detonation_results:
        all_urls.add(det.url)
        if det.final_url:
            all_urls.add(det.final_url)
        all_ips.update(det.external_ips)
        for f in det.downloaded_files:
            all_hashes.append({
                "filename": f.get("filename", "unknown"),
                "sha256": f.get("sha256", ""),
                "size_bytes": f.get("size_bytes", 0),
            })

    report = {
        "schema_version": "1.0",
        "report_type": "PHISHING_CAMPAIGN",
        "classification": "TLP:AMBER",

        "metadata": {
            "report_id": _generate_report_id(),
            "cert_in_ref_no": _generate_ref_no(),
            "generated_at": now.isoformat(),
            "reporting_entity": "SOAR Phishing Pipeline v1.0",
            "priority": "HIGH" if incident_count > 50 else "MEDIUM",
        },

        "incident_summary": {
            "incident_type": "Phishing Campaign",
            "severity": "Critical" if incident_count > 100 else "High",
            "campaign_domain": campaign_domain,
            "campaign_ip": campaign_ip,
            "total_incidents": incident_count,
            "first_seen": detonation_results[0].detonated_at.isoformat() if detonation_results else now.isoformat(),
            "last_seen": now.isoformat(),
            "description": (
                f"Automated phishing campaign detected targeting users via "
                f"domain '{campaign_domain}'. {incident_count} incidents recorded "
                f"across the sensor network. Dynamic analysis confirms "
                f"{'credential harvesting' if any(d.has_login_form for d in detonation_results) else 'malicious activity'}."
            ),
        },

        "indicators_of_compromise": {
            "urls": sorted(all_urls),
            "ip_addresses": sorted(all_ips),
            "file_hashes": all_hashes,
        },

        "mitre_attack_mapping": [
            {
                "technique_id": t.technique_id,
                "technique_name": t.technique_name,
                "tactic": t.tactic,
                "description": t.description,
            }
            for t in mitre_techniques
        ],

        "evidence_chain": [
            {
                "url": det.url,
                "final_url": det.final_url,
                "verdict": det.verdict,
                "confidence": det.confidence_score,
                "has_login_form": det.has_login_form,
                "redirect_count": len(det.redirect_chain),
                "brand_impersonation": det.brand_match,
                "downloaded_files": det.downloaded_files,
                "external_ips": det.external_ips,
                "detonated_at": det.detonated_at.isoformat(),
            }
            for det in detonation_results
        ],

        "recommended_actions": [
            f"Block domain '{campaign_domain}' at DNS/proxy level",
            f"Block IP address '{campaign_ip}' at firewall",
            "Update phishing filters with extracted IOCs",
            "Notify affected users to reset credentials",
            "Preserve evidence for law enforcement referral",
        ],
    }

    return report


def save_report(report_data: dict[str, Any]) -> tuple[str, Path]:
    """
    Save the CERT-In JSON report to disk.

    Returns (report_id, file_path).
    """
    report_id = report_data["metadata"]["report_id"]
    file_path = _REPORTS_DIR / f"{report_id}.json"

    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(report_data, f, indent=2, default=str)

    logger.info("Report %s saved to %s", report_id, file_path)
    return report_id, file_path


def create_intel_report(
    campaign_domain: str,
    campaign_ip: str,
    incident_count: int,
    detonation_results: list[DetonationResult],
    mitre_techniques: list[MITRETechnique],
) -> IntelReport:
    """
    Full pipeline: generate CERT-In JSON, save to disk, return metadata.
    """
    report_data = generate_certin_json(
        campaign_domain=campaign_domain,
        campaign_ip=campaign_ip,
        incident_count=incident_count,
        detonation_results=detonation_results,
        mitre_techniques=mitre_techniques,
    )

    report_id, file_path = save_report(report_data)

    return IntelReport(
        report_id=report_id,
        cert_in_ref_no=report_data["metadata"]["cert_in_ref_no"],
        status="generated",
        campaign_domain=campaign_domain,
        campaign_ip=campaign_ip,
        incident_count=incident_count,
        mitre_techniques=mitre_techniques,
        detonation_results=detonation_results,
        download_url=f"/api/v1/reports/{report_id}/pdf",
    )
