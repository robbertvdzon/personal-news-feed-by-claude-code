import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../api/api_client.dart';
import '../api/local_cache.dart';
import 'data_providers.dart';

final apiProvider = Provider<ApiClient>((ref) => ApiClient());

class AuthState {
  final String? token;
  final String? username;
  final String? role; // 'user' of 'admin'
  final String? error;
  const AuthState({this.token, this.username, this.role, this.error});
  bool get isLoggedIn => token != null;
  bool get isAdmin => role == 'admin';
}

class AuthNotifier extends StateNotifier<AuthState> {
  final ApiClient api;

  /// Optioneel: alleen nodig om bij uitloggen [requestProvider] te
  /// invalideren. Tests die de notifier los construeren laten hem weg.
  final Ref? _ref;

  AuthNotifier(this.api, {Ref? ref})
      : _ref = ref,
        super(const AuthState());

  Future<void> bootstrap() async {
    final prefs = await SharedPreferences.getInstance();
    final t = prefs.getString('token');
    final u = prefs.getString('username');
    final r = prefs.getString('role');
    if (t != null && u != null) {
      api.setToken(t);
      state = AuthState(token: t, username: u, role: r ?? 'user');
    }
  }

  Future<void> login(String u, String p) => _auth('/api/auth/login', u, p);
  Future<void> register(String u, String p) => _auth('/api/auth/register', u, p);

  Future<void> _auth(String path, String u, String p) async {
    try {
      final resp = await api.post(path, {'username': u, 'password': p}) as Map<String, dynamic>;
      final token = resp['token'] as String;
      final username = resp['username'] as String;
      // Backwards-compat: oudere backends sturen geen 'role'-veld; default
      // dan op 'user' zodat de UI niet crasht.
      final role = (resp['role'] as String?) ?? 'user';
      api.setToken(token);
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('token', token);
      await prefs.setString('username', username);
      await prefs.setString('role', role);
      state = AuthState(token: token, username: username, role: role);
    } on ApiException catch (e) {
      state = AuthState(error: 'Inloggen mislukt (${e.statusCode})');
    } catch (e) {
      state = AuthState(error: 'Inloggen mislukt: $e');
    }
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
    // Sluit de WebSocket van de uitgelogde gebruiker: requestProvider is
    // niet autoDispose, dus zonder deze invalidate blijft de socket van A
    // luisteren terwijl B inlogt. Bij de volgende login wordt hij opnieuw
    // opgezet met het token van de dán ingelogde gebruiker.
    _ref?.invalidate(requestProvider);
    state = const AuthState();
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>(
  (ref) => AuthNotifier(ref.read(apiProvider), ref: ref),
);
