"""
SOAR Backend — Gmail User Store

Manages per-user OAuth credentials, historyId cursors, and watch()
lease expirations. Uses SQLite (via aiosqlite) for persistence so
that restarts don't lose all authenticated users.

Production note: Swap this with PostgreSQL/Redis via a thin adapter;
the interface stays the same.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path

import aiosqlite

from config import BASE_DIR

logger = logging.getLogger("soar.gmail.store")

_DB_PATH = BASE_DIR / "gmail_users.db"

# ── Schema ───────────────────────────────────────────────────────────────

_INIT_SQL = """
CREATE TABLE IF NOT EXISTS gmail_users (
    email           TEXT PRIMARY KEY,
    creds_json      TEXT NOT NULL,
    last_history_id TEXT NOT NULL,
    watch_expiry_ms INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS processed_messages (
    msg_id  TEXT PRIMARY KEY,
    email   TEXT NOT NULL,
    ts      REAL NOT NULL DEFAULT (julianday('now'))
);

CREATE INDEX IF NOT EXISTS idx_pm_email ON processed_messages(email);
"""


async def init_gmail_db() -> None:
    """Create tables on first run."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        await db.executescript(_INIT_SQL)
        await db.commit()
    logger.info("Gmail user store ready  (%s)", _DB_PATH)

    # Auto-seed credentials from environment variable if present (for cloud deployments like Render)
    seed_env = os.getenv("GMAIL_USER_SEED_JSON")
    if seed_env:
        try:
            data = json.loads(seed_env)
            users = data if isinstance(data, list) else [data]
            for u in users:
                await upsert_user(
                    email=u["email"],
                    creds_json=u["creds_json"] if isinstance(u["creds_json"], str) else json.dumps(u["creds_json"]),
                    history_id=str(u.get("last_history_id", "1")),
                    watch_expiry_ms=int(u.get("watch_expiry_ms", 0)),
                )
            logger.info("Auto-seeded %d user(s) into Gmail store from environment", len(users))
        except Exception as e:
            logger.error("Failed to seed users from GMAIL_USER_SEED_JSON: %s", e)


# ── CRUD ─────────────────────────────────────────────────────────────────

async def upsert_user(
    email: str,
    creds_json: str,
    history_id: str,
    watch_expiry_ms: int,
) -> None:
    """Insert or update a user's credentials and cursor."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        await db.execute(
            """
            INSERT INTO gmail_users (email, creds_json, last_history_id, watch_expiry_ms)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(email) DO UPDATE SET
                creds_json      = excluded.creds_json,
                last_history_id = excluded.last_history_id,
                watch_expiry_ms = excluded.watch_expiry_ms
            """,
            (email, creds_json, history_id, watch_expiry_ms),
        )
        await db.commit()


async def get_user(email: str) -> dict | None:
    """Return user record dict or None."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        db.row_factory = aiosqlite.Row
        clean_email = email.strip() if email else ""
        cursor = await db.execute(
            "SELECT * FROM gmail_users WHERE LOWER(TRIM(email)) = LOWER(TRIM(?))", (clean_email,)
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return {
            "email": row["email"],
            "creds_json": row["creds_json"],
            "last_history_id": row["last_history_id"],
            "watch_expiry_ms": row["watch_expiry_ms"],
        }


async def update_history_id(email: str, new_history_id: str) -> None:
    """Advance the history cursor after processing a webhook."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        await db.execute(
            "UPDATE gmail_users SET last_history_id = ? WHERE email = ?",
            (new_history_id, email),
        )
        await db.commit()


async def update_creds(email: str, creds_json: str) -> None:
    """Persist refreshed credentials."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        await db.execute(
            "UPDATE gmail_users SET creds_json = ? WHERE email = ?",
            (creds_json, email),
        )
        await db.commit()


async def get_all_users() -> list[dict]:
    """Return all registered users (for watch renewal cron)."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute("SELECT * FROM gmail_users")
        rows = await cursor.fetchall()
        return [
            {
                "email": r["email"],
                "creds_json": r["creds_json"],
                "last_history_id": r["last_history_id"],
                "watch_expiry_ms": r["watch_expiry_ms"],
            }
            for r in rows
        ]


# ── Idempotency: Deduplicate message IDs ─────────────────────────────────

async def is_message_processed(msg_id: str) -> bool:
    """Check if we already handled this Gmail message ID."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        cursor = await db.execute(
            "SELECT 1 FROM processed_messages WHERE msg_id = ?", (msg_id,)
        )
        return (await cursor.fetchone()) is not None


async def mark_message_processed(msg_id: str, email: str) -> None:
    """Record a message ID so we never process it twice."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        await db.execute(
            "INSERT OR IGNORE INTO processed_messages (msg_id, email) VALUES (?, ?)",
            (msg_id, email),
        )
        await db.commit()


async def prune_old_messages(keep_days: int = 7) -> int:
    """Remove entries older than `keep_days` to avoid unbounded growth."""
    async with aiosqlite.connect(str(_DB_PATH)) as db:
        cursor = await db.execute(
            "DELETE FROM processed_messages WHERE ts < julianday('now') - ?",
            (keep_days,),
        )
        await db.commit()
        return cursor.rowcount
