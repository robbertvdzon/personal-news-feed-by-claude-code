import 'dart:convert';
import 'package:http/http.dart' as http;
import 'api_log.dart';

class ApiException implements Exception {
  final int statusCode;
  final String body;
  final String url;
  ApiException(this.statusCode, this.body, [this.url = '']);
  @override
  String toString() => 'ApiException($statusCode) $url: $body';
}

class ApiClient {
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );

  String? _token;
  String? get token => _token;

  void setToken(String? token) {
    _token = token;
  }

  Map<String, String> _headers([Map<String, String>? extra]) => {
        'Content-Type': 'application/json',
        if (_token != null) 'Authorization': 'Bearer $_token',
        ...?extra,
      };

  Future<dynamic> _send(
    String method,
    String url,
    Future<http.Response> Function() fn,
  ) async {
    final t0 = DateTime.now();
    try {
      final resp = await fn();
      final dur = DateTime.now().difference(t0).inMilliseconds;
      ApiLog.instance.add(ApiLogEntry(
        time: t0,
        method: method,
        url: url,
        statusCode: resp.statusCode,
        error: resp.statusCode >= 400 ? resp.body : null,
        durationMs: dur,
      ));
      if (resp.statusCode >= 400) {
        throw ApiException(resp.statusCode, resp.body, url);
      }
      if (resp.body.isEmpty) return null;
      return jsonDecode(resp.body);
    } on ApiException {
      rethrow;
    } catch (e) {
      // Bv. SocketException, TimeoutException, FormatException op decode
      final dur = DateTime.now().difference(t0).inMilliseconds;
      ApiLog.instance.add(ApiLogEntry(
        time: t0,
        method: method,
        url: url,
        error: e.toString(),
        durationMs: dur,
      ));
      rethrow;
    }
  }

  Future<dynamic> get(String path) {
    final url = '$baseUrl$path';
    return _send('GET', url, () => http.get(Uri.parse(url), headers: _headers()));
  }

  Future<dynamic> post(String path, [Object? body]) {
    final url = '$baseUrl$path';
    return _send('POST', url, () => http.post(
          Uri.parse(url),
          headers: _headers(),
          body: body == null ? null : jsonEncode(body),
        ));
  }

  Future<dynamic> put(String path, [Object? body]) {
    final url = '$baseUrl$path';
    return _send('PUT', url, () => http.put(
          Uri.parse(url),
          headers: _headers(),
          body: body == null ? null : jsonEncode(body),
        ));
  }

  Future<dynamic> delete(String path) {
    final url = '$baseUrl$path';
    return _send('DELETE', url, () => http.delete(Uri.parse(url), headers: _headers()));
  }
}
