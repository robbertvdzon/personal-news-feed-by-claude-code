import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../api/api_client.dart';
import '../api/local_cache.dart';

final apiProvider = Provider<ApiClient>((ref) => ApiClient());

const googleClientId = String.fromEnvironment(
  'GOOGLE_CLIENT_ID',
  defaultValue: '',
);
const skipGoogleAuthPreview = bool.fromEnvironment(
  'SKIP_GOOGLE_AUTH',
  defaultValue: false,
);

class AuthState {
  final String? token;
  final String? username;
  final String? role; // 'user' of 'admin'
  final String? error;
  final bool busy;
  const AuthState({
    this.token,
    this.username,
    this.role,
    this.error,
    this.busy = false,
  });
  bool get isLoggedIn => token != null;
  bool get isAdmin => role == 'admin';
}

class AuthNotifier extends StateNotifier<AuthState> {
  final ApiClient api;
  late final GoogleSignIn googleSignIn;
  StreamSubscription<GoogleSignInAccount?>? _googleAuthSub;

  AuthNotifier(this.api) : super(const AuthState()) {
    googleSignIn = GoogleSignIn(
      clientId: kIsWeb && googleClientId.isNotEmpty ? googleClientId : null,
      serverClientId: !kIsWeb && googleClientId.isNotEmpty
          ? googleClientId
          : null,
      scopes: const ['email'],
    );
    if (kIsWeb) {
      _googleAuthSub = googleSignIn.onCurrentUserChanged.listen(
        handleGoogleAccount,
      );
    }
  }

  Future<void> bootstrap() async {
    if (skipGoogleAuthPreview) {
      api.setToken('preview');
      state = const AuthState(
        token: 'preview',
        username: 'robbert',
        role: 'admin',
      );
      return;
    }
    final prefs = await SharedPreferences.getInstance();
    final t = prefs.getString('token');
    final u = prefs.getString('username');
    final r = prefs.getString('role');
    if (t != null && u != null) {
      api.setToken(t);
      state = AuthState(token: t, username: u, role: r ?? 'user');
    }
  }

  Future<void> handleGoogleAccount(GoogleSignInAccount? account) async {
    if (account == null || state.busy) return;
    await _authenticateGoogleAccount(account);
  }

  Future<void> loginWithGoogle() async {
    state = const AuthState(busy: true);
    try {
      final account = await googleSignIn.signIn();
      if (account == null) {
        state = const AuthState();
        return;
      }
      await _authenticateGoogleAccount(account);
    } catch (e) {
      await _googleError(e);
    }
  }

  Future<void> _authenticateGoogleAccount(GoogleSignInAccount account) async {
    state = const AuthState(busy: true);
    try {
      final authentication = await account.authentication;
      final idToken = authentication.idToken;
      if (idToken == null) {
        throw Exception(
          'Geen Google ID-token ontvangen. Controleer de OAuth-client-ID.',
        );
      }
      final resp =
          await api.post('/api/auth/google', {'idToken': idToken})
              as Map<String, dynamic>;
      final token = resp['token'] as String;
      final username = resp['username'] as String;
      final role = (resp['role'] as String?) ?? 'user';
      await _storeSession(token, username, role);
    } catch (e) {
      await _googleError(e);
    }
  }

  Future<void> _storeSession(String token, String username, String role) async {
    api.setToken(token);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('token', token);
    await prefs.setString('username', username);
    await prefs.setString('role', role);
    state = AuthState(token: token, username: username, role: role);
  }

  Future<void> _googleError(Object error) async {
    await googleSignIn.signOut().catchError((_) => null);
    state = AuthState(error: _loginError(error));
  }

  Future<void> logout() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('token');
    await prefs.remove('username');
    await prefs.remove('role');
    // Wis offline cache van álle users zodat een volgende user op
    // hetzelfde toestel geen oude data ziet bij netwerkproblemen.
    await LocalCache.clearAll();
    api.setToken(null);
    await googleSignIn.signOut().catchError((_) => null);
    state = const AuthState();
  }

  /// Zelfde opruiming als [logout], maar met een zichtbare melding — voor
  /// wanneer een 401/403 op een API-call laat zien dat de sessie niet meer
  /// geldig is. Zonder dit bleef de app stilletjes de laatst gecachete
  /// data tonen alsof er niets aan de hand was.
  Future<void> forceReauth() async {
    if (!state.isLoggedIn) return; // al uitgelogd, niet dubbel triggeren
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('token');
    await prefs.remove('username');
    await prefs.remove('role');
    await LocalCache.clearAll();
    api.setToken(null);
    await googleSignIn.signOut().catchError((_) => null);
    state = const AuthState(error: 'Sessie verlopen — log opnieuw in.');
  }

  @override
  void dispose() {
    _googleAuthSub?.cancel();
    super.dispose();
  }
}

String _loginError(Object error) {
  if (error is ApiException) {
    try {
      final body = jsonDecode(error.body) as Map<String, dynamic>;
      final message = body['message'] ?? body['error'];
      if (message is String && message.isNotEmpty) return message;
    } catch (_) {
      // Gebruik hieronder de HTTP-status als de response geen JSON is.
    }
    return 'Google-login geweigerd (${error.statusCode})';
  }
  return 'Google-login mislukt: $error';
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>(
  (ref) => AuthNotifier(ref.read(apiProvider)),
);
