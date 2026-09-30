"""
SOAR Backend — Docker Sandbox Runner

Dual-mode execution engine:
1. Direct mode (default): Runs Playwright directly on the host for
   local development and testing.
2. Docker mode: Spawns ephemeral containers with Playwright pre-installed
   for production isolation.

Mode is controlled by SOAR_USE_DOCKER environment variable.
"""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path

from config import (
    DOCKER_IMAGE,
    QUARANTINE_DIR,
    SANDBOX_TIMEOUT_MS,
    SANDBOX_USE_DOCKER,
)
from models import DetonationRequest, DetonationResult
from sandbox import detonate_url
from sandbox.brand_detector import detect_brand_spoofing

logger = logging.getLogger("soar.runner")


async def _run_docker_sandbox(url: str) -> DetonationResult:
    """
    Spawn an ephemeral Docker container to detonate a URL.

    The container:
    - Uses the official Playwright Docker image
    - Mounts the quarantine directory for screenshot/artifact exchange
    - Enforces resource limits and timeout
    - Is automatically removed after execution
    """
    container_name = f"soar-sandbox-{hash(url) & 0xFFFFFF:06x}"
    quarantine_mount = str(QUARANTINE_DIR.resolve())

    cmd = [
        "docker", "run",
        "--rm",
        "--name", container_name,
        "--network=none",           # No outbound network from container
        "--memory=512m",            # Memory limit
        "--cpus=1",                 # CPU limit
        "-v", f"{quarantine_mount}:/quarantine",
        DOCKER_IMAGE,
        "python", "-c",
        f"""
import asyncio, json
from playwright.async_api import async_playwright

async def run():
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True)
        page = await browser.new_page()
        redirects = []
        page.on("response", lambda r: redirects.append(r.url))
        try:
            await page.goto("{url}", wait_until="networkidle", timeout={SANDBOX_TIMEOUT_MS})
        except: pass
        await page.screenshot(path="/quarantine/docker_screenshot.png", full_page=True)
        has_login = bool(await page.query_selector('input[type="password"]'))
        result = {{
            "final_url": page.url,
            "redirect_chain": redirects[:20],
            "has_login_form": has_login,
        }}
        print(json.dumps(result))
        await browser.close()

asyncio.run(run())
""",
    ]

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

        stdout, stderr = await asyncio.wait_for(
            process.communicate(),
            timeout=SANDBOX_TIMEOUT_MS / 1000 + 30,  # Extra 30s for container spin-up
        )

        if process.returncode == 0 and stdout:
            data = json.loads(stdout.decode().strip().split("\n")[-1])
            screenshot = QUARANTINE_DIR / "docker_screenshot.png"

            return DetonationResult(
                url=url,
                final_url=data.get("final_url", url),
                redirect_chain=data.get("redirect_chain", []),
                screenshot_path=str(screenshot) if screenshot.exists() else None,
                has_login_form=data.get("has_login_form", False),
                verdict="suspicious" if data.get("has_login_form") else "clean",
                confidence_score=0.7 if data.get("has_login_form") else 0.1,
            )
        else:
            error = stderr.decode()[:500] if stderr else "Unknown Docker error"
            logger.error("Docker sandbox failed: %s", error)
            return DetonationResult(url=url, error=f"Docker error: {error}")

    except asyncio.TimeoutError:
        # Kill the container if it times out
        kill_proc = await asyncio.create_subprocess_exec(
            "docker", "kill", container_name,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        await kill_proc.communicate()
        return DetonationResult(url=url, error="Docker sandbox timed out")

    except FileNotFoundError:
        logger.error("Docker not found — falling back to direct mode")
        return await detonate_url(url)


async def run_sandbox(request: DetonationRequest) -> DetonationResult:
    """
    Execute the sandbox pipeline in the configured mode.

    After detonation, runs brand spoofing detection on the captured
    screenshot to identify impersonated brands.
    """
    if SANDBOX_USE_DOCKER:
        logger.info("Running Docker sandbox for %s", request.url)
        result = await _run_docker_sandbox(request.url)
    else:
        logger.info("Running direct sandbox for %s", request.url)
        result = await detonate_url(request.url)

    # Post-detonation: brand spoofing detection
    if result.screenshot_path and Path(result.screenshot_path).exists():
        brand, confidence = detect_brand_spoofing(result.screenshot_path)
        if brand:
            result.brand_match = brand
            result.brand_confidence = confidence
            # Upgrade verdict if brand spoofing detected
            if result.verdict != "malicious":
                result.verdict = "malicious"
                result.confidence_score = max(result.confidence_score, confidence)

    return result
