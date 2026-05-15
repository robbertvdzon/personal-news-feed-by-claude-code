import 'dart:convert';
import 'package:http/http.dart' as http;
import 'models.dart';

class ApiException implements Exception {
  final int statusCode;
  final String body;
  final String url;
  ApiException(this.statusCode, this.body, [this.url = '']);
  @override
  String toString() => 'ApiException($statusCode) $url: $body';
}

class ApiClient {
  /// Lege string = relatieve URL's, nginx in dezelfde container proxy't
  /// /api/* door naar de status-dashboard service.
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: '',
  );

  String? _token;
  String? get token => _token;

  void setToken(String? t) {
    _token = t;
  }

  Map<String, String> _headers([Map<String, String>? extra]) => {
        'Content-Type': 'application/json',
        if (_token != null) 'Authorization': 'Bearer $_token',
        ...?extra,
      };

  Uri _u(String path) => Uri.parse('$baseUrl$path');

  Future<dynamic> _send(String method, String path,
      {dynamic body, Duration timeout = const Duration(seconds: 15)}) async {
    final url = _u(path);
    http.Response resp;
    try {
      if (method == 'GET') {
        resp = await http.get(url, headers: _headers()).timeout(timeout);
      } else if (method == 'POST') {
        resp = await http
            .post(url, headers: _headers(), body: jsonEncode(body ?? {}))
            .timeout(timeout);
      } else {
        throw UnsupportedError('method $method');
      }
    } catch (e) {
      throw ApiException(-1, e.toString(), url.toString());
    }
    if (resp.statusCode >= 400) {
      throw ApiException(resp.statusCode, resp.body, url.toString());
    }
    if (resp.body.isEmpty) return null;
    return jsonDecode(resp.body);
  }

  Future<Map<String, dynamic>> login(String username, String password) async {
    final r = await _send('POST', '/api/v1/auth/login',
        body: {'username': username, 'password': password});
    return Map<String, dynamic>.from(r as Map);
  }

  Future<HomeState> state() async {
    final r = await _send('GET', '/api/v1/state');
    return HomeState.fromJson(Map<String, dynamic>.from(r as Map));
  }

  Future<List<StoryRow>> stories() async {
    final r = await _send('GET', '/api/v1/stories');
    return ((r as Map)['stories'] as List)
        .map((s) => StoryRow.fromJson(Map<String, dynamic>.from(s as Map)))
        .toList();
  }

  Future<StoryDetail> storyDetail(String key) async {
    final r = await _send('GET', '/api/v1/stories/$key');
    return StoryDetail.fromJson(Map<String, dynamic>.from(r as Map));
  }

  Future<HandoverData> storyHandover(String key) async {
    final r = await _send('GET', '/api/v1/stories/$key/handover');
    return HandoverData.fromJson(Map<String, dynamic>.from(r as Map));
  }

  Future<void> sendCommand(String key, String command) async {
    await _send('POST', '/api/v1/stories/$key/cmd/$command');
  }

  Future<Map<String, dynamic>> runnerLog(String jobName) async {
    final r = await _send('GET', '/api/v1/runner/$jobName/log');
    return Map<String, dynamic>.from(r as Map);
  }
}
