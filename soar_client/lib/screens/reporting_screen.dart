/// Tier 3 — Law Enforcement Escalation / Reporting
///
/// A minimal, fast-loading form that fetches generated CERT-In compliant
/// JSON/PDF reports and provides a single, secure action button to
/// submit the intelligence package.
library;

import 'dart:math';
import 'package:flutter/material.dart';
import '../theme/soar_theme.dart';
import '../models/soar_models.dart';
import '../widgets/shared_widgets.dart';

class ReportingScreen extends StatefulWidget {
  const ReportingScreen({super.key});

  @override
  State<ReportingScreen> createState() => _ReportingScreenState();
}

class _ReportingScreenState extends State<ReportingScreen> {
  final List<IntelReport> _reports = [];
  final bool _loading = false;
  String? _submittingId;

  @override
  void initState() {
    super.initState();
    _loadDemoReports();
  }

  void _loadDemoReports() {
    final random = Random();
    final now = DateTime.now();

    _reports.addAll([
      IntelReport(
        reportId: 'RPT-${now.year}-001',
        certInRefNo: 'CERT-IN/2026/PHISH/00412',
        status: 'generated',
        generatedAt: now.subtract(Duration(hours: random.nextInt(6))),
        downloadUrl: '/api/v1/reports/RPT-2026-001/pdf',
      ),
      IntelReport(
        reportId: 'RPT-${now.year}-002',
        certInRefNo: 'CERT-IN/2026/PHISH/00398',
        status: 'submitted',
        generatedAt: now.subtract(const Duration(days: 1)),
        downloadUrl: '/api/v1/reports/RPT-2026-002/pdf',
      ),
      IntelReport(
        reportId: 'RPT-${now.year}-003',
        certInRefNo: 'CERT-IN/2026/PHISH/00385',
        status: 'acknowledged',
        generatedAt: now.subtract(const Duration(days: 3)),
        downloadUrl: '/api/v1/reports/RPT-2026-003/pdf',
      ),
    ]);
  }

  Future<void> _submitReport(IntelReport report) async {
    setState(() => _submittingId = report.reportId);

    // Simulate network call
    await Future.delayed(const Duration(seconds: 2));

    if (!mounted) return;
    setState(() {
      final idx = _reports.indexWhere((r) => r.reportId == report.reportId);
      if (idx != -1) {
        _reports[idx] = IntelReport(
          reportId: report.reportId,
          certInRefNo: report.certInRefNo,
          status: 'submitted',
          generatedAt: report.generatedAt,
          downloadUrl: report.downloadUrl,
        );
      }
      _submittingId = null;
    });

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Intelligence package ${report.reportId} submitted to CERT-In',
            style: SoarTheme.body.copyWith(color: Colors.white),
          ),
          backgroundColor: SoarTheme.success.withValues(alpha: 0.9),
          behavior: SnackBarBehavior.floating,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildHeader(),
          const SectionHeader(title: 'INTELLIGENCE REPORTS'),
          Expanded(child: _buildReportList()),
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
                colors: [SoarTheme.success, Color(0xFF059669)],
              ),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(Icons.description_outlined, color: Colors.white, size: 20),
          ),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Escalation', style: SoarTheme.heading2),
              Text('CERT-In intelligence reports', style: SoarTheme.caption),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildReportList() {
    if (_loading) {
      return const Center(
        child: CircularProgressIndicator(color: SoarTheme.accentCyan, strokeWidth: 2),
      );
    }

    if (_reports.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.folder_open_outlined, size: 48, color: SoarTheme.textMuted),
            const SizedBox(height: 12),
            Text('No reports generated yet…', style: SoarTheme.body),
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      itemCount: _reports.length,
      itemBuilder: (_, i) => _ReportCard(
        report: _reports[i],
        isSubmitting: _submittingId == _reports[i].reportId,
        onSubmit: () => _submitReport(_reports[i]),
      ),
    );
  }
}

// ── Report Card ─────────────────────────────────────────────────────────
class _ReportCard extends StatelessWidget {
  final IntelReport report;
  final bool isSubmitting;
  final VoidCallback onSubmit;

  const _ReportCard({
    required this.report,
    required this.isSubmitting,
    required this.onSubmit,
  });

  Color _statusColor() {
    switch (report.status) {
      case 'generated':
        return SoarTheme.accentCyan;
      case 'submitted':
        return SoarTheme.warning;
      case 'acknowledged':
        return SoarTheme.success;
      default:
        return SoarTheme.textMuted;
    }
  }

  @override
  Widget build(BuildContext context) {
    final dateStr =
        '${report.generatedAt.day.toString().padLeft(2, '0')}/'
        '${report.generatedAt.month.toString().padLeft(2, '0')}/'
        '${report.generatedAt.year}';

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: GlassCard(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header row
            Row(
              children: [
                Icon(Icons.article_outlined, color: _statusColor(), size: 20),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    report.reportId,
                    style: SoarTheme.heading3.copyWith(color: _statusColor()),
                  ),
                ),
                StatusPill(label: report.status, color: _statusColor()),
              ],
            ),
            const SizedBox(height: 12),
            // Details
            _InfoRow(label: 'CERT-In Ref', value: report.certInRefNo),
            const SizedBox(height: 6),
            _InfoRow(label: 'Generated', value: dateStr),
            const SizedBox(height: 6),
            _InfoRow(label: 'Format', value: 'JSON + PDF'),
            const SizedBox(height: 16),
            // Actions
            if (report.status == 'generated')
              SoarButton(
                label: 'Submit Intelligence Package',
                icon: Icons.send_outlined,
                color: SoarTheme.accentCyan,
                isLoading: isSubmitting,
                onPressed: onSubmit,
              )
            else if (report.status == 'submitted')
              SoarButton(
                label: 'Awaiting Acknowledgement',
                icon: Icons.schedule,
                color: SoarTheme.warning,
                onPressed: null,
              )
            else
              SoarButton(
                label: 'Acknowledged by CERT-In',
                icon: Icons.verified_outlined,
                color: SoarTheme.success,
                onPressed: null,
              ),
          ],
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;

  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 90,
          child: Text(label, style: SoarTheme.caption.copyWith(fontSize: 11)),
        ),
        Expanded(
          child: Text(
            value,
            style: SoarTheme.body.copyWith(color: SoarTheme.textPrimary, fontSize: 13),
          ),
        ),
      ],
    );
  }
}
