/// Tier 2 — Dynamic Analysis Alerts
///
/// Displays sandbox detonation results and fires local push
/// notifications when a payload analysis completes. Uses
/// flutter_local_notifications to mock FCM alerts.
library;

import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import '../theme/soar_theme.dart';
import '../models/soar_models.dart';
import '../services/notification_service.dart';
import '../widgets/shared_widgets.dart';

class AlertsScreen extends StatefulWidget {
  const AlertsScreen({super.key});

  @override
  State<AlertsScreen> createState() => _AlertsScreenState();
}

class _AlertsScreenState extends State<AlertsScreen> {
  final List<SandboxAlert> _alerts = [];
  Timer? _demoTimer;

  @override
  void initState() {
    super.initState();
    NotificationService().init();
    _startDemoAlerts();
  }

  void _startDemoAlerts() {
    final random = Random();
    const hashes = [
      'a3f8b2c1d4e5f6a7b8c9d0e1f2a3b4c5',
      'd7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2',
      'c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6',
      'b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9',
      'e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4',
    ];
    const verdicts = ['malicious', 'suspicious', 'clean', 'malicious', 'suspicious'];
    const sandboxes = ['sandbox-alpha', 'sandbox-bravo', 'sandbox-charlie'];

    // Generate an initial batch
    for (int i = 0; i < 3; i++) {
      final idx = random.nextInt(hashes.length);
      _alerts.add(SandboxAlert(
        id: 'alert-${DateTime.now().millisecondsSinceEpoch + i}',
        payloadHash: hashes[idx],
        verdict: verdicts[idx],
        confidenceScore: 0.6 + random.nextDouble() * 0.4,
        detonatedAt: DateTime.now().subtract(Duration(minutes: random.nextInt(30))),
        sandboxId: sandboxes[random.nextInt(sandboxes.length)],
      ));
    }

    // Periodic new alerts
    _demoTimer = Timer.periodic(const Duration(seconds: 12), (_) {
      if (!mounted) return;
      final idx = random.nextInt(hashes.length);
      final alert = SandboxAlert(
        id: 'alert-${DateTime.now().millisecondsSinceEpoch}',
        payloadHash: hashes[idx],
        verdict: verdicts[idx],
        confidenceScore: 0.6 + random.nextDouble() * 0.4,
        detonatedAt: DateTime.now(),
        sandboxId: sandboxes[random.nextInt(sandboxes.length)],
      );
      setState(() => _alerts.insert(0, alert));
      NotificationService().showSandboxAlert(alert);
    });
  }

  @override
  void dispose() {
    _demoTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildHeader(),
          _buildSummaryBar(),
          const SectionHeader(title: 'SANDBOX RESULTS'),
          Expanded(child: _buildAlertList()),
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
                colors: [SoarTheme.warning, Color(0xFFF59E0B)],
              ),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(Icons.bug_report_outlined, color: Colors.white, size: 20),
          ),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Dynamic Analysis', style: SoarTheme.heading2),
              Text('Sandbox detonation alerts', style: SoarTheme.caption),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSummaryBar() {
    final malicious = _alerts.where((a) => a.isMalicious).length;
    final suspicious = _alerts.where((a) => a.isSuspicious).length;
    final clean = _alerts.length - malicious - suspicious;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          _MiniStat(icon: Icons.dangerous_outlined, label: 'Malicious', count: malicious, color: SoarTheme.danger),
          const SizedBox(width: 10),
          _MiniStat(icon: Icons.warning_amber_rounded, label: 'Suspicious', count: suspicious, color: SoarTheme.warning),
          const SizedBox(width: 10),
          _MiniStat(icon: Icons.check_circle_outline, label: 'Clean', count: clean, color: SoarTheme.success),
        ],
      ),
    );
  }

  Widget _buildAlertList() {
    if (_alerts.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.hourglass_empty, size: 48, color: SoarTheme.textMuted),
            const SizedBox(height: 12),
            Text('No detonation results yet…', style: SoarTheme.body),
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      itemCount: _alerts.length,
      itemBuilder: (_, i) => _AlertTile(alert: _alerts[i]),
    );
  }
}

// ── Mini Stat ────────────────────────────────────────────────────────────
class _MiniStat extends StatelessWidget {
  final IconData icon;
  final String label;
  final int count;
  final Color color;

  const _MiniStat({
    required this.icon,
    required this.label,
    required this.count,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: GlassCard(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(icon, color: color, size: 18),
            const SizedBox(width: 8),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('$count', style: SoarTheme.heading3.copyWith(color: color)),
                Text(label, style: SoarTheme.caption.copyWith(fontSize: 9)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ── Alert Tile ──────────────────────────────────────────────────────────
class _AlertTile extends StatelessWidget {
  final SandboxAlert alert;

  const _AlertTile({required this.alert});

  Color _verdictColor() {
    if (alert.isMalicious) return SoarTheme.danger;
    if (alert.isSuspicious) return SoarTheme.warning;
    return SoarTheme.success;
  }

  IconData _verdictIcon() {
    if (alert.isMalicious) return Icons.dangerous_outlined;
    if (alert.isSuspicious) return Icons.warning_amber_rounded;
    return Icons.check_circle_outline;
  }

  @override
  Widget build(BuildContext context) {
    final timeStr =
        '${alert.detonatedAt.hour.toString().padLeft(2, '0')}:'
        '${alert.detonatedAt.minute.toString().padLeft(2, '0')}';

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: GlassCard(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(_verdictIcon(), color: _verdictColor(), size: 22),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'SHA256: ${alert.payloadHash.substring(0, 16)}…',
                    style: SoarTheme.mono.copyWith(fontSize: 12, color: SoarTheme.textPrimary),
                  ),
                ),
                StatusPill(label: alert.verdict, color: _verdictColor()),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                _DetailChip(label: 'Confidence', value: '${(alert.confidenceScore * 100).toStringAsFixed(1)}%'),
                const SizedBox(width: 12),
                _DetailChip(label: 'Sandbox', value: alert.sandboxId),
                const Spacer(),
                Text(timeStr, style: SoarTheme.caption.copyWith(fontSize: 10)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _DetailChip extends StatelessWidget {
  final String label;
  final String value;

  const _DetailChip({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('$label: ', style: SoarTheme.caption.copyWith(fontSize: 10)),
        Text(value, style: SoarTheme.caption.copyWith(fontSize: 10, color: SoarTheme.textPrimary)),
      ],
    );
  }
}
