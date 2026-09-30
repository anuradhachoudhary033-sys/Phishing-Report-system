/// Notification service for Tier 2 sandbox detonation alerts.
///
/// Uses flutter_local_notifications to mock push alerts for sandbox
/// analysis results without requiring Firebase / OneSignal credentials
/// in the initial build.
library;

import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import '../models/soar_models.dart';

class NotificationService {
  static final NotificationService _instance = NotificationService._();
  factory NotificationService() => _instance;
  NotificationService._();

  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  bool _initialized = false;

  Future<void> init() async {
    if (_initialized) return;

    const androidSettings =
        AndroidInitializationSettings('@mipmap/ic_launcher');
    const iosSettings = DarwinInitializationSettings(
      requestAlertPermission: true,
      requestBadgePermission: true,
      requestSoundPermission: true,
    );

    const settings = InitializationSettings(
      android: androidSettings,
      iOS: iosSettings,
    );

    await _plugin.initialize(settings);
    _initialized = true;
  }

  /// Show a sandbox alert notification (Tier 2).
  Future<void> showSandboxAlert(SandboxAlert alert) async {
    final title = alert.isMalicious
        ? '🚨 Malicious Payload Detonated'
        : alert.isSuspicious
            ? '⚠️ Suspicious Payload Analyzed'
            : '✅ Payload Clean';

    final body =
        'Hash: ${alert.payloadHash.substring(0, 12)}…\n'
        'Confidence: ${(alert.confidenceScore * 100).toStringAsFixed(1)}%\n'
        'Sandbox: ${alert.sandboxId}';

    const androidDetails = AndroidNotificationDetails(
      'soar_sandbox_alerts',
      'Sandbox Alerts',
      channelDescription: 'Tier 2 sandbox detonation results',
      importance: Importance.high,
      priority: Priority.high,
    );

    const notificationDetails = NotificationDetails(
      android: androidDetails,
      iOS: DarwinNotificationDetails(),
    );

    await _plugin.show(
      alert.hashCode,
      title,
      body,
      notificationDetails,
    );
  }
}
