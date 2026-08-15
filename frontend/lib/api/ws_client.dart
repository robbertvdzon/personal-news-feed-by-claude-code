import 'dart:async';
import 'dart:convert';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'api_client.dart';

/// Bouwt de URL voor `/ws/requests` met het JWT als queryparameter.
///
/// De backend authenticeert de handshake (`JwtHandshakeInterceptor`) en
/// levert statusupdates alleen aan de eigenaar; zonder token wordt de
/// handshake met 401 geweigerd. Een browser-WebSocket kan geen
/// `Authorization`-header zetten, vandaar de queryparameter — hetzelfde
/// patroon als het audio-endpoint. Geeft `null` als er geen token is: dan
/// heeft verbinden geen zin.
String? requestsWsUrl(String? token) {
  if (token == null || token.isEmpty) return null;
  final base = ApiClient.baseUrl.replaceFirst(RegExp(r'^http'), 'ws');
  return '$base/ws/requests?token=${Uri.encodeQueryComponent(token)}';
}

class RequestsWebSocket {
  WebSocketChannel? _channel;
  StreamController<Map<String, dynamic>>? _controller;
  Timer? _reconnectTimer;
  bool _stopped = false;
  String? _token;

  /// Verbindt met het geauthenticeerde `/ws/requests`. Zonder [token] wordt
  /// er niet verbonden (de handshake zou toch met 401 worden geweigerd).
  Stream<Map<String, dynamic>> connect(String? token) {
    _token = token;
    _controller = StreamController<Map<String, dynamic>>.broadcast();
    _open();
    return _controller!.stream;
  }

  void _open() {
    if (_stopped) return;
    // Ook elke reconnect gebruikt het token waarmee deze socket is opgezet.
    final wsUrl = requestsWsUrl(_token);
    if (wsUrl == null) return;
    try {
      _channel = WebSocketChannel.connect(Uri.parse(wsUrl));
      _channel!.stream.listen(
        (msg) {
          if (msg is String) {
            try {
              final json = jsonDecode(msg) as Map<String, dynamic>;
              _controller?.add(json);
            } catch (_) {}
          }
        },
        onDone: _scheduleReconnect,
        onError: (_) => _scheduleReconnect(),
        cancelOnError: true,
      );
    } catch (_) {
      _scheduleReconnect();
    }
  }

  void _scheduleReconnect() {
    if (_stopped) return;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), _open);
  }

  void close() {
    _stopped = true;
    _reconnectTimer?.cancel();
    _channel?.sink.close();
    _controller?.close();
  }
}
