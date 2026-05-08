import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../api/api_client.dart';

final apiProvider = Provider<ApiClient>((ref) => ApiClient());

class AuthState {
  final String? token;
  final String? username;
  final String? error;
  const AuthState({this.token, this.username, this.error});
  bool get isLoggedIn => token != null;
}

class AuthNotifier extends StateNotifier<AuthState> {
  final ApiClient api;
  AuthNotifier(this.api) : super(const AuthState());

  Future<void> bootstrap() async {
    final prefs = await SharedPreferences.getInstance();
    final t = prefs.getString('token');
    final u = prefs.getString('username');
    if (t != null && u != null) {
      api.setToken(t);
      state = AuthState(token: t, username: u);
    }
  }

  Future<void> login(String u, String p) => _auth('/api/auth/login', u, p);
  Future<void> register(String u, String p) => _auth('/api/auth/register', u, p);

  Future<void> _auth(String path, String u, String p) async {
    try {
      final resp = await api.post(path, {'username': u, 'password': p}) as Map<String, dynamic>;
      final token = resp['token'] as String;
      final username = resp['username'] as String;
      api.setToken(token);
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('token', token);
      await prefs.setString('username', username);
      state = AuthState(token: token, username: username);
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
    api.setToken(null);
    state = const AuthState();
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>(
  (ref) => AuthNotifier(ref.read(apiProvider)),
);
