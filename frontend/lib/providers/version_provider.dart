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
  static const Object _unset = Object();

  final VersionInfo frontend;
  final VersionInfo? backend;
  final String? bundleBackendSha;
  final String? dismissedSha;
  final bool updateAvailable;
  const VersionState({
    required this.frontend,
    this.backend,
    this.bundleBackendSha,
    this.dismissedSha,
    this.updateAvailable = false,
  });

  VersionState copyWith({
    VersionInfo? backend,
    String? bundleBackendSha,
    Object? dismissedSha = _unset,
    bool? updateAvailable,
  }) =>
      VersionState(
        frontend: frontend,
        backend: backend ?? this.backend,
        bundleBackendSha: bundleBackendSha ?? this.bundleBackendSha,
        dismissedSha: dismissedSha == _unset ? this.dismissedSha : dismissedSha as String?,
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
    // Banner tonen als er een mismatch is, maar niet als deze SHA al
    // is genegeerd. Als backend-SHA verandert (nieuwere versie), reset
    // de genegeerde SHA zodat de banner weer verschijnt.
    final shouldShow = mismatch && backend.sha != state.dismissedSha;
    final resetDismissed =
        mismatch && state.dismissedSha != null && backend.sha != state.dismissedSha;
    state = state.copyWith(
      backend: backend,
      bundleBackendSha: bundleSha,
      dismissedSha: resetDismissed ? null : state.dismissedSha,
      updateAvailable: shouldShow,
    );
  }

  /// Markeer huidige backend-versie als genegeerd. De banner verdwijnt
  /// tot er een nieuwere versie binnenkomt.
  void dismiss() {
    if (state.backend != null && state.backend!.sha != 'unknown') {
      state = state.copyWith(
        dismissedSha: state.backend!.sha,
        updateAvailable: false,
      );
    }
  }
}

final versionClientProvider = Provider<VersionClient>(
  (ref) => VersionClient(ref.read(apiProvider)),
);

final versionProvider =
    StateNotifierProvider<VersionNotifier, VersionState>((ref) {
  return VersionNotifier(ref.read(versionClientProvider));
});
