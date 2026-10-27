"""
SOAR Backend — Android Auth Route (serverAuthCode exchange)

Single endpoint:
  POST /auth/google  ← receives { "auth_code": "..." } from the Flutter/Android app

Architecture change from browser-flow:
  The old /login + /oauth2callback flow assumed the user opens a browser on
  the *server*. That doesn't work when the client is a mobile app. Instead:
    1. Flutter calls `GoogleSignIn.signIn()` which opens a native Google
       sign-in sheet on the phone and returns a one-time `serverAuthCode`.
    2. The app POSTs that code to this endpoint.
    3. We exchange it for access_token + refresh_token using the Web Client
       ID's client_secret (redirect_uri="" for mobile-originated codes).
    4. We immediately register users.watch() and start receiving real-time
       Pub/Sub pushes for that user's INBOX.

Why gmail.modify?
  With gmail.modify we can auto-label detected phishing emails (e.g.,
  apply a "[PHISHING]" label) — gives the user visual feedback even
  before they open the SOAR app.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build

from config import (
    GMAIL_CLIENT_SECRETS_FILE,
    GMAIL_SCOPES,
    GMAIL_PUBSUB_TOPIC,
)
from gmail.user_store import upsert_user

logger = logging.getLogger("soar.gmail.auth")

router = APIRouter(tags=["Gmail Auth"])


class AndroidAuthPayload(BaseModel):
    """Payload sent by the Flutter/Android client after Google Sign-In."""
    auth_code: str


@router.post("/auth/google")
async def authenticate_android_user(payload: AndroidAuthPayload):
    """
    Exchange a one-time serverAuthCode from the Android/Flutter client
    for a full OAuth2 credential set, then activate Gmail real-time watch().

    The redirect_uri MUST be "" (empty string) when exchanging an auth code
    that originated from an Android client's requestServerAuthCode() call.
    This is a Google-specific requirement — using any other value (even
    "urn:ietf:wg:oauth:2.0:oob") will fail with an "invalid_grant" error.
    """
    try:
        flow = Flow.from_client_secrets_file(
            GMAIL_CLIENT_SECRETS_FILE,
            scopes=GMAIL_SCOPES,
            redirect_uri="",  # Critical: empty string for mobile auth codes
        )
        flow.fetch_token(code=payload.auth_code)
        creds = flow.credentials
    except Exception as e:
        logger.error("Token exchange failed: %s", e)
        raise HTTPException(
            status_code=400,
            detail=f"Token exchange failed: {str(e)}",
        )

    # Build the Gmail API client and register Pub/Sub push notifications
    try:
        service = build("gmail", "v1", credentials=creds)
        profile = service.users().getProfile(userId="me").execute()
        email_address = profile["emailAddress"]

        watch_body = {
            "labelIds": ["INBOX"],
            "topicName": GMAIL_PUBSUB_TOPIC,
            "labelFilterBehavior": "INCLUDE",
        }
        watch_response = service.users().watch(userId="me", body=watch_body).execute()
    except Exception as e:
        logger.error("Gmail watch() setup failed: %s", e)
        raise HTTPException(
            status_code=400,
            detail=f"Gmail watch setup failed: {str(e)}",
        )

    history_id = watch_response["historyId"]
    expiration = int(watch_response["expiration"])

    # Persist user credentials + cursor so the webhook handler can use them
    await upsert_user(
        email=email_address,
        creds_json=creds.to_json(),
        history_id=history_id,
        watch_expiry_ms=expiration,
    )

    logger.info(
        "Gmail watch activated for %s  (historyId=%s, expires=%s)",
        email_address, history_id, expiration,
    )

    return {
        "status": "watch_activated",
        "email": email_address,
        "starting_history_id": history_id,
        "watch_expiration_ms": expiration,
        "message": (
            "Real-time Gmail monitoring is now active. "
            "New emails will be pushed to /webhook/gmail within 1-3 seconds."
        ),
    }


class SeedUserPayload(BaseModel):
    email: str
    creds_json: str
    last_history_id: str = "1"
    watch_expiry_ms: int = 0


@router.post("/auth/seed")
@router.post("/api/v1/auth/seed")
async def seed_user_endpoint(payload: SeedUserPayload):
    """Seed user credentials into database directly."""
    await upsert_user(
        email=payload.email,
        creds_json=payload.creds_json,
        history_id=payload.last_history_id,
        watch_expiry_ms=payload.watch_expiry_ms,
    )
    logger.info("Directly seeded user %s via /auth/seed", payload.email)
    return {
        "status": "seeded",
        "email": payload.email,
        "message": "User credentials successfully registered.",
    }

