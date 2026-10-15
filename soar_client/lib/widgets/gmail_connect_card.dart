/// SOAR Client — Gmail Connect Widget
///
/// A self-contained card that handles the full Google Sign-In flow:
///   • Idle state: animated gradient "Connect Gmail Protection" button
///   • Loading state: spinner with status text
///   • Connected state: green badge with the user's email + disconnect option
///   • Error state: inline error with retry
///
/// Uses the existing [GmailAuthService] — no extra state management needed.
library;

import 'dart:async';
import 'package:flutter/material.dart';
import '../services/gmail_auth_service.dart';
import '../theme/soar_theme.dart';

/// Drop-in widget — place it anywhere in the widget tree.
class GmailConnectCard extends StatefulWidget {
  const GmailConnectCard({super.key});

  @override
  State<GmailConnectCard> createState() => _GmailConnectCardState();
}

class _GmailConnectCardState extends State<GmailConnectCard>
    with SingleTickerProviderStateMixin {
  final GmailAuthService _authService = GmailAuthService();

  _UiState _state = _UiState.idle;
  String? _email;
  String? _errorMsg;

  // Subtle pulse animation on the connect button
  late final AnimationController _pulseController;
  late final Animation<double> _pulseAnim;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2000),
    )..repeat(reverse: true);
    _pulseAnim = Tween<double>(begin: 0.85, end: 1.0).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
    _checkExistingSession();
  }

  @override
  void dispose() {
    _pulseController.dispose();
    super.dispose();
  }

  Future<void> _checkExistingSession() async {
    final signedIn = await _authService.isSignedIn();
    if (signedIn && _authService.connectedEmail != null && mounted) {
      setState(() {
        _state = _UiState.connected;
        _email = _authService.connectedEmail;
      });
    }
  }

  Future<void> _handleConnect() async {
    setState(() {
      _state = _UiState.loading;
      _errorMsg = null;
    });

    final result = await _authService.connectGmail();

    if (!mounted) return;

    if (result.success) {
      setState(() {
        _state = _UiState.connected;
        _email = result.email;
      });
      _showSnack('✅ Gmail monitoring active for ${result.email}', success: true);
    } else {
      setState(() {
        _state = _UiState.error;
        _errorMsg = result.error;
      });
    }
  }

  Future<void> _handleDisconnect() async {
    await _authService.disconnectGmail();
    if (mounted) {
      setState(() {
        _state = _UiState.idle;
        _email = null;
        _errorMsg = null;
      });
    }
  }

  void _showSnack(String msg, {bool success = false}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg,
            style: const TextStyle(color: SoarTheme.textPrimary, fontSize: 13)),
        backgroundColor:
            success ? SoarTheme.successDim : SoarTheme.dangerDim,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(SoarTheme.radiusSm)),
        margin: const EdgeInsets.all(12),
        duration: const Duration(seconds: 4),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: SoarTheme.surfaceAlt,
        borderRadius: BorderRadius.circular(SoarTheme.radiusLg),
        border: Border.all(
          color: _state == _UiState.connected
              ? SoarTheme.success.withValues(alpha: 0.4)
              : SoarTheme.border,
          width: 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          _buildHeader(),
          const SizedBox(height: 12),
          _buildDescription(),
          const SizedBox(height: 20),
          _buildScopeRow(),
          const SizedBox(height: 20),
          _buildActionArea(),
          if (_state == _UiState.error && _errorMsg != null) ...[
            const SizedBox(height: 12),
            _buildErrorBanner(),
          ],
        ],
      ),
    );
  }

  Widget _buildHeader() {
    final (icon, color, label) = switch (_state) {
      _UiState.connected => (Icons.shield_rounded, SoarTheme.success, 'Protected'),
      _UiState.loading   => (Icons.sync_rounded, SoarTheme.accentCyan, 'Connecting...'),
      _UiState.error     => (Icons.warning_amber_rounded, SoarTheme.warning, 'Error'),
      _UiState.idle      => (Icons.shield_outlined, SoarTheme.textMuted, 'Not Connected'),
    };

    return Row(
      children: [
        Icon(icon, color: color, size: 20),
        const SizedBox(width: 10),
        Text('Gmail Phishing Protection',
            style: SoarTheme.heading3.copyWith(fontSize: 16)),
        const Spacer(),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: color.withValues(alpha: 0.3)),
          ),
          child: Text(label,
              style: SoarTheme.caption.copyWith(color: color, fontSize: 11)),
        ),
      ],
    );
  }

  Widget _buildDescription() {
    final text = _state == _UiState.connected
        ? 'Monitoring $_email in real-time. New phishing emails will be detected and reported within seconds.'
        : 'Connect your Gmail to scan incoming emails for phishing URLs in real-time via Google Cloud Pub/Sub.';

    return Text(text, style: SoarTheme.body.copyWith(fontSize: 13));
  }

  Widget _buildScopeRow() {
    return Row(
      children: [
        _ScopeChip(
          icon: Icons.mark_email_read_outlined,
          label: 'gmail.modify',
          tooltip: 'Read emails + apply phishing labels',
        ),
        const SizedBox(width: 8),
        _ScopeChip(
          icon: Icons.notifications_active_outlined,
          label: 'Real-time push',
          tooltip: 'Google Cloud Pub/Sub webhook',
        ),
      ],
    );
  }

  Widget _buildActionArea() {
    return switch (_state) {
      _UiState.idle      => _buildConnectButton(),
      _UiState.loading   => _buildLoadingIndicator(),
      _UiState.connected => _buildConnectedRow(),
      _UiState.error     => _buildConnectButton(),
    };
  }

  Widget _buildConnectButton() {
    return AnimatedBuilder(
      animation: _pulseAnim,
      builder: (_, _) => Transform.scale(
        scale: _pulseAnim.value,
        child: SizedBox(
          width: double.infinity,
          height: 48,
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFF06B6D4), Color(0xFF0EA5E9)],
                begin: Alignment.centerLeft,
                end: Alignment.centerRight,
              ),
              borderRadius: BorderRadius.circular(SoarTheme.radiusMd),
              boxShadow: [
                BoxShadow(
                  color: SoarTheme.accentCyan.withValues(alpha: 0.3),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Material(
              color: Colors.transparent,
              child: InkWell(
                onTap: _handleConnect,
                borderRadius: BorderRadius.circular(SoarTheme.radiusMd),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.shield_rounded, color: Colors.white, size: 18),
                    SizedBox(width: 8),
                    Text(
                      'Connect Gmail Protection',
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                        fontSize: 14,
                        letterSpacing: 0.3,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildLoadingIndicator() {
    return const SizedBox(
      height: 48,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: 18,
            height: 18,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              valueColor: AlwaysStoppedAnimation(SoarTheme.accentCyan),
            ),
          ),
          SizedBox(width: 12),
          Text('Authenticating with Google...',
              style: TextStyle(
                color: SoarTheme.textSecondary,
                fontSize: 13,
                fontWeight: FontWeight.w500,
              )),
        ],
      ),
    );
  }

  Widget _buildConnectedRow() {
    return Row(
      children: [
        Expanded(
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: SoarTheme.successDim.withValues(alpha: 0.5),
              borderRadius: BorderRadius.circular(SoarTheme.radiusMd),
              border: Border.all(
                  color: SoarTheme.success.withValues(alpha: 0.2)),
            ),
            child: Row(
              children: [
                const Icon(Icons.check_circle_outline,
                    color: SoarTheme.success, size: 16),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _email ?? 'Connected',
                    style: SoarTheme.body.copyWith(
                      color: SoarTheme.success,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(width: 8),
        IconButton(
          onPressed: _handleDisconnect,
          icon: const Icon(Icons.logout_rounded,
              color: SoarTheme.textMuted, size: 18),
          tooltip: 'Disconnect',
          style: IconButton.styleFrom(
            backgroundColor: SoarTheme.surface,
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(SoarTheme.radiusSm)),
          ),
        ),
      ],
    );
  }

  Widget _buildErrorBanner() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: SoarTheme.dangerDim.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(SoarTheme.radiusSm),
        border: Border.all(color: SoarTheme.danger.withValues(alpha: 0.3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.error_outline,
              color: SoarTheme.danger, size: 15),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _errorMsg!,
              style: SoarTheme.caption.copyWith(
                color: SoarTheme.danger,
                fontWeight: FontWeight.w400,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────

enum _UiState { idle, loading, connected, error }

class _ScopeChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final String tooltip;

  const _ScopeChip({
    required this.icon,
    required this.label,
    required this.tooltip,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: SoarTheme.surface,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: SoarTheme.border),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 11, color: SoarTheme.accentCyan),
            const SizedBox(width: 4),
            Text(label,
                style: SoarTheme.caption.copyWith(
                    fontSize: 10, color: SoarTheme.textSecondary)),
          ],
        ),
      ),
    );
  }
}
