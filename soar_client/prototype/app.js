/* ══════════════════════════════════════════════════════════════════
   SOAR Client — Web Prototype Application Logic
   Connected to live FastAPI backend via WebSockets
   ══════════════════════════════════════════════════════════════════ */

// ── Configuration ────────────────────────────────────────────────
const API_URL = 'http://127.0.0.1:8000';
const WS_URL = 'ws://127.0.0.1:8000/ws/triage';

// ── Navigation ───────────────────────────────────────────────────
document.querySelectorAll('.nav-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const screen = btn.dataset.screen;

    // Update active nav button
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');

    // Switch screen
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    document.getElementById(`screen-${screen}`).classList.add('active');
  });
});

// ── Utilities ────────────────────────────────────────────────────
function pad2(n) { return n.toString().padStart(2, '0'); }

function timeStr(dateStr) {
  const date = dateStr ? new Date(dateStr) : new Date();
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

function dateStr(dateStr) {
  const date = dateStr ? new Date(dateStr) : new Date();
  return `${pad2(date.getDate())}/${pad2(date.getMonth() + 1)}/${date.getFullYear()}`;
}

// ── Risk Badge SVG ───────────────────────────────────────────────
function createRiskBadge(score) {
  const r = 19; // radius
  const circ = 2 * Math.PI * r;
  const dashoffset = circ * (1 - score);

  let color;
  if (score < 0.3) color = 'var(--success)';
  else if (score < 0.6) color = 'var(--warning)';
  else color = 'var(--danger)';

  return `
    <div class="risk-badge">
      <svg viewBox="0 0 44 44">
        <circle class="track" cx="22" cy="22" r="${r}" />
        <circle class="score-arc" cx="22" cy="22" r="${r}"
          stroke="${color}"
          stroke-dasharray="${circ}"
          stroke-dashoffset="${dashoffset}" />
      </svg>
      <span class="score-text" style="color:${color}">${Math.round(score * 100)}</span>
    </div>
  `;
}

// ══════════════════════════════════════════════════════════════════
// TIER 1 — TRIAGE DASHBOARD
// ══════════════════════════════════════════════════════════════════

let totalBlocked = 0;
let phishingDetected = 0;
const triageFeed = document.getElementById('triage-feed');

function addTriageEvent(eventData) {
  const isPhishing = eventData.is_phishing;
  totalBlocked++;
  if (isPhishing) phishingDetected++;

  // Update stats
  document.getElementById('stat-blocked').textContent = totalBlocked;
  document.getElementById('stat-phishing').textContent = phishingDetected;
  document.getElementById('stat-rate').textContent =
    totalBlocked > 0 ? `${Math.round((phishingDetected / totalBlocked) * 100)}%` : '—';

  // Create tile
  const tile = document.createElement('div');
  tile.className = 'glass-card event-tile';
  tile.innerHTML = `
    <div class="event-tile-inner">
      ${createRiskBadge(eventData.risk_score)}
      <div class="event-details">
        <div class="event-url ${isPhishing ? 'phishing' : 'clean'}">${eventData.url}</div>
        <div class="event-meta">
          <span class="status-pill ${isPhishing ? 'pill-danger' : 'pill-success'}">
            ${isPhishing ? 'PHISHING' : 'CLEAN'}
          </span>
          <span class="event-peer">${eventData.peer_node || 'local'}</span>
          <span class="event-spacer"></span>
          <span class="event-time">${timeStr(eventData.timestamp)}</span>
        </div>
      </div>
    </div>
  `;

  // Remove empty state
  const empty = triageFeed.querySelector('.empty-state');
  if (empty) empty.remove();

  // Prepend & cap at 50
  triageFeed.prepend(tile);
  while (triageFeed.children.length > 50) {
    triageFeed.lastChild.remove();
  }
}

// ── WebSocket Connection ─────────────────────────────────────────
let ws = null;
const connectionDot = document.getElementById('connection-dot');
const connectionStatus = document.getElementById('connection-status');

function connectWebSocket() {
  ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    connectionDot.className = 'pulse-dot connected-dot';
    connectionStatus.className = 'status-text success-text';
    connectionStatus.textContent = 'LIVE FEED';
    showSnackbar('Connected to SOAR backend pipeline');
  };

  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      if (data.id && data.id.startsWith('det-')) {
        // Handle Detonation Alert
        addAlertTile(data);
      } else {
        // Handle Triage Event
        addTriageEvent(data);
      }
    } catch (e) {
      console.error('Error parsing WebSocket message:', e);
    }
  };

  ws.onclose = () => {
    connectionDot.className = 'pulse-dot demo-dot';
    connectionStatus.className = 'status-text warning-text';
    connectionStatus.textContent = 'DISCONNECTED';
    
    // Auto reconnect after 3 seconds
    setTimeout(connectWebSocket, 3000);
  };

  ws.onerror = (err) => {
    console.error('WebSocket error:', err);
    ws.close();
  };
}

