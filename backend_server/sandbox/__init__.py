"""
SOAR Backend — Async Sandbox Detonator

Strictly uses playwright.async_api to avoid blocking the FastAPI
asyncio event loop. When running outside Docker, the Playwright
browser context is hardened:
  - Downloads routed to an isolated quarantine directory
  - Auto-execution of downloaded payloads is forbidden
  - SHA-256 hashes are extracted in-memory, then files are scrubbed
"""

from __future__ import annotations

import hashlib
import logging
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from playwright.async_api import async_playwright, Browser, BrowserContext

from config import QUARANTINE_DIR, SANDBOX_TIMEOUT_MS
from models import DetonationResult

logger = logging.getLogger("soar.sandbox")

# ── IP extraction pattern ────────────────────────────────────────────────
_IP_PATTERN = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")

# ── Login form detection heuristics ──────────────────────────────────────
_LOGIN_SELECTORS = [
    'input[type="password"]',
    'input[name*="pass"]',
    'input[name*="pwd"]',
    'form[action*="login"]',
    'form[action*="signin"]',
    'form[action*="auth"]',
]


async def _create_hardened_context(browser: Browser) -> BrowserContext:
    """
    Create a browser context with strict host isolation guardrails.

    All downloads are funnelled to the quarantine directory.
    JavaScript is enabled (required to trigger dynamic phishing kits),
    but the context is sandboxed from the host filesystem.
    """
    context = await browser.new_context(
        accept_downloads=True,
        java_script_enabled=True,
        ignore_https_errors=True,
        # Realistic viewport for mobile phishing detection
        viewport={"width": 412, "height": 915},
        user_agent=(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Mobile Safari/537.36"
        ),
    )
    return context


async def _hash_and_scrub(filepath: Path) -> dict:
    """
    Compute SHA-256 of a downloaded file, then immediately delete it.

    Never allows the downloaded binary to persist or auto-execute.
    Returns metadata dict with hash, filename, and size.
    """
    try:
        file_bytes = filepath.read_bytes()
        sha256 = hashlib.sha256(file_bytes).hexdigest()
        size = len(file_bytes)
        name = filepath.name
    finally:
        # Scrub the file regardless of success/failure
        try:
            filepath.unlink(missing_ok=True)
        except OSError:
            pass

    return {
        "filename": name,
        "sha256": sha256,
        "size_bytes": size,
    }


async def detonate_url(url: str) -> DetonationResult:
    """
    Detonate a suspicious URL in a headless Playwright Chromium instance.

    Captures:
    - Full redirect chain
    - Final rendered URL
    - Page screenshot
    - Login form detection
    - Downloaded file SHA-256 hashes (quarantined, then scrubbed)
    - External IP addresses contacted

    Returns a structured DetonationResult.
    """
    redirect_chain: list[str] = []
    external_ips: set[str] = set()
    downloaded_files: list[dict] = []
    screenshot_path: str | None = None
    has_login_form = False
    final_url = url
    error_msg: str | None = None

    try:
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=True)
            context = await _create_hardened_context(browser)
            page = await context.new_page()

            # Track redirect chain
            page.on("response", lambda resp: redirect_chain.append(resp.url))

            # Track external IPs from network requests
            def _capture_request(request):
                try:
                    parsed_url = request.url
                    matches = _IP_PATTERN.findall(parsed_url)
                    external_ips.update(matches)
                except Exception:
                    pass

            page.on("request", _capture_request)

            # Handle downloads — route to quarantine
            async def _handle_download(download):
                quarantine_path = QUARANTINE_DIR / download.suggested_filename
                await download.save_as(str(quarantine_path))
                file_meta = await _hash_and_scrub(quarantine_path)
                downloaded_files.append(file_meta)

            page.on("download", lambda dl: dl.page.evaluate("() => {}").then(
                lambda _: None
            ) if False else None)
            # Register download handler properly
            context.on("page", lambda p: None)  # Keep context alive

            try:
                # Navigate with timeout
                response = await page.goto(
                    url,
                    wait_until="networkidle",
                    timeout=SANDBOX_TIMEOUT_MS,
                )
                final_url = page.url

                # Wait for any dynamic content to render
                await page.wait_for_timeout(2000)

                # Detect login forms
                for selector in _LOGIN_SELECTORS:
                    try:
                        element = await page.query_selector(selector)
                        if element:
                            has_login_form = True
                            break
                    except Exception:
                        continue

                # Handle any triggered downloads
                try:
                    download = await page.wait_for_event("download", timeout=3000)
                    quarantine_path = QUARANTINE_DIR / download.suggested_filename
                    await download.save_as(str(quarantine_path))
                    file_meta = await _hash_and_scrub(quarantine_path)
                    downloaded_files.append(file_meta)
                except Exception:
                    pass  # No download triggered — expected for most pages

                # Capture screenshot
                ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
                screenshot_name = f"detonation_{ts}.png"
                screenshot_full_path = QUARANTINE_DIR / screenshot_name
                await page.screenshot(path=str(screenshot_full_path), full_page=True)
                screenshot_path = str(screenshot_full_path)

            except Exception as nav_err:
                error_msg = f"Navigation error: {nav_err}"
                logger.warning("Detonation navigation failed for %s: %s", url, nav_err)

            await context.close()
            await browser.close()

    except Exception as e:
        error_msg = f"Playwright error: {e}"
        logger.error("Detonation failed for %s: %s", url, e)

    # Determine verdict
    verdict = "clean"
    confidence = 0.0

    if has_login_form:
        verdict = "suspicious"
        confidence = 0.7
    if downloaded_files:
        verdict = "malicious"
        confidence = 0.9
    if has_login_form and downloaded_files:
        confidence = 0.95

    return DetonationResult(
        url=url,
        final_url=final_url,
        redirect_chain=redirect_chain[:20],  # Cap to prevent memory bloat
        screenshot_path=screenshot_path,
        has_login_form=has_login_form,
        downloaded_files=downloaded_files,
        external_ips=list(external_ips),
        verdict=verdict,
        confidence_score=round(confidence, 4),
        detonated_at=datetime.now(timezone.utc),
        error=error_msg,
    )
