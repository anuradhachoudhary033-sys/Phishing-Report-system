/// SOAR Client — Gmail Authentication Service
///
/// Uses [google_sign_in] to trigger Android's native Google Sign-In sheet,
/// request the `gmail.modify` scope, and retrieve a one-time `serverAuthCode`.
/// That code is then POSTed to the backend's `POST /auth/google` endpoint,
/// which exchanges it for long-lived tokens and activates real-time
/// Gmail → Pub/Sub → webhook monitoring.
///
/// Architecture note:
///   The WEB Client ID (not the Android Client ID) must be passed as
///   [serverClientId]. Google's OAuth system uses the Web Client ID to
///   issue a server-exchangeable auth code. The Android Client ID is only
///   used implicitly by Play Services to verify the calling app's SHA-1.

library;

import 'dart:convert';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:http/http.dart' as http;

class GmailAuthConfig {
  /// The **Web Application** OAuth Client ID from Google Cloud Console.
  /// NOT the Android Client ID — Google requires the Web ID for serverAuthCode.
  static const String webClientId =
      '1089603468965-cna69vf6su8nmvp7112siooa36j6e7g9.apps.googleusercontent.com';

  /// Backend base URL — your permanent static ngrok domain
  static const String backendBaseUrl = 'https://mangle-fading-deploy.ngrok-free.dev';
}

/// Result of a Gmail authentication attempt.
class GmailAuthResult {
  final bool success;
  final String? email;
  final String? historyId;
  final String? error;

  const GmailAuthResult({
    required this.success,
    this.email,
    this.historyId,
    this.error,
  });

  @override
  String toString() => success
      ? 'GmailAuthResult(email=$email, historyId=$historyId)'
      : 'GmailAuthResult(error=$error)';
}

/// Service that handles the full Gmail OAuth flow from the mobile side.
class GmailAuthService {
  late final GoogleSignIn _googleSignIn;

  /// Whether a user has successfully authenticated and their watch() is active.
  bool _isConnected = false;
  bool get isConnected => _isConnected;

  /// The authenticated user's email address (set after successful auth).
  String? _connectedEmail;
  String? get connectedEmail => _connectedEmail;

  GmailAuthService() {
    _googleSignIn = GoogleSignIn(
      serverClientId: GmailAuthConfig.webClientId,
      scopes: ['https://www.googleapis.com/auth/gmail.modify'],
      forceCodeForRefreshToken: true,
    );
  }

  /// Trigger the Google Sign-In flow and connect Gmail monitoring.
  ///
  /// Flow:
  ///   1. Opens native Google sign-in sheet on Android
  ///   2. User consents to gmail.modify scope
  ///   3. Google returns a one-time serverAuthCode
  ///   4. We POST that code to backend /auth/google
  ///   5. Backend exchanges it for tokens + activates watch()
  Future<GmailAuthResult> connectGmail() async {
    try {
      // Step 1: Native Google Sign-In
      final account = await _googleSignIn.signIn();

      if (account == null) {
        return const GmailAuthResult(
          success: false,
          error: 'Sign-in cancelled by user',
        );
      }

      final serverAuthCode = account.serverAuthCode;
      if (serverAuthCode == null) {
        return const GmailAuthResult(
          success: false,
          error: 'No serverAuthCode received — check serverClientId configuration',
        );
      }

      // Step 2: Send auth code to backend
      final response = await http.post(
        Uri.parse('${GmailAuthConfig.backendBaseUrl}/auth/google'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'auth_code': serverAuthCode}),
      );

      if (response.statusCode != 200) {
        final errorBody = jsonDecode(response.body);
        return GmailAuthResult(
          success: false,
          error: errorBody['detail'] ?? 'Backend returned ${response.statusCode}',
        );
      }

      final data = jsonDecode(response.body);
      _isConnected = true;
      _connectedEmail = data['email'] as String?;

      return GmailAuthResult(
        success: true,
        email: _connectedEmail,
        historyId: data['starting_history_id'] as String?,
      );
    } catch (e) {
      return GmailAuthResult(
        success: false,
        error: e.toString(),
      );
    }
  }

  /// Disconnect Gmail monitoring.
  Future<void> disconnectGmail() async {
    await _googleSignIn.signOut();
    _isConnected = false;
    _connectedEmail = null;
  }

  /// Check if user is currently signed in (does not hit network).
  Future<bool> isSignedIn() async {
    return _googleSignIn.isSignedIn();
  }
}
