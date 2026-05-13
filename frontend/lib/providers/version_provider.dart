import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/version_client.dart';
import 'auth_provider.dart';

/// Gedeelde state voor versie-detectie.
///
/// - `frontend` komt uit de bundel (compile-time --dart-define), is dus
///   altijd bekend zodra de app draait.
/// - `backend` wordt gevuld door `/api/version` en door het WebSocket
///   `serverVersion`-bericht. Bij een fout blijft hij `null` — Settings
///   toont dan "onbekend".
/// - `currentBackendBundleSha` is de SHA waar deze tab tegen geladen is.
///   Tijdens de eerste succesvolle versie-check wordt die vastgelegd; als
///   later een afwijkende `backend.sha` binnenkomt mag de UI er níet
///   automatisch op meeschakelen (anders zou de banner direct verdwijnen
///   bij een reload van Settings). Mismatch detectie gebeurt tegen deze
///   waarde.
@immutable
class VersionState {
  final VersionInfo frontend;
  final VersionInfo? backend;
  final String? bundleBackendSha;
  final bool updateAvailable;
  const VersionState({
    required this.frontend,
    this.backend,
    this.bundleBackendSha,
    this.updateAvailable = false,
  });

  VersionState copyWith({
    VersionInfo? backend,
    String? bundleBackendSha,
    bool? updateAvailable,
  }) =>
      VersionState(
        frontend: frontend,
        backend: backend ?? this.backend,
        bundleBackendSha: bundleBackendSha ?? this.bundleBackendSha,
        updateAvailable: updateAvailable ?? this.updateAvailable,
      );
}

class VersionNotifier extends StateNotifier<VersionState> {
  final VersionClient _client;
  VersionNotifier(this._client)
      : super(VersionState(frontend: FrontendVersion.info));

  /// Trigger een check via REST. Stil falen bij netwerkfouten — de
  /// volgende focus of WS-reconnect probeert het opnieuw.
  Future<void> check() async {
    try {
      final v = await _client.fetch();
      _apply(v);
    } catch (_) {
      // offline / 5xx — Settings toont "onbekend" zolang er geen WS- of
      // REST-respons is binnengekomen.
    }
  }

  /// Update de bekende backend-versie op basis van het WebSocket-bericht
  /// `serverVersion`. Identiek effect als `check()` bij een geslaagde
  /// REST-call.
  void applyServerVersion(Map<String, dynamic> payload) {
    _apply(VersionInfo.fromJson(payload));
  }

  void _apply(VersionInfo backend) {
    // Eerste binnengekomen versie = de versie waar deze tab "tegen
    // geladen" is. Dat is het anker voor mismatch-detectie. (Frontend-
    // SHA zelf is uit de bundel, maar de backend-SHA waar we tegen
    // werken hangt af van de tijd van de eerste succesvolle call.)
    final bundleSha = state.bundleBackendSha ?? backend.sha;
    final mismatch = backend.sha != 'unknown' &&
        bundleSha != 'unknown' &&
        backend.sha != bundleSha;
    state = state.copyWith(
      backend: backend,
      bundleBackendSha: bundleSha,
      updateAvailable: mismatch,
    );
  }
}

final versionClientProvider = Provider<VersionClient>(
  (ref) => VersionClient(ref.read(apiProvider)),
);

final versionProvider =
    StateNotifierProvider<VersionNotifier, VersionState>((ref) {
  return VersionNotifier(ref.read(versionClientProvider));
});
