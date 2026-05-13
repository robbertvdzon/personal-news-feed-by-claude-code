import 'api_client.dart';

/// Versie-informatie die door zowel `/api/version` als het
/// WebSocket-bericht `serverVersion` wordt aangeleverd.
class VersionInfo {
  final String sha;
  final String buildTime;
  const VersionInfo({required this.sha, required this.buildTime});

  factory VersionInfo.fromJson(Map<String, dynamic> json) => VersionInfo(
        sha: (json['sha'] ?? json['gitSha'] ?? 'unknown').toString(),
        buildTime: (json['buildTime'] ?? 'unknown').toString(),
      );

  static const unknown = VersionInfo(sha: 'unknown', buildTime: 'unknown');
}

/// Compile-time geinjecteerde frontend-versie (via --dart-define).
/// Gegarandeerd beschikbaar — geen netwerk-call nodig.
class FrontendVersion {
  static const String sha =
      String.fromEnvironment('BUILD_SHA', defaultValue: 'unknown');
  static const String buildTime =
      String.fromEnvironment('BUILD_TIME', defaultValue: 'unknown');
  static const info = VersionInfo(sha: sha, buildTime: buildTime);
}

class VersionClient {
  final ApiClient _api;
  VersionClient(this._api);

  /// Haalt de actueel-gedeployde backend-versie op. Gooit bij netwerk- of
  /// HTTP-fouten zodat de caller kan kiezen om het stil te negeren.
  Future<VersionInfo> fetch() async {
    final r = await _api.get('/api/version') as Map<String, dynamic>;
    return VersionInfo.fromJson(r);
  }
}
