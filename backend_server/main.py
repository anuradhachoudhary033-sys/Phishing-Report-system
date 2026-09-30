"""
SOAR Phishing Orchestrator Engine — FastAPI Application

Full API server with:
- Layer 1: ML-powered triage scoring (/api/v1/triage)
- Layer 2: Async sandbox detonation (/api/v1/detonate)
- Layer 3: CERT-In report generation and PDF download (/api/v1/reports)
- WebSocket: Live event streaming to Flutter clients (/ws/triage)
- Campaign tracking: SQLite-persisted incident aggregation
"""

from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from fastapi import BackgroundTasks, FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from config import CAMPAIGN_REPORT_THRESHOLD
from models import (
    DetonationRequest,
    EmailScanPayload,
    IntelReport,
    PhishingEvent,
    TriagePayload,
)
from models.email_analyzer import analyze_email, extract_urls_from_email
from models.feature_extractor import extract_domain
from models.triage_scorer import score_url
from sandbox.docker_runner import run_sandbox
from reporting import create_intel_report, generate_certin_json, save_report
from reporting.mitre_mapper import map_techniques
from reporting.pdf_builder import build_pdf
from campaign_db import (
    get_all_campaigns,
    get_campaign_stats,
    log_detonation,
    mark_report_generated,
    record_incident,
)

# ── Logging ──────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s │ %(name)-18s │ %(levelname)-7s │ %(message)s",
)
logger = logging.getLogger("soar.api")


# ── WebSocket Connection Manager ────────────────────────────────────────

class ConnectionManager:
    """Manages active WebSocket connections for live event broadcasting."""

    def __init__(self):
        self._connections: list[WebSocket] = []

    async def connect(self, ws: WebSocket):
        await ws.accept()
        self._connections.append(ws)
        logger.info("Client connected (%d total)", len(self._connections))

    def disconnect(self, ws: WebSocket):
        self._connections.remove(ws)
        logger.info("Client disconnected (%d remaining)", len(self._connections))

    async def broadcast(self, event: PhishingEvent):
        """Broadcast a phishing event to all connected clients."""
        data = event.model_dump_json()
        dead: list[WebSocket] = []
        for ws in self._connections:
            try:
                await ws.send_text(data)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self._connections.remove(ws)

    @property
    def active_count(self) -> int:
        return len(self._connections)


manager = ConnectionManager()


# ── App Lifecycle ────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup / shutdown lifecycle for the SOAR API."""
    logger.info("SOAR Phishing Orchestrator Engine starting up")
    # Ensure campaign DB is initialized
    from campaign_db import _ensure_db
    await _ensure_db()
    yield
    logger.info("SOAR engine shutting down")


app = FastAPI(
    title="SOAR Phishing Orchestrator Engine",
    version="1.0.0",
    description="Enterprise-grade phishing detection SOAR pipeline",
    lifespan=lifespan,
)

# ── CORS (allow prototype UI to reach API) ────────────
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:3000",
        "http://127.0.0.1:3000",
        "http://localhost:5000",
        "http://127.0.0.1:5000",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Layer 1: Triage API ─────────────────────────────────────────────────

@app.post("/api/v1/triage")
async def process_triage(payload: TriagePayload, background_tasks: BackgroundTasks):
    """
    Score a URL using the ML triage pipeline.

    Returns the risk score, action (BLOCK_INSTANT / QUEUE_SANDBOX / ALLOW),
    and extracted features. If the action is QUEUE_SANDBOX, the detonation
    is automatically queued as a background task.
    """
    result = await score_url(payload)

    # Record incident in campaign tracker
    domain = extract_domain(payload.url)
    if domain:
        background_tasks.add_task(
            record_incident, domain, payload.source_ip
        )

    # Broadcast to connected WebSocket clients
    event = PhishingEvent(
        id=f"evt-{uuid4().hex[:12]}",
        url=payload.url,
        risk_score=result.risk_score,
        is_phishing=result.is_phishing,
        bloom_match_count=result.bloom_match_count,
        source_ip=payload.source_ip,
        peer_node=payload.peer_node,
    )
    background_tasks.add_task(manager.broadcast, event)

    # Auto-queue sandbox if in the suspicious range
    if result.action == "QUEUE_SANDBOX":
        det_request = DetonationRequest(
            url=payload.url,
            triage_score=result.risk_score,
            source_ip=payload.source_ip,
            peer_node=payload.peer_node,
        )
        background_tasks.add_task(_run_detonation_pipeline, det_request)

    return result.model_dump()