// ── Manual Triage Form ───────────────────────────────────────────
document.getElementById('triage-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const input = document.getElementById('triage-url-input');
  const btn = document.querySelector('.submit-btn');
  const url = input.value.trim();
  
  if (!url) return;
  
  btn.classList.add('loading');
  btn.innerHTML = '<div class="spinner"></div>';
  
  try {
    const response = await fetch(`${API_URL}/api/v1/triage`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: url,
        client_score: 0.0,
        source_ip: '127.0.0.1',
        peer_node: 'web-prototype'
      })
    });
    
    if (response.ok) {
      const result = await response.json();
      input.value = '';
      if (result.action === 'QUEUE_SANDBOX') {
        showSnackbar(`Suspicious URL queued for sandbox detonation`);
      }
    } else {
      showSnackbar(`Error: ${response.statusText}`);
    }
  } catch (error) {
    showSnackbar(`Connection error: Is the backend running?`);
  } finally {
    btn.classList.remove('loading');
    btn.innerHTML = '<span class="material-icons-round">radar</span>';
  }
});


// ══════════════════════════════════════════════════════════════════
// TIER 2 — DYNAMIC ANALYSIS ALERTS
// ══════════════════════════════════════════════════════════════════

let maliciousCount = 0;
let suspiciousCount = 0;
let cleanCount = 0;
const alertsFeed = document.getElementById('alerts-feed');

function verdictColor(verdict) {
  if (verdict === 'malicious') return 'danger';
  if (verdict === 'suspicious') return 'warning';
  return 'success';
}

function verdictIcon(verdict) {
  if (verdict === 'malicious') return 'dangerous';
  if (verdict === 'suspicious') return 'warning';
  return 'check_circle';
}

function addAlertTile(alertData) {
  // Translate PhishingEvent data to alert format for UI
  const verdict = alertData.is_phishing ? (alertData.risk_score > 0.8 ? 'malicious' : 'suspicious') : 'clean';
  const confidence = alertData.risk_score;
  const hash = alertData.url.substring(0, 32); // Fallback if no hash
  const sandbox = 'docker-runner-01';
  const vColor = verdictColor(verdict);

  // Stats
  if (verdict === 'malicious') maliciousCount++;
  else if (verdict === 'suspicious') suspiciousCount++;
  else cleanCount++;

  document.getElementById('stat-malicious').textContent = maliciousCount;
  document.getElementById('stat-suspicious').textContent = suspiciousCount;
  document.getElementById('stat-clean').textContent = cleanCount;

  // Create tile
  const tile = document.createElement('div');
  tile.className = 'glass-card alert-tile';
  tile.innerHTML = `
    <div class="alert-header">
      <span class="material-icons-round ${vColor}-icon">${verdictIcon(verdict)}</span>
      <span class="alert-hash" title="${alertData.url}">URL: ${alertData.url.substring(0, 30)}…</span>
      <span class="status-pill pill-${vColor}">${verdict.toUpperCase()}</span>
    </div>
    <div class="alert-details">
      <span class="detail-chip">Confidence: <strong>${(confidence * 100).toFixed(1)}%</strong></span>
      <span class="detail-chip">Sandbox: <strong>${sandbox}</strong></span>
      <span class="event-spacer"></span>
      <span class="event-time">${timeStr(alertData.timestamp)}</span>
    </div>
  `;

  // Remove empty state
  const empty = alertsFeed.querySelector('.empty-state');
  if (empty) empty.remove();

  alertsFeed.prepend(tile);
  
  // Show push notification toast
  showNotificationToast({ verdict, url: alertData.url, confidence, sandbox });
  
  // Fetch fresh reports
  fetchReports();
}

function showNotificationToast(alert) {
  const existing = document.querySelector('.notification-toast');
  if (existing) existing.remove();

  let title = '✅ Payload Clean';
  if (alert.verdict === 'malicious') title = '🚨 Malicious Payload Detonated';
  else if (alert.verdict === 'suspicious') title = '⚠️ Suspicious Payload Analyzed';

  const toast = document.createElement('div');
  toast.className = 'notification-toast';
  toast.innerHTML = `
    <div class="toast-title">${title}</div>
    <div class="toast-body">
      Target: ${alert.url.substring(0, 25)}…<br>
      Confidence: ${(alert.confidence * 100).toFixed(1)}%<br>
      Sandbox: ${alert.sandbox}
    </div>
  `;

  document.body.appendChild(toast);
  requestAnimationFrame(() => {
    requestAnimationFrame(() => toast.classList.add('visible'));
  });

  setTimeout(() => {
    toast.classList.remove('visible');
    setTimeout(() => toast.remove(), 400);
  }, 3500);
}


