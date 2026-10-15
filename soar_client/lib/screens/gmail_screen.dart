/// SOAR Client — Gmail Protection Screen (Tier 0)
///
/// The entry point for users to connect their Gmail account to
/// the real-time SOAR phishing pipeline. Hosts the [GmailConnectCard]
/// and explains how the system works in-app.
library;

import 'package:flutter/material.dart';
import '../theme/soar_theme.dart';
import '../widgets/gmail_connect_card.dart';
import '../widgets/shared_widgets.dart';

class GmailScreen extends StatelessWidget {
  const GmailScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: SoarTheme.bg,
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            // ── App Bar ──────────────────────────────────────────────────
            SliverAppBar(
              pinned: true,
              backgroundColor: SoarTheme.bg,
              expandedHeight: 80,
              flexibleSpace: FlexibleSpaceBar(
                titlePadding:
                    const EdgeInsets.only(left: 20, bottom: 14),
                title: Row(
                  children: [
                    Container(
                      width: 28,
                      height: 28,
                      decoration: BoxDecoration(
                        color: SoarTheme.accentCyanDim,
                        borderRadius:
                            BorderRadius.circular(SoarTheme.radiusSm),
                      ),
                      child: const Icon(Icons.email_rounded,
                          color: SoarTheme.accentCyan, size: 16),
                    ),
                    const SizedBox(width: 10),
                    Text('Gmail Monitor',
                        style: SoarTheme.heading2.copyWith(fontSize: 17)),
                  ],
                ),
              ),
            ),

            // ── Body ─────────────────────────────────────────────────────
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
              sliver: SliverList(
                delegate: SliverChildListDelegate([

                  // Connect card — the main action
                  const GmailConnectCard(),

                  const SizedBox(height: 24),

                  // How it works section
                  const SectionHeader(title: 'HOW IT WORKS'),
                  const SizedBox(height: 12),
                  _HowItWorksCard(),

                  const SizedBox(height: 24),

                  // What we detect section
                  const SectionHeader(title: 'WHAT WE DETECT'),
                  const SizedBox(height: 12),
                  _DetectionGrid(),

                  const SizedBox(height: 24),

                  // Privacy note
                  _PrivacyNote(),
                ]),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── How It Works Card ─────────────────────────────────────────────────────

class _HowItWorksCard extends StatelessWidget {
  final _steps = const [
    (
      icon: Icons.login_rounded,
      color: SoarTheme.accentCyan,
      title: 'Sign in with Google',
      desc: 'One tap — your credentials never leave your device.',
    ),
    (
      icon: Icons.notifications_active_outlined,
      color: SoarTheme.warning,
      title: 'Gmail notifies our server',
      desc: 'Google Cloud Pub/Sub pushes a tiny notification (no email body) within 1-3 seconds of a new email.',
    ),
    (
      icon: Icons.psychology_outlined,
      color: SoarTheme.success,
      title: 'ML pipeline runs',
      desc: 'The backend fetches only the raw email, extracts URLs, and runs the phishing detection model.',
    ),
    (
      icon: Icons.shield_rounded,
      color: SoarTheme.danger,
      title: 'Phishing emails get labelled',
      desc: 'Detected phishing emails are auto-labelled "[PHISHING]" in your Gmail and reported as IOCs.',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: SoarTheme.surfaceAlt,
        borderRadius: BorderRadius.circular(SoarTheme.radiusLg),
        border: Border.all(color: SoarTheme.border),
      ),
      child: Column(
        children: [
          for (int i = 0; i < _steps.length; i++) ...[
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 32,
                    height: 32,
                    decoration: BoxDecoration(
                      color: _steps[i].color.withValues(alpha: 0.12),
                      borderRadius:
                          BorderRadius.circular(SoarTheme.radiusSm),
                    ),
                    child: Icon(_steps[i].icon,
                        color: _steps[i].color, size: 16),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('${i + 1}. ${_steps[i].title}',
                            style: SoarTheme.heading3
                                .copyWith(fontSize: 13)),
                        const SizedBox(height: 3),
                        Text(_steps[i].desc,
                            style: SoarTheme.body.copyWith(fontSize: 12)),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            if (i < _steps.length - 1)
              const Divider(
                  height: 1, color: SoarTheme.border, indent: 60),
          ],
        ],
      ),
    );
  }
}

// ── Detection Grid ────────────────────────────────────────────────────────

class _DetectionGrid extends StatelessWidget {
  final _threats = const [
    (icon: Icons.link_off_rounded,   label: 'Malicious URLs'),
    (icon: Icons.person_off_rounded, label: 'Sender Spoofing'),
    (icon: Icons.timer_outlined,     label: 'Urgency Tactics'),
    (icon: Icons.swap_horiz_rounded, label: 'Hidden Link Mismatch'),
    (icon: Icons.attach_file_rounded, label: 'Dangerous Attachments'),
    (icon: Icons.dns_outlined,       label: 'Typosquat Domains'),
  ];

  @override
  Widget build(BuildContext context) {
    return GridView.count(
      crossAxisCount: 2,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 8,
      crossAxisSpacing: 8,
      childAspectRatio: 2.8,
      children: [
        for (final t in _threats)
          Container(
            padding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: SoarTheme.surfaceAlt,
              borderRadius:
                  BorderRadius.circular(SoarTheme.radiusMd),
              border: Border.all(color: SoarTheme.border),
            ),
            child: Row(
              children: [
                Icon(t.icon,
                    color: SoarTheme.accentCyan, size: 14),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(t.label,
                      style: SoarTheme.caption.copyWith(
                          color: SoarTheme.textSecondary,
                          fontSize: 11),
                      maxLines: 2),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

// ── Privacy Note ──────────────────────────────────────────────────────────

class _PrivacyNote extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: SoarTheme.surface,
        borderRadius: BorderRadius.circular(SoarTheme.radiusMd),
        border: Border.all(
            color: SoarTheme.accentCyan.withValues(alpha: 0.15)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.lock_outline_rounded,
              color: SoarTheme.accentCyan, size: 15),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Privacy: Google only sends us a tiny notification cursor (historyId). '
              'Your email content is fetched server-side only when a new email arrives, '
              'and is never stored — only its URLs are scanned.',
              style: SoarTheme.caption.copyWith(
                  color: SoarTheme.textMuted,
                  fontWeight: FontWeight.w400,
                  height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}
