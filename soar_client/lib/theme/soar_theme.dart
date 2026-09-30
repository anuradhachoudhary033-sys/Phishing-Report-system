/// SOAR Client — Application design tokens and theme configuration.
///
/// Uses a dark, cyber-security-inspired palette with neon accent colors
/// to convey urgency and precision. All colors use curated HSL values
/// rather than generic Material primaries.
library;

import 'package:flutter/material.dart';

class SoarTheme {
  SoarTheme._();

  // ── Core palette ──────────────────────────────────────────────────────
  static const Color bg         = Color(0xFF0A0E17);
  static const Color surface    = Color(0xFF111827);
  static const Color surfaceAlt = Color(0xFF1A2332);
  static const Color border     = Color(0xFF1E2A3A);

  static const Color textPrimary   = Color(0xFFE2E8F0);
  static const Color textSecondary = Color(0xFF94A3B8);
  static const Color textMuted     = Color(0xFF64748B);

  // Accent — cyan / teal for "safe" or neutral actions
  static const Color accentCyan     = Color(0xFF06B6D4);
  static const Color accentCyanDim  = Color(0xFF164E63);

  // Danger — red / rose for phishing / high-risk
  static const Color danger     = Color(0xFFF43F5E);
  static const Color dangerDim  = Color(0xFF4C0519);

  // Warning — amber
  static const Color warning    = Color(0xFFFBBF24);
  static const Color warningDim = Color(0xFF451A03);

  // Success — emerald
  static const Color success    = Color(0xFF34D399);
  static const Color successDim = Color(0xFF064E3B);

  // ── Typography ────────────────────────────────────────────────────────
  static const String fontFamily = 'Inter';

  static TextStyle heading1 = const TextStyle(
    fontFamily: fontFamily,
    fontSize: 22,
    fontWeight: FontWeight.w700,
    color: textPrimary,
    letterSpacing: -0.5,
  );

  static TextStyle heading2 = const TextStyle(
    fontFamily: fontFamily,
    fontSize: 18,
    fontWeight: FontWeight.w600,
    color: textPrimary,
    letterSpacing: -0.3,
  );

  static TextStyle heading3 = const TextStyle(
    fontFamily: fontFamily,
    fontSize: 15,
    fontWeight: FontWeight.w600,
    color: textPrimary,
  );

  static TextStyle body = const TextStyle(
    fontFamily: fontFamily,
    fontSize: 14,
    fontWeight: FontWeight.w400,
    color: textSecondary,
    height: 1.5,
  );

  static TextStyle caption = const TextStyle(
    fontFamily: fontFamily,
    fontSize: 12,
    fontWeight: FontWeight.w500,
    color: textMuted,
    letterSpacing: 0.5,
  );

  static TextStyle mono = const TextStyle(
    fontFamily: 'monospace',
    fontSize: 13,
    fontWeight: FontWeight.w400,
    color: accentCyan,
    letterSpacing: 0.8,
  );

  // ── Border Radius ─────────────────────────────────────────────────────
  static const double radiusSm = 6;
  static const double radiusMd = 10;
  static const double radiusLg = 16;

  // ── ThemeData builder ─────────────────────────────────────────────────
  static ThemeData build() {
    return ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: bg,
      fontFamily: fontFamily,
      colorScheme: const ColorScheme.dark(
        surface: surface,
        primary: accentCyan,
        error: danger,
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: bg,
        elevation: 0,
        scrolledUnderElevation: 0,
        titleTextStyle: heading2,
        iconTheme: const IconThemeData(color: textSecondary, size: 20),
      ),
      bottomNavigationBarTheme: const BottomNavigationBarThemeData(
        backgroundColor: surface,
        selectedItemColor: accentCyan,
        unselectedItemColor: textMuted,
        type: BottomNavigationBarType.fixed,
        elevation: 0,
      ),
      dividerColor: border,
      cardColor: surfaceAlt,
    );
  }
}
