import 'dart:convert';
import 'package:http/http.dart' as http;

class ApiException implements Exception {
  final int statusCode;
  final String body;
  ApiException(this.statusCode, this.body);
  @override
  String toString() => 'ApiException($statusCode): $body';
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

  Future<dynamic> _send(Future<http.Response> Function() fn) async {
    final resp = await fn();
    if (resp.statusCode >= 400) throw ApiException(resp.statusCode, resp.body);
    if (resp.body.isEmpty) return null;
    return jsonDecode(resp.body);
  }

  Future<dynamic> get(String path) =>
      _send(() => http.get(Uri.parse('$baseUrl$path'), headers: _headers()));

  Future<dynamic> post(String path, [Object? body]) => _send(() => http.post(
        Uri.parse('$baseUrl$path'),
        headers: _headers(),
        body: body == null ? null : jsonEncode(body),
      ));

  Future<dynamic> put(String path, [Object? body]) => _send(() => http.put(
        Uri.parse('$baseUrl$path'),
        headers: _headers(),
        body: body == null ? null : jsonEncode(body),
      ));

  Future<dynamic> delete(String path) =>
      _send(() => http.delete(Uri.parse('$baseUrl$path'), headers: _headers()));
}
