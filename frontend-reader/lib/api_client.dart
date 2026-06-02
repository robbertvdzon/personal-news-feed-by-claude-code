import 'dart:convert';
import 'package:http/http.dart' as http;
import 'models.dart';

/// Minimale, read-only API-client. Praat alleen met het publieke
/// `GET /api/shared/feed` endpoint — geen auth, geen schrijf-acties.
class ApiClient {
  /// Op web leeg → relatieve calls (`/api/...`), nginx proxy't door.
  /// In de APK wordt dit via --dart-define op de prod-hostname gezet.
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );

  Future<List<FeedItem>> fetchFeed() async {
    final url = '$baseUrl/api/shared/feed';
    final resp = await http.get(Uri.parse(url));
    if (resp.statusCode >= 400) {
      throw Exception('Server gaf ${resp.statusCode} terug');
    }
    final data = jsonDecode(resp.body) as List<dynamic>;
    return data.map((e) => FeedItem.fromJson(e as Map<String, dynamic>)).toList();
  }
}