# ── Layer 2: Sandbox API ────────────────────────────────────────────────

@app.post("/api/v1/detonate")
async def trigger_detonation(request: DetonationRequest, background_tasks: BackgroundTasks):
    """
    Manually trigger sandbox detonation for a URL.

    The detonation runs as a background task to avoid blocking.
    """
    background_tasks.add_task(_run_detonation_pipeline, request)
    return {"status": "detonation_queued", "target": request.url}


async def _run_detonation_pipeline(request: DetonationRequest):
    """
    Full detonation pipeline:
    1. Run sandbox (async Playwright)
    2. Log result to campaign DB
    3. Check campaign threshold for auto-report generation
    4. Broadcast IOC update to clients
    """
    try:
        result = await run_sandbox(request)
        domain = extract_domain(request.url)

        # Log detonation
        await log_detonation(
            url=request.url,
            domain=domain,
            verdict=result.verdict,
            confidence=result.confidence_score,
            brand_match=result.brand_match,
            screenshot_path=result.screenshot_path,
        )

        # Record incident and check threshold
        if domain:
            count, threshold_crossed = await record_incident(domain)

            if threshold_crossed:
                logger.info(
                    "Campaign threshold crossed for '%s' (%d incidents) — generating CERT-In report",
                    domain, count,
                )
                # Auto-generate report
                techniques = map_techniques(
                    detonation=result,
                    has_typosquat=False,  # Could be enriched from triage features
                )
                report = create_intel_report(
                    campaign_domain=domain,
                    campaign_ip=result.external_ips[0] if result.external_ips else "",
                    incident_count=count,
                    detonation_results=[result],
                    mitre_techniques=techniques,
                )
                # Build PDF
                report_data = generate_certin_json(
                    campaign_domain=domain,
                    campaign_ip=result.external_ips[0] if result.external_ips else "",
                    incident_count=count,
                    detonation_results=[result],
                    mitre_techniques=techniques,
                )
                build_pdf(report_data)
                await mark_report_generated(domain)
                logger.info("Auto-generated report %s for campaign '%s'", report.report_id, domain)

        # Broadcast as phishing event
        if result.verdict in ("malicious", "suspicious"):
            event = PhishingEvent(
                id=f"det-{uuid4().hex[:12]}",
                url=request.url,
                risk_score=result.confidence_score,
                is_phishing=True,
                source_ip=request.source_ip,
                peer_node=request.peer_node,
            )
            await manager.broadcast(event)

    except Exception as e:
        logger.error("Detonation pipeline failed for %s: %s", request.url, e)


# ── Layer 3: Reporting API ──────────────────────────────────────────────

@app.get("/api/v1/reports")
async def list_reports():
    """List all generated intelligence reports."""
    reports_dir = Path("reporting/generated")
    if not reports_dir.exists():
        return []

    reports = []
    for json_file in sorted(reports_dir.glob("*.json"), reverse=True):
        try:
            data = json.loads(json_file.read_text())
            reports.append({
                "report_id": data["metadata"]["report_id"],
                "cert_in_ref_no": data["metadata"]["cert_in_ref_no"],
                "status": "generated",
                "generated_at": data["metadata"]["generated_at"],
                "download_url": f"/api/v1/reports/{data['metadata']['report_id']}/pdf",
            })
        except Exception:
            continue

    return reports


@app.get("/api/v1/reports/{report_id}/pdf")
async def download_report_pdf(report_id: str):
    """Download a report as PDF."""
    pdf_path = Path(f"reporting/generated/{report_id}.pdf")
    if not pdf_path.exists():
        # Try to build from JSON
        json_path = Path(f"reporting/generated/{report_id}.json")
        if json_path.exists():
            report_data = json.loads(json_path.read_text())
            pdf_path = build_pdf(report_data)
        else:
            return JSONResponse(
                {"error": "Report not found"},
                status_code=404,
            )

    return FileResponse(
        str(pdf_path),
        media_type="application/pdf",
        filename=f"{report_id}.pdf",
    )


