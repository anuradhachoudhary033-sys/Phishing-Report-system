/// Tier 1 — Real-Time Triage Dashboard
///
/// Low-latency WebSockets view that streams live phishing blocks
/// and peer network updates. Runs the stream listener on the
/// framework's microtask queue so the main UI thread is never stalled.
library;

import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import '../theme/soar_theme.dart';
import '../models/soar_models.dart';
import '../services/websocket_service.dart';
import '../widgets/shared_widgets.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final WebSocketService _ws = WebSocketService();
  final List<PhishingEvent> _events = [];
  bool _connected = false;
  StreamSubscription? _eventSub;
  StreamSubscription? _statusSub;

  // Demo mode — generates synthetic events when the backend is offline.
  Timer? _demoTimer;
  bool _demoMode = true;

  // Stats
  int _totalBlocked = 0;
  int _phishingDetected = 0;

  @override
  void initState() {
    super.initState();
    _ws.connect();

    _statusSub = _ws.connectionStatus.listen((status) {
      setState(() => _connected = status);
      if (status && _demoMode) {
        _demoMode = false;
        _demoTimer?.cancel();
      }
    });

    _eventSub = _ws.events.listen(_handleEvent);

    // Start demo mode after a short delay if no connection
    Future.delayed(const Duration(seconds: 2), () {
      if (!_connected && mounted) {
        _startDemoMode();
      }
    });
  }

  void _startDemoMode() {
    _demoMode = true;
    final random = Random();
    const urls = [
      'https://secure-login-verify.suspicious.cc/auth',
      'https://bankofamerica.com.phish.xyz/login',
      'https://paypa1-secure.net/verify',
      'https://microsoft365-alert.com/reset',
      'https://apple-id-recovery.cc/confirm',
      'https://google.com/search?q=flutter',
      'https://github.com/flutter/flutter',
      'https://amaz0n-prime.review/deal',
      'https://linkedin.com/in/profile',
      'https://netlflix-renew.xyz/billing',
    ];
    const peers = ['peer-us-east', 'peer-eu-west', 'peer-ap-south', 'peer-af-north'];

    _demoTimer = Timer.periodic(const Duration(milliseconds: 1800), (_) {
      if (!mounted) return;
      final url = urls[random.nextInt(urls.length)];
      final score = random.nextDouble();
      final event = PhishingEvent(
        id: 'evt-${DateTime.now().millisecondsSinceEpoch}',
        url: url,
        riskScore: score,
        isPhishing: score > 0.5,
        bloomMatchCount: random.nextInt(4),
        sourceIp: '${random.nextInt(255)}.${random.nextInt(255)}.${random.nextInt(255)}.${random.nextInt(255)}',
        peerNode: peers[random.nextInt(peers.length)],
        timestamp: DateTime.now(),
      );
      _handleEvent(event);
    });
  }

  void _handleEvent(PhishingEvent event) {
    if (!mounted) return;
    setState(() {
      _events.insert(0, event);
      if (_events.length > 100) _events.removeLast(); // Keep buffer lean
      _totalBlocked++;
      if (event.isPhishing) _phishingDetected++;
    });
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    _statusSub?.cancel();
    _demoTimer?.cancel();
    _ws.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildHeader(),
          _buildStatsRow(),
          SectionHeader(
            title: 'LIVE FEED',
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                PulseDot(color: _connected ? SoarTheme.success : (_demoMode ? SoarTheme.warning : SoarTheme.danger)),
                const SizedBox(width: 6),
                Text(
                  _connected ? 'CONNECTED' : (_demoMode ? 'DEMO' : 'OFFLINE'),
                  style: SoarTheme.caption.copyWith(
                    color: _connected ? SoarTheme.success : (_demoMode ? SoarTheme.warning : SoarTheme.danger),
                    fontSize: 10,
                  ),
                ),
              ],
            ),
          ),
          Expanded(child: _buildEventList()),
        ],
      ),
    );
  }

  Widget _buildHeader() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 4),
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [SoarTheme.accentCyan, Color(0xFF0891B2)],
              ),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(Icons.shield_outlined, color: Colors.white, size: 20),
          ),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Triage Dashboard', style: SoarTheme.heading2),
              Text('Real-time phishing intercept', style: SoarTheme.caption),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildStatsRow() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          _StatCard(label: 'BLOCKED', value: '$_totalBlocked', color: SoarTheme.accentCyan),
          const SizedBox(width: 10),
          _StatCard(label: 'PHISHING', value: '$_phishingDetected', color: SoarTheme.danger),
          const SizedBox(width: 10),
          _StatCard(
            label: 'RATE',
            value: _totalBlocked > 0
                ? '${(_phishingDetected / _totalBlocked * 100).toStringAsFixed(0)}%'
                : '—',
            color: SoarTheme.warning,
          ),
        ],
      ),
    );
  }

  Widget _buildEventList() {
    if (_events.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.wifi_tethering, size: 48, color: SoarTheme.textMuted),
            const SizedBox(height: 12),
            Text('Awaiting events…', style: SoarTheme.body),
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      itemCount: _events.length,
      itemBuilder: (_, i) => _EventTile(event: _events[i]),
    );
  }
}

// ── Stat Card ────────────────────────────────────────────────────────────
class _StatCard extends StatelessWidget {
  final String label;
  final String value;
  final Color color;

  const _StatCard({required this.label, required this.value, required this.color});

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: GlassCard(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: SoarTheme.caption.copyWith(fontSize: 10)),
            const SizedBox(height: 6),
            Text(
              value,
              style: SoarTheme.heading1.copyWith(color: color, fontSize: 24),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Event Tile ──────────────────────────────────────────────────────────
class _EventTile extends StatelessWidget {
  final PhishingEvent event;

  const _EventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final timeStr =
        '${event.timestamp.hour.toString().padLeft(2, '0')}:'
        '${event.timestamp.minute.toString().padLeft(2, '0')}:'
        '${event.timestamp.second.toString().padLeft(2, '0')}';

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: GlassCard(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            RiskBadge(score: event.riskScore, size: 44),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    event.url,
                    style: SoarTheme.mono.copyWith(
                      fontSize: 12,
                      color: event.isPhishing
                          ? SoarTheme.danger
                          : SoarTheme.textSecondary,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      StatusPill(
                        label: event.isPhishing ? 'phishing' : 'clean',
                        color: event.isPhishing
                            ? SoarTheme.danger
                            : SoarTheme.success,
                      ),
                      const SizedBox(width: 8),
                      Text(event.peerNode, style: SoarTheme.caption.copyWith(fontSize: 10)),
                      const Spacer(),
                      Text(timeStr, style: SoarTheme.caption.copyWith(fontSize: 10)),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
