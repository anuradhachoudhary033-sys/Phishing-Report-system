"""
SOAR Backend — Gmail Watch Renewal Service

Gmail's users.watch() expires every 7 days. This module provides
a renewal function that loops through all registered users and
re-registers the watch. Designed to be called by a scheduler
(APScheduler, Celery beat, or a simple asyncio background task)
once every 24 hours.
"""

from __future__ import annotations

import json
import logging

from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request as GoogleAuthRequest
from googleapiclient.discovery import build

from config import GMAIL_SCOPES, GMAIL_PUBSUB_TOPIC
from gmail.user_store import get_all_users, upsert_user, update_creds

logger = logging.getLogger("soar.gmail.watch")


async def renew_all_watches() -> dict:
    """
    Loop through every registered Gmail user and call users.watch()
    to renew their Pub/Sub push subscription.

    Returns a summary dict: { renewed: int, failed: int, errors: [...] }
    """
    users = await get_all_users()
    renewed = 0
    failed = 0
    errors = []

    for user in users:
        email = user["email"]
        try:
            creds = Credentials.from_authorized_user_info(
                json.loads(user["creds_json"]), GMAIL_SCOPES
            )
            if creds.expired and creds.refresh_token:
                creds.refresh(GoogleAuthRequest())
                await update_creds(email, creds.to_json())

            service = build("gmail", "v1", credentials=creds)
            watch_body = {
                "labelIds": ["INBOX"],
                "topicName": GMAIL_PUBSUB_TOPIC,
                "labelFilterBehavior": "INCLUDE",
            }
            resp = service.users().watch(userId="me", body=watch_body).execute()

            # Update the stored expiration
            await upsert_user(
                email=email,
                creds_json=creds.to_json(),
                history_id=resp["historyId"],
                watch_expiry_ms=int(resp["expiration"]),
            )
            renewed += 1
            logger.info("Renewed watch for %s (expires %s)", email, resp["expiration"])

        except Exception as e:
            failed += 1
            errors.append({"email": email, "error": str(e)})
            logger.error("Failed to renew watch for %s: %s", email, e)

    summary = {"renewed": renewed, "failed": failed, "errors": errors}
    logger.info("Watch renewal complete: %s", summary)
    return summary