// ══════════════════════════════════════════════════════════════════
// TIER 3 — INTELLIGENCE REPORTS
// ══════════════════════════════════════════════════════════════════
const reportsFeed = document.getElementById('reports-feed');

function statusColor(status) {
  if (status === 'generated') return 'cyan';
  if (status === 'submitted') return 'warning';
  if (status === 'acknowledged') return 'success';
  return 'text-muted';
}

function createReportCard(report) {
  const sColor = statusColor(report.status);
  const card = document.createElement('div');
  card.className = 'glass-card report-card';
  card.id = `report-${report.report_id}`;

  let btnHtml = '';
  if (report.status === 'generated') {
    btnHtml = `
      <div class="report-actions">
        <button class="soar-btn soar-btn-cyan flex-1" onclick="submitReport('${report.report_id}', this)">
          <span class="material-icons-round">send</span>
          Submit to CERT-In
        </button>
        <a href="${API_URL}${report.download_url}" target="_blank" class="soar-btn soar-btn-outline icon-only">
          <span class="material-icons-round">picture_as_pdf</span>
        </a>
      </div>
    `;
  } else if (report.status === 'submitted') {
    btnHtml = `
      <div class="report-actions">
        <button class="soar-btn soar-btn-warning flex-1" disabled>
          <span class="material-icons-round">schedule</span>
          Awaiting Acknowledgement
        </button>
        <a href="${API_URL}${report.download_url}" target="_blank" class="soar-btn soar-btn-outline icon-only">
          <span class="material-icons-round">picture_as_pdf</span>
        </a>
      </div>
    `;
  }

  card.innerHTML = `
    <div class="report-header">
      <span class="material-icons-round" style="color:var(--${sColor})">${report.status === 'acknowledged' ? 'verified' : 'article'}</span>
      <span class="report-id" style="color:var(--${sColor})">${report.report_id}</span>
      <span class="status-pill pill-${sColor}">${report.status.toUpperCase()}</span>
    </div>
    <div class="info-row">
      <span class="info-label">CERT-In Ref</span>
      <span class="info-value">${report.cert_in_ref_no}</span>
    </div>
    <div class="info-row">
      <span class="info-label">Generated</span>
      <span class="info-value">${dateStr(report.generated_at)} ${timeStr(report.generated_at)}</span>
    </div>
    <div class="info-row">
      <span class="info-label">Format</span>
      <span class="info-value">JSON + PDF</span>
    </div>
    ${btnHtml}
  `;

  return card;
}

window.submitReport = async function(reportId, btn) {
  btn.disabled = true;
  const originalHtml = btn.innerHTML;
  btn.innerHTML = '<div class="spinner"></div>';

  try {
    const response = await fetch(`${API_URL}/api/v1/reports/${reportId}/submit`, {
      method: 'POST'
    });
    
    if (response.ok) {
      showSnackbar(`Intelligence package ${reportId} submitted to CERT-In`);
      fetchReports(); // Refresh the list
    } else {
      showSnackbar(`Failed to submit: ${response.statusText}`);
      btn.disabled = false;
      btn.innerHTML = originalHtml;
    }
  } catch (error) {
    showSnackbar(`Connection error during submission`);
    btn.disabled = false;
    btn.innerHTML = originalHtml;
  }
};

function showSnackbar(message) {
  const el = document.getElementById('snackbar');
  el.textContent = message;
  el.className = 'snackbar snackbar-success visible';

  setTimeout(() => {
    el.className = 'snackbar hidden';
  }, 3000);
}

async function fetchReports() {
  try {
    const response = await fetch(`${API_URL}/api/v1/reports`);
    if (response.ok) {
      const reports = await response.json();
      reportsFeed.innerHTML = '';
      if (reports.length === 0) {
        reportsFeed.innerHTML = `
          <div class="empty-state">
            <span class="material-icons-round empty-icon">assignment_turned_in</span>
            <p>No reports generated yet</p>
          </div>
        `;
      } else {
        reports.forEach(r => reportsFeed.appendChild(createReportCard(r)));
      }
    }
  } catch (error) {
    console.error("Failed to fetch reports", error);
  }
}

// ── Initialization ───────────────────────────────────────────────
connectWebSocket();
fetchReports();
