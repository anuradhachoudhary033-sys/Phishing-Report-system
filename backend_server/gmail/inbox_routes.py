"""
SOAR Backend — Gmail Inbox REST API

Three endpoints that let the Flutter client browse and act on the live inbox:

  GET  /api/v1/inbox                 list of inbox messages + phishing verdicts
  GET  /api/v1/inbox/{msg_id}        full parsed email + threat breakdown
  POST /api/v1/inbox/{msg_id}/trash  move a phishing email to Gmail Trash
  POST /api/v1/send                  compose + send a new email (or reply)

Authentication is credential-less from the Flutter perspective: the client
passes the email address it authenticated with, and we retrieve the stored
OAuth tokens from gmail_users.db (the same DB the webhook handler uses).

MIME parsing and analysis re-uses the exact helpers from webhook_handler.py
so the verdict logic is identical whether an email arrives via push or is
fetched on-demand.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import email as email_lib
from email import policy
from email.message import EmailMessage
from typing import Optional
import re

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field
from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request as GoogleAuthRequest
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from config import GMAIL_SCOPES
from gmail.user_store import get_user, update_creds
from models import EmailScanPayload
from models.email_analyzer import analyze_email
import html

logger = logging.getLogger("soar.gmail.inbox")


def _clean_snippet(body_text: str, body_html: str) -> str:
    candidate = body_text.strip() if body_text else ""
    if not candidate or candidate == "(failed to parse)":
        if body_html:
            text = re.sub(r"(?is)<!DOCTYPE[^>]*>", " ", body_html)
            text = re.sub(r"(?is)<head[\s\S]*?</head>", " ", text)
            text = re.sub(r"(?is)<style[\s\S]*?</style>", " ", text)
            text = re.sub(r"(?is)<script[\s\S]*?</script>", " ", text)
            text = re.sub(r"(?is)<xml[\s\S]*?</xml>", " ", text)
            text = re.sub(r"<[^>]+>", " ", text)
            text = html.unescape(text)
            candidate = re.sub(r"\s+", " ", text).strip()
    if "<" in candidate:
        candidate = re.sub(r"<[^>]+>", " ", candidate)
        candidate = re.sub(r"\s+", " ", candidate).strip()
    return candidate[:200].strip()


router = APIRouter(prefix="/api/v1", tags=["Gmail Inbox"])

# -- Shared MIME helpers (mirrors webhook_handler.py) ---------------------

_URL_PATTERN = re.compile(r'https?://[^\s<>"\')\]]+', re.IGNORECASE)
_HREF_PATTERN = re.compile(
    r'<a[^>]+href=["\']([^"\']+)["\'][^>]*>(.*?)</a>',
    re.IGNORECASE | re.DOTALL,
)


def _extract_email_addr(from_header: str) -> str:
    match = re.search(r'<([^>]+)>', from_header)
    if match:
        return match.group(1).strip()
    return from_header.strip()


def _extract_display_name(from_header: str) -> str:
    match = re.search(r'^(.*?)\s*<', from_header)
    if match:
        return match.group(1).strip().strip('"').strip("'")
    return ""


def _parse_mime(raw_bytes: bytes) -> dict:
    """Parse raw RFC-5322 bytes into a structured dict ready for analysis."""
    parsed: EmailMessage = email_lib.message_from_bytes(
        raw_bytes, policy=policy.default
    )

    sender_full = parsed.get("From", "")
    body_text = ""
    body_html = ""
    attachment_names: list[str] = []

    if parsed.is_multipart():
        for part in parsed.walk():
            ct = part.get_content_type()
            disposition = str(part.get("Content-Disposition", ""))
            if "attachment" in disposition:
                fn = part.get_filename()
                if fn:
                    attachment_names.append(fn)
                continue
            if ct == "text/plain" and not body_text:
                payload = part.get_content()
                if isinstance(payload, str):
                    body_text = payload
            elif ct == "text/html" and not body_html:
                payload = part.get_content()
                if isinstance(payload, str):
                    body_html = payload
    else:
        ct = parsed.get_content_type()
        payload = parsed.get_content()
        if isinstance(payload, str):
            if ct == "text/html":
                body_html = payload
            else:
                body_text = payload

    reply_to = parsed.get("Reply-To", "")
    headers = {}
    if reply_to:
        headers["reply-to"] = reply_to
    # SPF / DKIM / DMARC verdicts stamped by Gmail's MX — the analyzer uses
    # these to tell genuinely authenticated senders from spoofed ones.
    auth_results = parsed.get_all("Authentication-Results") or []
    if auth_results:
        headers["authentication-results"] = " ; ".join(str(h) for h in auth_results)
    to_header = parsed.get("To", "")

    return {
        "sender_email": _extract_email_addr(sender_full),
        "sender_display_name": _extract_display_name(sender_full),
        "subject": parsed.get("Subject", ""),
        "body_text": body_text,
        "body_html": body_html,
        "attachment_names": attachment_names,
        "message_id": parsed.get("Message-ID", ""),
        "date": parsed.get("Date", ""),
        "to": str(to_header),
        "headers": headers,
    }


# -- Credential helper -----------------------------------------------------

async def _get_service(email: str, force_refresh: bool = False):
    """
    Load stored credentials for *email*, refresh if expired, and return
    an authenticated Gmail service client.
    Raises HTTPException(404) if the user is not registered.
    """
    user = await get_user(email)
    if not user:
        raise HTTPException(
            status_code=404,
            detail=(
                f"No registered Gmail user found for {email!r}. "
                "Connect via POST /auth/google first."
            ),
        )

    creds = Credentials.from_authorized_user_info(
        json.loads(user["creds_json"]), GMAIL_SCOPES
    )
    if (creds.expired or force_refresh or not creds.valid) and creds.refresh_token:
        try:
            creds.refresh(GoogleAuthRequest())
            await update_creds(email, creds.to_json())
            logger.info("Refreshed OAuth token for %s", email)
        except Exception as e:
            logger.warning("Token refresh warning for %s: %s", email, e)

    return build("gmail", "v1", credentials=creds)


# In-memory scan cache to avoid re-evaluating already processed emails: msg_id -> dict
_INBOX_SCAN_CACHE: dict[str, dict] = {}

# -- GET /api/v1/inbox ----------------------------------------------------

@router.get("/inbox")
async def list_inbox(
    email: str = Query(..., description="The authenticated Gmail address"),
    max_results: int = Query(25, ge=1, le=100),
    page_token: Optional[str] = Query(None),
    label: str = Query("INBOX", description="Gmail label: INBOX or SENT"),
):
    """
    Return a paginated list of INBOX or SENT messages with phishing verdicts.
    """
    service = await _get_service(email)

    target_label = label.strip().upper()
    list_kwargs: dict = {
        "userId": "me",
        "labelIds": [target_label],
        "maxResults": max_results,
    }
    if page_token:
        list_kwargs["pageToken"] = page_token

    try:
        list_resp = service.users().messages().list(**list_kwargs).execute()
    except HttpError as e:
        if "ratelimitexceeded" in str(e).lower() or "quota" in str(e).lower():
            logger.warning("Gmail list rate limit hit for %s, waiting 2.0s...", email)
            await asyncio.sleep(2.0)
            try:
                list_resp = service.users().messages().list(**list_kwargs).execute()
            except HttpError as e_quota:
                logger.error("Gmail list retry error for %s: %s", email, e_quota)
                raise HTTPException(status_code=429, detail="Gmail rate limit exceeded. Please wait a moment.")
        elif e.resp is not None and e.resp.status == 401:
            logger.info("Got 401 from Gmail list for %s, refreshing token and retrying...", email)
            service = await _get_service(email, force_refresh=True)
            try:
                list_resp = service.users().messages().list(**list_kwargs).execute()
            except HttpError as e2:
                logger.error("Gmail list retry error for %s (%s): %s", email, target_label, e2)
                raise HTTPException(status_code=502, detail=f"Gmail API error: {e2}")
        else:
            logger.error("Gmail list error for %s (%s): %s", email, target_label, e)
            status = e.resp.status if e.resp is not None else 502
            raise HTTPException(status_code=status if 400 <= status < 500 else 502, detail=f"Gmail API error: {e}")

    messages_meta = list_resp.get("messages", [])
    next_page = list_resp.get("nextPageToken")

    needed_meta = [m for m in messages_meta if m["id"] not in _INBOX_SCAN_CACHE]

    if needed_meta:
        raw_map = {}
        def _batch_cb(req_id, resp, ex):
            if ex:
                logger.warning("Batch get failed for %s: %s", req_id, ex)
            else:
                raw_map[req_id] = resp

        batch = service.new_batch_http_request(callback=_batch_cb)
        for m in needed_meta:
            batch.add(
                service.users().messages().get(userId="me", id=m["id"], format="raw"),
                request_id=m["id"]
            )

        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, batch.execute)

        for m in needed_meta:
            msg_id = m["id"]
            raw_msg = raw_map.get(msg_id)
            if not raw_msg:
                continue
            try:
                raw_bytes = base64.urlsafe_b64decode(raw_msg["raw"])
                parsed = _parse_mime(raw_bytes)

                if target_label == "SENT":
                    verdict = "safe"
                    confidence = 1.0
                    threat_count = 0
                    urls_extracted = 0
                else:
                    scan_payload = EmailScanPayload(
                        sender_email=parsed["sender_email"],
                        sender_display_name=parsed["sender_display_name"],
                        subject=parsed["subject"],
                        body_text=parsed["body_text"],
                        body_html=parsed["body_html"],
                        urls=[],
                        headers=parsed["headers"],
                        attachment_names=parsed["attachment_names"],
                        message_id=parsed["message_id"],
                    )
                    result = await analyze_email(scan_payload)
                    verdict = result.verdict
                    confidence = round(result.confidence, 4)
                    threat_count = len(result.threats)
                    urls_extracted = result.urls_extracted

                labels = raw_msg.get("labelIds", [])

                item = {
                    "msg_id": msg_id,
                    "thread_id": raw_msg.get("threadId", ""),
                    "sender_email": parsed["sender_email"],
                    "sender_display": parsed["sender_display_name"],
                    "recipient": parsed.get("to", ""),
                    "subject": parsed["subject"],
                    "date": parsed["date"],
                    "snippet": _clean_snippet(
                        parsed.get("body_text", ""),
                        parsed.get("body_html", "")
                    ),
                    "is_unread": "UNREAD" in labels,
                    "has_attachment": len(parsed["attachment_names"]) > 0,
                    "attachment_count": len(parsed["attachment_names"]),
                    # -- Phishing analysis --
                    "verdict": verdict,
                    "confidence": confidence,
                    "threat_count": threat_count,
                    "urls_extracted": urls_extracted,
                }
                _INBOX_SCAN_CACHE[msg_id] = item
            except Exception as e:
                logger.warning("Skipping msg %s for %s -- parse error: %s", msg_id, email, e)

    results = []
    for meta in messages_meta:
        msg_id = meta["id"]
        if msg_id in _INBOX_SCAN_CACHE:
            results.append(_INBOX_SCAN_CACHE[msg_id])
        else:
            results.append({
                "msg_id": msg_id,
                "thread_id": meta.get("threadId", ""),
                "sender_email": "",
                "sender_display": "",
                "recipient": "",
                "subject": "(failed to parse)",
                "date": "",
                "snippet": "",
                "is_unread": False,
                "has_attachment": False,
                "attachment_count": 0,
                "verdict": "unknown",
                "confidence": 0.0,
                "threat_count": 0,
                "urls_extracted": 0,
            })

    return {
        "email": email,
        "messages": results,
        "next_page_token": next_page,
        "count": len(results),
    }


# -- GET /api/v1/sent ----------------------------------------------------

@router.get("/sent")
async def list_sent(
    email: str = Query(..., description="The authenticated Gmail address"),
    max_results: int = Query(25, ge=1, le=100),
    page_token: Optional[str] = Query(None),
):
    """
    Return paginated list of SENT emails from Gmail.
    """
    return await list_inbox(
        email=email,
        max_results=max_results,
        page_token=page_token,
        label="SENT",
    )


# -- GET /api/v1/inbox/{msg_id} -------------------------------------------

@router.get("/inbox/{msg_id}")
async def get_email_detail(
    msg_id: str,
    email: str = Query(..., description="The authenticated Gmail address"),
):
    """
    Return full parsed email body plus complete threat breakdown.

    Response includes:
      - Full plain-text and HTML body
      - Complete list of extracted URLs with individual risk scores
      - All threat findings (type, severity, detail, indicator)
      - Verdict, confidence, and scanned_at timestamp
    """
    service = await _get_service(email)

    try:
        raw_msg = (
            service.users()
            .messages()
            .get(userId="me", id=msg_id, format="raw")
            .execute()
        )
    except HttpError as e:
        if e.resp is not None and e.resp.status in (401, 403):
            logger.info("Got %s fetching email %s for %s, refreshing token and retrying...", e.resp.status, msg_id, email)
            service = await _get_service(email, force_refresh=True)
            try:
                raw_msg = (
                    service.users()
                    .messages()
                    .get(userId="me", id=msg_id, format="raw")
                    .execute()
                )
            except HttpError as e2:
                if e2.resp is not None and e2.resp.status == 404:
                    raise HTTPException(status_code=404, detail=f"Message {msg_id} not found")
                raise HTTPException(status_code=502, detail=f"Gmail API error: {e2}")
        elif e.resp is not None and e.resp.status == 404:
            raise HTTPException(status_code=404, detail=f"Message {msg_id} not found")
        else:
            raise HTTPException(status_code=502, detail=f"Gmail API error: {e}")

    raw_bytes = base64.urlsafe_b64decode(raw_msg["raw"])
    parsed = _parse_mime(raw_bytes)

    scan_payload = EmailScanPayload(
        sender_email=parsed["sender_email"],
        sender_display_name=parsed["sender_display_name"],
        subject=parsed["subject"],
        body_text=parsed["body_text"],
        body_html=parsed["body_html"],
        urls=[],
        headers=parsed["headers"],
        attachment_names=parsed["attachment_names"],
        message_id=parsed["message_id"],
    )
    result = await analyze_email(scan_payload)

    labels = raw_msg.get("labelIds", [])

    return {
        "msg_id": msg_id,
        "thread_id": raw_msg.get("threadId", ""),
        "sender_email": parsed["sender_email"],
        "sender_display": parsed["sender_display_name"],
        "subject": parsed["subject"],
        "date": parsed["date"],
        "message_id_header": parsed["message_id"],
        "is_unread": "UNREAD" in labels,
        "labels": labels,
        "attachment_names": parsed["attachment_names"],
        # -- Full body --
        "body_text": parsed["body_text"],
        "body_html": parsed["body_html"],
        # -- Analysis --
        "verdict": result.verdict,
        "confidence": round(result.confidence, 4),
        "threats": [t.model_dump() for t in result.threats],
        "url_scores": result.url_scores,
        "urls_extracted": result.urls_extracted,
        "scanned_at": result.scanned_at.isoformat(),
    }


# -- POST /api/v1/inbox/{msg_id}/trash ------------------------------------

@router.post("/inbox/{msg_id}/trash")
async def trash_email(
    msg_id: str,
    email: str = Query(..., description="The authenticated Gmail address"),
):
    """
    Move a Gmail message to Trash (requires gmail.modify scope,
    which is already granted during OAuth).

    The Gmail API trash() moves the message and adds the TRASH label;
    the user can restore it from Gmail Trash within 30 days.
    """
    service = await _get_service(email)

    try:
        service.users().messages().trash(userId="me", id=msg_id).execute()
        logger.info("Moved msg %s to Trash for %s", msg_id, email)
    except HttpError as e:
        if e.resp is not None and e.resp.status in (401, 403):
            logger.info("Got %s trashing email %s for %s, refreshing token and retrying...", e.resp.status, msg_id, email)
            service = await _get_service(email, force_refresh=True)
            try:
                service.users().messages().trash(userId="me", id=msg_id).execute()
                logger.info("Moved msg %s to Trash for %s on retry", msg_id, email)
            except HttpError as e2:
                if e2.resp is not None and e2.resp.status == 404:
                    raise HTTPException(status_code=404, detail=f"Message {msg_id} not found")
                raise HTTPException(status_code=502, detail=f"Gmail trash failed: {e2}")
        elif e.resp is not None and e.resp.status == 404:
            raise HTTPException(status_code=404, detail=f"Message {msg_id} not found")
        else:
            raise HTTPException(status_code=502, detail=f"Gmail trash failed: {e}")

    return {
        "status": "trashed",
        "msg_id": msg_id,
        "email": email,
        "message": "Email moved to Gmail Trash. The user can restore it within 30 days.",
    }


# -- POST /api/v1/send ----------------------------------------------------

_ADDR_PATTERN = re.compile(r"^[^@\s<>,;]+@[^@\s<>,;]+\.[^@\s<>,;]+$")


class AttachmentItem(BaseModel):
    filename: str
    mime_type: str = "application/octet-stream"
    content_base64: str


class SendEmailPayload(BaseModel):
    """Body for POST /api/v1/send."""
    email: str = Field(..., description="Authenticated Gmail address (sender)")
    to: list[str] = Field(..., min_length=1)
    cc: list[str] = Field(default_factory=list)
    bcc: list[str] = Field(default_factory=list)
    subject: str = ""
    body: str = ""
    # Optional reply threading
    thread_id: Optional[str] = None
    in_reply_to: Optional[str] = None  # Message-ID header of the parent
    attachments: list[AttachmentItem] = Field(default_factory=list)


def _clean_header(value: str) -> str:
    """Strip CR/LF so user input can't inject extra headers."""
    return value.replace("\r", " ").replace("\n", " ").strip()


