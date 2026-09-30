"""
SOAR Backend — PDF Report Builder

Generates human-readable PDF intelligence reports using reportlab.
Embeds screenshots, MITRE ATT&CK tables, and the full forensic
evidence chain.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch, mm
from reportlab.platypus import (
    Image,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

from config import REPORTING_DIR

logger = logging.getLogger("soar.pdf")

_PDF_DIR = REPORTING_DIR / "generated"
_PDF_DIR.mkdir(parents=True, exist_ok=True)

# ── Custom styles ────────────────────────────────────────────────────────
_styles = getSampleStyleSheet()

_TITLE_STYLE = ParagraphStyle(
    "ReportTitle",
    parent=_styles["Title"],
    fontSize=18,
    textColor=colors.HexColor("#0891B2"),
    spaceAfter=6,
)

_HEADING_STYLE = ParagraphStyle(
    "ReportHeading",
    parent=_styles["Heading2"],
    fontSize=13,
    textColor=colors.HexColor("#06B6D4"),
    spaceBefore=16,
    spaceAfter=8,
)

_BODY_STYLE = ParagraphStyle(
    "ReportBody",
    parent=_styles["Normal"],
    fontSize=10,
    leading=14,
    spaceAfter=6,
)

_MONO_STYLE = ParagraphStyle(
    "ReportMono",
    parent=_styles["Code"],
    fontSize=9,
    leading=12,
    textColor=colors.HexColor("#94A3B8"),
)

_TABLE_STYLE = TableStyle([
    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#111827")),
    ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#06B6D4")),
    ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
    ("FONTSIZE", (0, 0), (-1, -1), 9),
    ("ALIGN", (0, 0), (-1, -1), "LEFT"),
    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
    ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#1E2A3A")),
    ("ROWBACKGROUNDS", (0, 1), (-1, -1), [
        colors.HexColor("#0A0E17"),
        colors.HexColor("#111827"),
    ]),
    ("TEXTCOLOR", (0, 1), (-1, -1), colors.HexColor("#E2E8F0")),
    ("LEFTPADDING", (0, 0), (-1, -1), 8),
    ("RIGHTPADDING", (0, 0), (-1, -1), 8),
    ("TOPPADDING", (0, 0), (-1, -1), 6),
    ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
])


def build_pdf(report_data: dict[str, Any]) -> Path:
    """
    Build a styled PDF report from CERT-In JSON data.

    Returns the path to the generated PDF file.
    """
    report_id = report_data["metadata"]["report_id"]
    pdf_path = _PDF_DIR / f"{report_id}.pdf"

    doc = SimpleDocTemplate(
        str(pdf_path),
        pagesize=A4,
        topMargin=20 * mm,
        bottomMargin=20 * mm,
        leftMargin=15 * mm,
        rightMargin=15 * mm,
    )

    elements: list = []

    # ── Title ────────────────────────────────────────────────────────
    elements.append(Paragraph(
        "SOAR Phishing Intelligence Report",
        _TITLE_STYLE,
    ))
    elements.append(Paragraph(
        f"Report ID: {report_id} | "
        f"Ref: {report_data['metadata']['cert_in_ref_no']} | "
        f"Classification: {report_data.get('classification', 'TLP:AMBER')}",
        _MONO_STYLE,
    ))
    elements.append(Spacer(1, 12))

    # ── Incident Summary ─────────────────────────────────────────────
    elements.append(Paragraph("Incident Summary", _HEADING_STYLE))
    summary = report_data.get("incident_summary", {})
    summary_data = [
        ["Field", "Value"],
        ["Type", summary.get("incident_type", "")],
        ["Severity", summary.get("severity", "")],
        ["Campaign Domain", summary.get("campaign_domain", "")],
        ["Campaign IP", summary.get("campaign_ip", "")],
        ["Total Incidents", str(summary.get("total_incidents", 0))],
        ["First Seen", summary.get("first_seen", "")],
        ["Last Seen", summary.get("last_seen", "")],
    ]
    table = Table(summary_data, colWidths=[120, 350])
    table.setStyle(_TABLE_STYLE)
    elements.append(table)
    elements.append(Spacer(1, 8))

    if summary.get("description"):
        elements.append(Paragraph(summary["description"], _BODY_STYLE))

    # ── IOCs ─────────────────────────────────────────────────────────
    iocs = report_data.get("indicators_of_compromise", {})
    elements.append(Paragraph("Indicators of Compromise", _HEADING_STYLE))

    if iocs.get("urls"):
        elements.append(Paragraph("<b>Malicious URLs:</b>", _BODY_STYLE))
        for url in iocs["urls"][:10]:
            elements.append(Paragraph(f"• {url}", _MONO_STYLE))

    if iocs.get("ip_addresses"):
        elements.append(Spacer(1, 6))
        elements.append(Paragraph("<b>C2 / External IPs:</b>", _BODY_STYLE))
        for ip in iocs["ip_addresses"][:10]:
            elements.append(Paragraph(f"• {ip}", _MONO_STYLE))

    if iocs.get("file_hashes"):
        elements.append(Spacer(1, 6))
        elements.append(Paragraph("<b>Payload Hashes:</b>", _BODY_STYLE))
        hash_data = [["Filename", "SHA-256", "Size"]]
        for h in iocs["file_hashes"][:10]:
            hash_data.append([
                h.get("filename", ""),
                h.get("sha256", "")[:32] + "…",
                f"{h.get('size_bytes', 0):,} B",
            ])
        hash_table = Table(hash_data, colWidths=[120, 280, 70])
        hash_table.setStyle(_TABLE_STYLE)
        elements.append(hash_table)

    # ── MITRE ATT&CK Mapping ────────────────────────────────────────
    mitre = report_data.get("mitre_attack_mapping", [])
    if mitre:
        elements.append(Paragraph("MITRE ATT&CK Mapping", _HEADING_STYLE))
        mitre_data = [["Technique ID", "Name", "Tactic"]]
        for t in mitre:
            mitre_data.append([
                t.get("technique_id", ""),
                t.get("technique_name", ""),
                t.get("tactic", ""),
            ])
        mitre_table = Table(mitre_data, colWidths=[90, 250, 130])
        mitre_table.setStyle(_TABLE_STYLE)
        elements.append(mitre_table)

    # ── Evidence Chain ───────────────────────────────────────────────
    evidence = report_data.get("evidence_chain", [])
    if evidence:
        elements.append(Paragraph("Evidence Chain", _HEADING_STYLE))
        for i, ev in enumerate(evidence[:5]):  # Cap at 5 for PDF readability
            elements.append(Paragraph(
                f"<b>Detonation #{i + 1}</b>: {ev.get('url', '')}",
                _BODY_STYLE,
            ))
            ev_data = [
                ["Final URL", ev.get("final_url", "")],
                ["Verdict", ev.get("verdict", "")],
                ["Confidence", f"{ev.get('confidence', 0) * 100:.1f}%"],
                ["Login Form", "Yes" if ev.get("has_login_form") else "No"],
                ["Redirects", str(ev.get("redirect_count", 0))],
                ["Brand Spoof", ev.get("brand_impersonation") or "None"],
            ]
            ev_table = Table(ev_data, colWidths=[100, 370])
            ev_table.setStyle(_TABLE_STYLE)
            elements.append(ev_table)
            elements.append(Spacer(1, 8))

    # ── Recommended Actions ──────────────────────────────────────────
    actions = report_data.get("recommended_actions", [])
    if actions:
        elements.append(Paragraph("Recommended Actions", _HEADING_STYLE))
        for action in actions:
            elements.append(Paragraph(f"• {action}", _BODY_STYLE))

    # ── Footer ───────────────────────────────────────────────────────
    elements.append(Spacer(1, 20))
    elements.append(Paragraph(
        f"Generated by SOAR Phishing Pipeline v1.0 | "
        f"{datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}",
        _MONO_STYLE,
    ))

    # Build the PDF
    doc.build(elements)
    logger.info("PDF report built: %s", pdf_path)

    return pdf_path
