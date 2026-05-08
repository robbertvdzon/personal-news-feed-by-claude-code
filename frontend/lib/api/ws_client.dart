import 'dart:async';
import 'dart:convert';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'api_client.dart';

class RequestsWebSocket {
  WebSocketChannel? _channel;
  StreamController<Map<String, dynamic>>? _controller;
  Timer? _reconnectTimer;
  bool _stopped = false;

  Stream<Map<String, dynamic>> connect() {
    _controller = StreamController<Map<String, dynamic>>.broadcast();
    _open();
    return _controller!.stream;
  }

  void _open() {
    if (_stopped) return;
    final wsUrl = ApiClient.baseUrl
            .replaceFirst(RegExp(r'^http'), 'ws') +
        '/ws/requests';
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