def _validate_addrs(field: str, addrs: list[str]) -> list[str]:
    cleaned = [_clean_header(a) for a in addrs if a and a.strip()]
    bad = [a for a in cleaned if not _ADDR_PATTERN.match(a)]
    if bad:
        raise HTTPException(
            status_code=422,
            detail=f"Invalid {field} address(es): {', '.join(bad)}",
        )
    return cleaned


@router.post("/send")
@router.post("/inbox/send")
async def send_email(payload: SendEmailPayload):
    """
    Compose and send an email from the authenticated user's Gmail account.

    Uses users.messages.send, which is covered by the gmail.modify scope
    already granted at sign-in -- no re-consent needed. The sent message
    automatically lands in the user's Gmail SENT label.
    """
    to = _validate_addrs("to", payload.to)
    cc = _validate_addrs("cc", payload.cc)
    bcc = _validate_addrs("bcc", payload.bcc)
    if not to:
        raise HTTPException(status_code=422, detail="At least one recipient is required")

    service = await _get_service(payload.email)

    msg = EmailMessage(policy=policy.SMTP)
    msg["From"] = _clean_header(payload.email)
    msg["To"] = ", ".join(to)
    if cc:
        msg["Cc"] = ", ".join(cc)
    if bcc:
        # Gmail reads Bcc to route the message, then strips it before delivery.
        msg["Bcc"] = ", ".join(bcc)
    msg["Subject"] = _clean_header(payload.subject)
    if payload.in_reply_to:
        parent = _clean_header(payload.in_reply_to)
        msg["In-Reply-To"] = parent
        msg["References"] = parent
    msg.set_content(payload.body or "")

    for att in payload.attachments:
        if not att.content_base64:
            continue
        try:
            file_bytes = base64.b64decode(att.content_base64)
            maintype, subtype = "application", "octet-stream"
            if "/" in att.mime_type:
                parts = att.mime_type.split("/", 1)
                maintype, subtype = parts[0], parts[1]
            msg.add_attachment(
                file_bytes,
                maintype=maintype,
                subtype=subtype,
                filename=att.filename or "attachment"
            )
        except Exception as e:
            logger.warning("Failed to attach %s: %s", att.filename, e)

    raw = base64.urlsafe_b64encode(msg.as_bytes()).decode("ascii")
    body: dict = {"raw": raw}
    if payload.thread_id and payload.thread_id.strip():
        body["threadId"] = payload.thread_id.strip()

    sent = None
    try:
        sent = service.users().messages().send(userId="me", body=body).execute()
    except HttpError as e:
        # 1. If threadId was rejected as invalid, strip it and retry sending as a clean thread
        if "thread" in str(e).lower() and "threadId" in body:
            logger.warning("Thread ID %s invalid for %s, retrying without threadId...", payload.thread_id, payload.email)
            body.pop("threadId", None)
            try:
                sent = service.users().messages().send(userId="me", body=body).execute()
            except HttpError as e_thread:
                e = e_thread

        # 2. Check if rate limited / quota exceeded -> wait 3.0s and retry once
        err_str = str(e).lower()
        if sent is None and ("ratelimitexceeded" in err_str or "quota" in err_str or (e.resp and e.resp.status == 403 and "usagelimits" in err_str)):
            logger.warning("Gmail rate limit/quota hit for %s, waiting 3.0s and retrying send...", payload.email)
            await asyncio.sleep(3.0)
            try:
                sent = service.users().messages().send(userId="me", body=body).execute()
            except HttpError as e_quota:
                logger.error("Gmail retry after rate limit failed for %s: %s", payload.email, e_quota)
                raise HTTPException(
                    status_code=429,
                    detail="Gmail API rate limit reached (Units per minute per user). Please wait 30 seconds before sending.",
                )

        # 3. If token expired (401), force refresh and retry once
        if sent is None:
            if e.resp is not None and e.resp.status == 401:
                logger.info("Got 401 from Gmail send for %s, refreshing token and retrying...", payload.email)
                service = await _get_service(payload.email, force_refresh=True)
                try:
                    sent = service.users().messages().send(userId="me", body=body).execute()
                except HttpError as e2:
                    logger.error("Gmail retry send failed for %s: %s", payload.email, e2)
                    status = e2.resp.status if e2.resp is not None else 502
                    raise HTTPException(
                        status_code=status if 400 <= status < 500 else 502,
                        detail=f"Gmail send failed: {e2}",
                    )
            else:
                logger.error("Gmail send failed for %s: %s", payload.email, e)
                status = e.resp.status if e.resp is not None else 502
                raise HTTPException(
                    status_code=status if 400 <= status < 500 else 502,
                    detail=f"Gmail send failed: {e}",
                )

    logger.info(
        "Sent msg %s from %s to %d recipient(s)",
        sent.get("id"), payload.email, len(to) + len(cc) + len(bcc),
    )

    return {
        "status": "sent",
        "msg_id": sent.get("id", ""),
        "thread_id": sent.get("threadId", ""),
        "labels": sent.get("labelIds", []),
    }
