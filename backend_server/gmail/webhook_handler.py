"""
SOAR Backend — Gmail Pub/Sub Webhook & Email Processor

POST /webhook/gmail  ← called by Google Cloud Pub/Sub (push subscription)

How it works:
  1. Pub/Sub sends a POST with a base64 envelope containing { emailAddress, historyId }.
  2. We immediately return 200 OK (Pub/Sub retries on anything else).
  3. A background task uses the historyId delta to fetch only the *new*
     messages via users.history.list — no polling, no full-sync.
  4. Each new email is decoded from raw RFC 5322, URLs are extracted,
     and the existing triage + email analysis pipeline is invoked.

Gotcha handling:
  • Duplicate messages:  checked against processed_messages table (at-least-once).
  • Expired historyId:   404 from History API → reset cursor from getProfile().
  • Token refresh:       automatic via google-auth when creds.expired == True.
"""

from __future__ import annotations

import base64
import json
import logging
import email as email_lib
from email import policy
from email.message import EmailMessage
from typing import Optional

from fastapi import APIRouter, Request, BackgroundTasks
from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request as GoogleAuthRequest
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from config import GMAIL_SCOPES
from gmail.user_store import (
    get_user,
    update_creds,
    update_history_id,
    is_message_processed,
    mark_message_processed,
)
from models import EmailScanPayload, TriagePayload
from models.email_analyzer import analyze_email, extract_urls_from_email
from models.feature_extractor import extract_domain
from campaign_db import record_incident

logger = logging.getLogger("soar.gmail.webhook")

router = APIRouter(tags=["Gmail Webhook"])

# ── URL extraction regex (for raw MIME bodies) ───────────────────────────
import re

_URL_PATTERN = re.compile(
    r'https?://[^\s<>"\')\]]+',
    re.IGNORECASE,
)

_HREF_PATTERN = re.compile(
    r'<a[^>]+href=["\']([^"\']+)["\'][^>]*>(.*?)</a>',
    re.IGNORECASE | re.DOTALL,
)


# ── Webhook Endpoint ────────────────────────────────────────────────────

@router.post("/webhook/gmail")
async def gmail_pubsub_webhook(request: Request, background_tasks: BackgroundTasks):
    """
    Receive a push notification from Google Cloud Pub/Sub.

    Envelope format:
    {
      "message": {
        "data": "<base64 of {emailAddress, historyId}>",
        "messageId": "...",
        "publishTime": "..."
      },
      "subscription": "projects/.../subscriptions/..."
    }
    """
    envelope = await request.json()
    message = envelope.get("message", {})

    if "data" not in message:
        logger.warning("Webhook received with no data field")
        return {"status": "no_data"}

    # Decode the base64 inner payload
    decoded = base64.b64decode(message["data"])
    notification = json.loads(decoded.decode("utf-8"))

    email_address = notification.get("emailAddress")
    new_history_id = notification.get("historyId")

    if not email_address or not new_history_id:
        logger.warning("Malformed Gmail notification: %s", notification)
        return {"status": "malformed"}

    logger.info(
        "Pub/Sub push: email=%s historyId=%s", email_address, new_history_id
    )

    # Offload the heavy work so we return 200 OK within milliseconds
    background_tasks.add_task(
        process_mailbox_changes, email_address, new_history_id
    )

    # Pub/Sub considers any non-2xx as a failure and retries
    return {"status": "acknowledged"}


# ── Background Processor ────────────────────────────────────────────────

async def process_mailbox_changes(email_address: str, new_history_id: str):
    """
    Fetch new messages since our last stored historyId, parse each one
    through the full SOAR email analysis pipeline, and broadcast results.
    """
    user = await get_user(email_address)
    if not user:
        logger.warning("Webhook for unknown user %s — ignoring", email_address)
        return

    # Rebuild credentials and auto-refresh if expired
    creds = Credentials.from_authorized_user_info(
        json.loads(user["creds_json"]), GMAIL_SCOPES
    )
    if creds.expired and creds.refresh_token:
        creds.refresh(GoogleAuthRequest())
        await update_creds(email_address, creds.to_json())
        logger.info("Refreshed OAuth token for %s", email_address)

    service = build("gmail", "v1", credentials=creds)
    start_history_id = user["last_history_id"]

    # ── Fetch history delta ──────────────────────────────────────────
    try:
        history_response = (
            service.users()
            .history()
            .list(
                userId="me",
                startHistoryId=start_history_id,
                historyTypes=["messageAdded"],
                labelId="INBOX",
            )
            .execute()
        )
    except HttpError as e:
        if e.resp.status == 404:
            # historyId is too old — reset cursor from profile
            logger.warning(
                "historyId %s expired for %s — resetting cursor",
                start_history_id, email_address,
            )
            profile = service.users().getProfile(userId="me").execute()
            fresh_id = profile["historyId"]
            await update_history_id(email_address, fresh_id)
            return
        logger.error("Gmail History API error for %s: %s", email_address, e)
        return
    except Exception as e:
        logger.error("Unexpected error fetching history for %s: %s", email_address, e)
        return

    # Advance cursor immediately to prevent duplicate processing on retry
    await update_history_id(email_address, new_history_id)

    changes = history_response.get("history", [])
    if not changes:
        logger.debug("No new messages for %s (delta was metadata-only)", email_address)
        return

    # ── Process each new message ─────────────────────────────────────
    for change in changes:
        for msg_added in change.get("messagesAdded", []):
            msg_id = msg_added["message"]["id"]

            # Idempotency gate — Pub/Sub guarantees at-least-once
            if await is_message_processed(msg_id):
                logger.debug("Skipping already-processed msg %s", msg_id)
                continue

            try:
                await _process_single_message(service, email_address, msg_id)
                await mark_message_processed(msg_id, email_address)
            except Exception as e:
                logger.error(
                    "Failed to process msg %s for %s: %s", msg_id, email_address, e
                )


