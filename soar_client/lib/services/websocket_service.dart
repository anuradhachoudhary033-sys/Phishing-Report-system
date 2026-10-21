/// WebSocket service for Tier 1 real-time triage dashboard.
///
/// Maintains a persistent connection to the FastAPI backend,
/// streaming live phishing block events and peer network updates
/// without stalling the main UI thread.
library;

import 'dart:async';
import 'dart:convert';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../models/soar_models.dart';

class WebSocketService {
  WebSocketChannel? _channel;
  final StreamController<PhishingEvent> _eventController =
      StreamController<PhishingEvent>.broadcast();
  final StreamController<bool> _connectionController =
      StreamController<bool>.broadcast();

  Timer? _reconnectTimer;
  Timer? _pingTimer;
  bool _disposed = false;
  bool _connected = false;

  /// The backend WebSocket endpoint.
  final String wsUrl;

  WebSocketService({
    this.wsUrl = 'wss://mangle-fading-deploy.ngrok-free.dev/ws/triage',
  });

  /// Exposed stream of live phishing events.
  Stream<PhishingEvent> get events => _eventController.stream;

  /// Connection status stream: true = connected.
  Stream<bool> get connectionStatus => _connectionController.stream;

  /// Initiate WebSocket connection.
  void connect() {
    if (_disposed) return;
    try {
      _channel = WebSocketChannel.connect(Uri.parse(wsUrl));

      // Only mark connected once the handshake completes successfully.
      // WebSocketChannel.connect() is lazy — the connection is not verified
      // until .ready resolves, so we must not emit true before this.
      _channel!.ready.then((_) {
        if (!_disposed) {
          _connected = true;
          _connectionController.add(true);
          _startPing();
        }
      }).catchError((_) {
        _connectionController.add(false);
        _scheduleReconnect();
      });

      _channel!.stream.listen(
        (message) {
          try {
            final data = jsonDecode(message as String) as Map<String, dynamic>;
            final event = PhishingEvent.fromJson(data);
            _eventController.add(event);
          } catch (_) {
            // Malformed message — silently drop
          }
        },
        onDone: () {
          _connected = false;
          _pingTimer?.cancel();
          _connectionController.add(false);
          _scheduleReconnect();
        },
        onError: (_) {
          _connected = false;
          _pingTimer?.cancel();
          _connectionController.add(false);
          _scheduleReconnect();
        },
      );
    } catch (_) {
      _connectionController.add(false);
      _scheduleReconnect();
    }
  }

  /// Send a periodic ping to keep the ngrok tunnel alive.
  /// Ngrok drops idle WebSocket connections after ~60 seconds of inactivity.
  void _startPing() {
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (_disposed || !_connected) return;
      try {
        _channel?.sink.add('{"ping":true}');
      } catch (_) {
        // Sink is dead — trigger reconnect
        _connected = false;
        _pingTimer?.cancel();
        _connectionController.add(false);
        _scheduleReconnect();
      }
    });
  }

  void _scheduleReconnect() {
    if (_disposed) return;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), connect);
  }

  /// Close the WebSocket connection and release resources.
  void dispose() {
    _disposed = true;
    _reconnectTimer?.cancel();
    _pingTimer?.cancel();
    _channel?.sink.close();
    _eventController.close();
    _connectionController.close();
  }
}
