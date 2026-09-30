/// Reusable, minimal custom widgets used across the SOAR client.
///
/// Built natively with CustomPaint and core Flutter primitives
/// to avoid heavy third-party UI libraries.
library;

import 'package:flutter/material.dart';
import '../theme/soar_theme.dart';

// ── Glassmorphic Card ───────────────────────────────────────────────────
/// A dark glassmorphic card with a subtle gradient border.
class GlassCard extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  final VoidCallback? onTap;

  const GlassCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: padding,
        decoration: BoxDecoration(
          color: SoarTheme.surfaceAlt.withValues(alpha: 0.7),
          borderRadius: BorderRadius.circular(SoarTheme.radiusMd),
          border: Border.all(color: SoarTheme.border, width: 1),
        ),
        child: child,
      ),
    );
  }
}

// ── Risk Score Indicator ────────────────────────────────────────────────
/// A small circular indicator that visualises a 0.0–1.0 risk score
/// using a colour gradient from green → amber → red.
class RiskBadge extends StatelessWidget {
  final double score;
  final double size;

  const RiskBadge({super.key, required this.score, this.size = 40});

  Color _color() {
    if (score < 0.3) return SoarTheme.success;
    if (score < 0.6) return SoarTheme.warning;
    return SoarTheme.danger;
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(
        painter: _RiskPainter(score: score, color: _color()),
        child: Center(
          child: Text(
            (score * 100).toStringAsFixed(0),
            style: SoarTheme.caption.copyWith(
              color: _color(),
              fontWeight: FontWeight.w700,
              fontSize: size * 0.3,
            ),
          ),
        ),
      ),
    );
  }
}

class _RiskPainter extends CustomPainter {
  final double score;
  final Color color;

  _RiskPainter({required this.score, required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2 - 3;

    // Background track
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = SoarTheme.border
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3,
    );

    // Score arc
    const startAngle = -1.5708; // -π/2 (top)
    final sweepAngle = 6.2832 * score; // 2π * score
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius),
      startAngle,
      sweepAngle,
      false,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(covariant _RiskPainter old) =>
      old.score != score || old.color != color;
}

// ── Status Pill ─────────────────────────────────────────────────────────
/// A small coloured pill showing a status label.
class StatusPill extends StatelessWidget {
  final String label;
  final Color color;
  final Color? bgColor;

  const StatusPill({
    super.key,
    required this.label,
    required this.color,
    this.bgColor,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: bgColor ?? color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label.toUpperCase(),
        style: SoarTheme.caption.copyWith(
          color: color,
          fontSize: 10,
          fontWeight: FontWeight.w700,
          letterSpacing: 1.0,
        ),
      ),
    );
  }
}

// ── Animated Pulse Dot ──────────────────────────────────────────────────
/// A small dot that pulses to indicate live activity.
class PulseDot extends StatefulWidget {
  final Color color;
  final double size;

  const PulseDot({
    super.key,
    this.color = SoarTheme.success,
    this.size = 8,
  });

  @override
  State<PulseDot> createState() => _PulseDotState();
}

class _PulseDotState extends State<PulseDot>
    with SingleTickerProviderStateMixin {
  late AnimationController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (_, _) => Container(
        width: widget.size,
        height: widget.size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: widget.color.withValues(alpha: 0.5 + _ctrl.value * 0.5),
          boxShadow: [
            BoxShadow(
              color: widget.color.withValues(alpha: _ctrl.value * 0.4),
              blurRadius: widget.size * 2,
              spreadRadius: widget.size * 0.5 * _ctrl.value,
            ),
          ],
        ),
      ),
    );
  }
}

// ── Section Header ──────────────────────────────────────────────────────
class SectionHeader extends StatelessWidget {
  final String title;
  final Widget? trailing;

  const SectionHeader({super.key, required this.title, this.trailing});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      child: Row(
        children: [
          Text(title, style: SoarTheme.caption),
          const Spacer(),
          ?trailing,
        ],
      ),
    );
  }
}

// ── Primary Action Button ───────────────────────────────────────────────
class SoarButton extends StatelessWidget {
  final String label;
  final VoidCallback? onPressed;
  final bool isLoading;
  final Color color;
  final IconData? icon;

  const SoarButton({
    super.key,
    required this.label,
    this.onPressed,
    this.isLoading = false,
    this.color = SoarTheme.accentCyan,
    this.icon,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 48,
      child: ElevatedButton(
        onPressed: isLoading ? null : onPressed,
        style: ElevatedButton.styleFrom(
          backgroundColor: color.withValues(alpha: 0.15),
          foregroundColor: color,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(SoarTheme.radiusMd),
            side: BorderSide(color: color.withValues(alpha: 0.3)),
          ),
        ),
        child: isLoading
            ? SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: color,
                ),
              )
            : Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  if (icon != null) ...[
                    Icon(icon, size: 18),
                    const SizedBox(width: 8),
                  ],
                  Text(
                    label,
                    style: SoarTheme.heading3.copyWith(
                      color: color,
                      fontSize: 14,
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}