async def _process_single_message(service, email_address: str, msg_id: str):
    """
    Fetch a single Gmail message in raw RFC 5322 format, parse it,
    and run it through the SOAR email analysis pipeline.
    """
    # Fetch raw MIME bytes
    raw_msg = (
        service.users()
        .messages()
        .get(userId="me", id=msg_id, format="raw")
        .execute()
    )
    raw_bytes = base64.urlsafe_b64decode(raw_msg["raw"])

    # Parse RFC 5322 into a structured EmailMessage
    parsed: EmailMessage = email_lib.message_from_bytes(
        raw_bytes, policy=policy.default
    )

    # Extract fields
    sender_full = parsed.get("From", "")
    sender_email = _extract_email_from_header(sender_full)
    sender_display = _extract_display_name(sender_full)
    subject = parsed.get("Subject", "")
    reply_to = parsed.get("Reply-To", "")
    message_id_header = parsed.get("Message-ID", msg_id)

    # Extract body (prefer plain text, fall back to HTML)
    body_text = ""
    body_html = ""
    attachment_names = []

    if parsed.is_multipart():
        for part in parsed.walk():
            content_type = part.get_content_type()
            disposition = str(part.get("Content-Disposition", ""))

            if "attachment" in disposition:
                filename = part.get_filename()
                if filename:
                    attachment_names.append(filename)
                continue

            if content_type == "text/plain" and not body_text:
                payload = part.get_content()
                if isinstance(payload, str):
                    body_text = payload
            elif content_type == "text/html" and not body_html:
                payload = part.get_content()
                if isinstance(payload, str):
                    body_html = payload
    else:
        content_type = parsed.get_content_type()
        payload = parsed.get_content()
        if isinstance(payload, str):
            if content_type == "text/html":
                body_html = payload
            else:
                body_text = payload

    # Build headers dict for the analyzer
    headers = {}
    if reply_to:
        headers["reply-to"] = reply_to

    # Construct the EmailScanPayload and run through existing pipeline
    scan_payload = EmailScanPayload(
        sender_email=sender_email,
        sender_display_name=sender_display,
        subject=subject,
        body_text=body_text,
        body_html=body_html,
        urls=[],  # Will be extracted by analyze_email
        headers=headers,
        attachment_names=attachment_names,
        message_id=message_id_header,
    )

    result = await analyze_email(scan_payload)

    # Log the result
    emoji = {"phishing": "🚨", "suspicious": "⚠️", "safe": "✅"}.get(result.verdict, "❓")
    logger.info(
        "%s [%s] msg=%s from=%s subject='%s' verdict=%s confidence=%.2f threats=%d urls=%d",
        emoji, email_address, msg_id, sender_email,
        subject[:50], result.verdict, result.confidence,
        len(result.threats), result.urls_extracted,
    )

    # Record incidents for malicious domains found in the email
    if result.verdict in ("phishing", "suspicious"):
        for url, score in result.url_scores.items():
            if score > 0.4:
                domain = extract_domain(url)
                if domain:
                    await record_incident(domain, "gmail-webhook")


# ── Helpers ──────────────────────────────────────────────────────────────

def _extract_email_from_header(from_header: str) -> str:
    """Extract 'user@example.com' from 'Display Name <user@example.com>'."""
    match = re.search(r'<([^>]+)>', from_header)
    if match:
        return match.group(1).strip()
    # Bare email with no angle brackets
    return from_header.strip()


def _extract_display_name(from_header: str) -> str:
    """Extract 'Display Name' from 'Display Name <user@example.com>'."""
    match = re.search(r'^(.*?)\s*<', from_header)
    if match:
        name = match.group(1).strip().strip('"').strip("'")
        return name
    return ""
