"""
SOAR Backend — Campaign Tracking Database

Uses SQLite via aiosqlite for persistent campaign frequency counts.
Ensures campaign state survives server restarts and --reload cycles,
addressing the ephemeral in-memory state loss tripwire.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

import aiosqlite

from config import DB_PATH, CAMPAIGN_REPORT_THRESHOLD

logger = logging.getLogger("soar.campaign")

_DB_INITIALIZED = False


async def _ensure_db():
    """Create the campaign_tracking table if it doesn't exist."""
    global _DB_INITIALIZED
    if _DB_INITIALIZED:
        return

    async with aiosqlite.connect(str(DB_PATH)) as db:
        await db.execute("""
            CREATE TABLE IF NOT EXISTS campaign_tracking (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                domain TEXT NOT NULL,
                ip TEXT DEFAULT '',
                incident_count INTEGER DEFAULT 1,
                first_seen TEXT NOT NULL,
                last_seen TEXT NOT NULL,
                report_generated INTEGER DEFAULT 0,
                UNIQUE(domain)
            )
        """)
        await db.execute("""
            CREATE TABLE IF NOT EXISTS detonation_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                domain TEXT NOT NULL,
                verdict TEXT DEFAULT 'clean',
                confidence REAL DEFAULT 0.0,
                brand_match TEXT,
                screenshot_path TEXT,
                detonated_at TEXT NOT NULL
            )
        """)
        await db.commit()

    _DB_INITIALIZED = True
    logger.info("Campaign database initialized at %s", DB_PATH)


async def record_incident(domain: str, ip: str = "") -> tuple[int, bool]:
    """
    Record a new phishing incident for a domain.

    Returns (current_count, threshold_crossed) where threshold_crossed
    is True if the count just hit CAMPAIGN_REPORT_THRESHOLD.
    """
    await _ensure_db()
    now = datetime.now(timezone.utc).isoformat()

    async with aiosqlite.connect(str(DB_PATH)) as db:
        # Upsert: increment count or insert new
        await db.execute("""
            INSERT INTO campaign_tracking (domain, ip, incident_count, first_seen, last_seen)
            VALUES (?, ?, 1, ?, ?)
            ON CONFLICT(domain) DO UPDATE SET
                incident_count = incident_count + 1,
                ip = CASE WHEN excluded.ip != '' THEN excluded.ip ELSE campaign_tracking.ip END,
                last_seen = excluded.last_seen
        """, (domain, ip, now, now))
        await db.commit()

        # Fetch current count
        cursor = await db.execute(
            "SELECT incident_count, report_generated FROM campaign_tracking WHERE domain = ?",
            (domain,),
        )
        row = await cursor.fetchone()

        if row:
            count, report_generated = row
            threshold_crossed = (
                count >= CAMPAIGN_REPORT_THRESHOLD
                and not report_generated
            )
            return count, threshold_crossed

    return 1, False


async def mark_report_generated(domain: str):
    """Mark that a CERT-In report has been generated for this campaign."""
    await _ensure_db()

    async with aiosqlite.connect(str(DB_PATH)) as db:
        await db.execute(
            "UPDATE campaign_tracking SET report_generated = 1 WHERE domain = ?",
            (domain,),
        )
        await db.commit()

    logger.info("Campaign '%s' marked as report-generated", domain)


async def log_detonation(
    url: str,
    domain: str,
    verdict: str,
    confidence: float,
    brand_match: str | None = None,
    screenshot_path: str | None = None,
):
    """Log a detonation result for forensic aggregation."""
    await _ensure_db()
    now = datetime.now(timezone.utc).isoformat()

    async with aiosqlite.connect(str(DB_PATH)) as db:
        await db.execute("""
            INSERT INTO detonation_log
            (url, domain, verdict, confidence, brand_match, screenshot_path, detonated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (url, domain, verdict, confidence, brand_match, screenshot_path, now))
        await db.commit()


async def get_campaign_stats(domain: str) -> dict | None:
    """Get campaign statistics for a specific domain."""
    await _ensure_db()

    async with aiosqlite.connect(str(DB_PATH)) as db:
        cursor = await db.execute(
            "SELECT * FROM campaign_tracking WHERE domain = ?",
            (domain,),
        )
        row = await cursor.fetchone()
        if row:
            return {
                "id": row[0],
                "domain": row[1],
                "ip": row[2],
                "incident_count": row[3],
                "first_seen": row[4],
                "last_seen": row[5],
                "report_generated": bool(row[6]),
            }
    return None


async def get_all_campaigns() -> list[dict]:
    """List all tracked campaigns."""
    await _ensure_db()

    async with aiosqlite.connect(str(DB_PATH)) as db:
        cursor = await db.execute(
            "SELECT domain, ip, incident_count, first_seen, last_seen, report_generated "
            "FROM campaign_tracking ORDER BY incident_count DESC"
        )
        rows = await cursor.fetchall()
        return [
            {
                "domain": r[0],
                "ip": r[1],
                "incident_count": r[2],
                "first_seen": r[3],
                "last_seen": r[4],
                "report_generated": bool(r[5]),
            }
            for r in rows
        ]
