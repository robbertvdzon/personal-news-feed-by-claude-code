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

  Future<ActiveAgentJob?> activeJob(String key) async {
    final r = await _send('GET', '/api/v1/stories/$key/active-job');
    final m = Map<String, dynamic>.from(r as Map);
    final a = m['active'];
    if (a == null) return null;
    return ActiveAgentJob.fromJson(Map<String, dynamic>.from(a as Map));
  }

  Future<PoQuestion?> poQuestion(String key) async {
    final r = await _send('GET', '/api/v1/stories/$key/po-question');
    final m = Map<String, dynamic>.from(r as Map);
    final q = m['question'];
    if (q == null) return null;
    return PoQuestion.fromJson(Map<String, dynamic>.from(q as Map));
  }

  Future<void> sendPoAnswer(String key, String text) async {
    await _send('POST', '/api/v1/stories/$key/po-answer', body: {'text': text});
  }

  /// Continue (geen value) of BUDGET=value voor budget-paused story.
  /// De poller pikt de comment op binnen 30s.
  Future<void> sendBudgetResume(String key, {int? value}) async {
    final body = value == null ? <String, dynamic>{} : {'value': value};
    await _send('POST', '/api/v1/stories/$key/budget-resume', body: body);
  }

  Future<ApkInfo> apks() async {
    final r = await _send('GET', '/api/v1/apks');
    return ApkInfo.fromJson(Map<String, dynamic>.from(r as Map));
  }

  Future<List<ScreenshotAttachment>> attachments(String key) async {
    final r = await _send('GET', '/api/v1/stories/$key/attachments');
    final m = Map<String, dynamic>.from(r as Map);
    return (m['attachments'] as List? ?? [])
        .map((a) =>
            ScreenshotAttachment.fromJson(Map<String, dynamic>.from(a as Map)))
        .toList();
  }

  /// Volledige URL voor een attachment-raw-endpoint, bruikbaar voor
  /// Image.network. Caller moet zelf de Authorization-header meegeven.
  String attachmentRawUrl(String relPath) => '$baseUrl$relPath';

  Map<String, String> authHeaders() =>
      _token == null ? const {} : {'Authorization': 'Bearer $_token'};
}