@app.post("/api/v1/reports/{report_id}/submit")
async def submit_report(report_id: str):
    """
    Submit an intelligence package to CERT-In.
    (Mocked for initial implementation — would integrate with
    official CERT-In API or secure email relay in production.)
    """
    json_path = Path(f"reporting/generated/{report_id}.json")
    if not json_path.exists():
        return JSONResponse(
            {"error": "Report not found"},
            status_code=404,
        )

    # In production, this would POST to CERT-In's API
    logger.info("Intelligence package %s submitted to CERT-In (mocked)", report_id)

    return {
        "status": "submitted",
        "report_id": report_id,
        "message": "Intelligence package submitted to CERT-In portal",
    }


@app.post("/api/v1/reports/generate")
async def manually_generate_report(domain: str, ip: str = ""):
    """
    Manually trigger a CERT-In report for a specific domain.
    """
    stats = await get_campaign_stats(domain)
    incident_count = stats["incident_count"] if stats else 1

    techniques = map_techniques(has_typosquat=False)
    report_data = generate_certin_json(
        campaign_domain=domain,
        campaign_ip=ip,
        incident_count=incident_count,
        detonation_results=[],
        mitre_techniques=techniques,
    )
    save_report(report_data)
    pdf_path = build_pdf(report_data)

    return {
        "status": "generated",
        "report_id": report_data["metadata"]["report_id"],
        "cert_in_ref_no": report_data["metadata"]["cert_in_ref_no"],
        "pdf_path": str(pdf_path),
    }


# ── Email Scanning API ──────────────────────────────────────────────────

@app.post("/api/v1/scan/email")
async def scan_email(payload: EmailScanPayload, background_tasks: BackgroundTasks):
    """
    Scan an email for phishing indicators.

    Analyzes sender spoofing, urgency keywords, hidden link mismatches,
    dangerous attachments, and triages all embedded URLs through the
    ML scoring pipeline.
    """
    result = await analyze_email(payload)

    # If phishing detected, broadcast alert and record incidents
    if result.verdict in ("phishing", "suspicious"):
        # Record incidents for each malicious URL's domain
        for url, score in result.url_scores.items():
            if score > 0.4:
                domain = extract_domain(url)
                if domain:
                    background_tasks.add_task(record_incident, domain, "email-scanner")

        # Broadcast as a phishing event
        event = PhishingEvent(
            id=f"email-{uuid4().hex[:12]}",
            url=f"email://{payload.sender_email}",
            risk_score=result.confidence,
            is_phishing=result.verdict == "phishing",
            source_ip="email-scanner",
            peer_node="gmail-oauth",
        )
        background_tasks.add_task(manager.broadcast, event)

    return result.model_dump()


# ── Campaign Tracking API ───────────────────────────────────────────────

@app.get("/api/v1/campaigns")
async def list_campaigns():
    """List all tracked phishing campaigns with incident counts."""
    return await get_all_campaigns()


@app.get("/api/v1/campaigns/{domain}")
async def get_campaign(domain: str):
    """Get details for a specific campaign domain."""
    stats = await get_campaign_stats(domain)
    if stats:
        return stats
    return JSONResponse({"error": "Campaign not found"}, status_code=404)


# ── WebSocket: Live Triage Feed ─────────────────────────────────────────

@app.websocket("/ws/triage")
async def triage_websocket(ws: WebSocket):
    """
    WebSocket endpoint for streaming live phishing events
    to connected Flutter mobile clients.

    Clients can also send triage payloads via the WebSocket
    for inline scoring.
    """
    await manager.connect(ws)
    try:
        while True:
            # Accept incoming messages (triage requests from clients)
            data = await ws.receive_text()
            try:
                payload = TriagePayload.model_validate_json(data)
                result = await score_url(payload)

                # Record incident
                domain = extract_domain(payload.url)
                if domain:
                    await record_incident(domain, payload.source_ip)

                # Build event and broadcast
                event = PhishingEvent(
                    id=f"ws-{uuid4().hex[:12]}",
                    url=payload.url,
                    risk_score=result.risk_score,
                    is_phishing=result.is_phishing,
                    bloom_match_count=result.bloom_match_count,
                    source_ip=payload.source_ip,
                    peer_node=payload.peer_node,
                )
                await manager.broadcast(event)

            except Exception as e:
                await ws.send_json({"error": str(e)})

    except WebSocketDisconnect:
        manager.disconnect(ws)


# ── Health Check ─────────────────────────────────────────────────────────

@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "active_connections": manager.active_count,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
