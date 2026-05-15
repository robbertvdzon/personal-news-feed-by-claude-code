import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../api/api_client.dart';

final apiProvider = Provider<ApiClient>((ref) => ApiClient());

class AuthState {
  final String? token;
  final int? expiresAt;
  final String? error;
  final bool loading;
  const AuthState({this.token, this.expiresAt, this.error, this.loading = false});
  bool get isAuthenticated => token != null && token!.isNotEmpty;
}

class AuthNotifier extends StateNotifier<AuthState> {
  final ApiClient api;
  AuthNotifier(this.api) : super(const AuthState());

  Future<void> bootstrap() async {
    final prefs = await SharedPreferences.getInstance();
    final t = prefs.getString('jwt');
    final exp = prefs.getInt('jwt_exp');
    if (t != null && exp != null && exp > DateTime.now().millisecondsSinceEpoch ~/ 1000) {
      api.setToken(t);
      state = AuthState(token: t, expiresAt: exp);
    }
  }

  Future<void> login(String username, String password) async {
    state = AuthState(loading: true);
    try {
      final resp = await api.login(username, password);
      final token = resp['token'] as String;
      final exp = (resp['expires_at'] as num).toInt();
      api.setToken(token);
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('jwt', token);
      await prefs.setInt('jwt_exp', exp);
      state = AuthState(token: token, expiresAt: exp);
    } on ApiException catch (e) {
      state = AuthState(
        error: e.statusCode == 401 ? 'Ongeldige inloggegevens' : 'Fout: ${e.statusCode}',
      );
    } catch (e) {
      state = AuthState(error: 'Netwerkfout: $e');
    }
  }

  Future<void> logout() async {
    api.setToken(null);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('jwt');
    await prefs.remove('jwt_exp');
    state = const AuthState();
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>(
  (ref) => AuthNotifier(ref.read(apiProvider)),
);
