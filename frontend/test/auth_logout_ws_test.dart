import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:personal_news_feed/api/api_client.dart';
import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/auth_provider.dart';
import 'package:personal_news_feed/providers/data_providers.dart';

/// Stand-in zonder WebSocket, voor tests die alleen naar de auth-kant kijken.
class _SilentRequestNotifier extends RequestNotifier {
  @override
  Future<List<NewsRequest>> build() async => const [];
}

/// Praat niet met het netwerk: `login` slaagt met een token dat van de
/// gebruikersnaam is afgeleid en `/api/requests` levert een lege lijst.
class _FakeApiClient extends ApiClient {
  @override
  Future<dynamic> get(String path) async => <dynamic>[];

  @override
  Future<dynamic> post(String path, [Object? body]) async {
    final username = (body as Map<String, dynamic>)['username'] as String;
    return <String, dynamic>{
      'token': 'token-van-$username',
      'username': username,
      'role': 'user',
    };
  }
}

/// SF-2166: na uitloggen mag de WebSocket van de vorige gebruiker niet
/// blijven luisteren, en na een volgende login moet er weer verbonden worden
/// met het token van de dán ingelogde gebruiker.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('zonder ingelogde gebruiker wordt er geen verbinding opgezet', () async {
    final container = ProviderContainer(
      overrides: [apiProvider.overrideWithValue(_FakeApiClient())],
    );
    addTearDown(container.dispose);

    await container.read(requestProvider.future);

    expect(container.read(requestProvider.notifier).connectedToken, isNull);
  });

  test('logout wist het token, zodat een nieuwe verbinding niet met het oude token opgaat', () async {
    final container = ProviderContainer(
      overrides: [requestProvider.overrideWith(_SilentRequestNotifier.new)],
    );
    addTearDown(container.dispose);
    final api = container.read(apiProvider);
    api.setToken('token-van-a');

    await container.read(authProvider.notifier).logout();

    expect(api.token, isNull);
    expect(container.read(authProvider).isLoggedIn, isFalse);
  });

  // Regressietest op de echte RequestNotifier: het instellingenscherm (waar
  // de uitlogknop staat) watcht requestProvider, dus de invalidate in
  // logout leidt meteen tot een rebuild mét een leeg token. De
  // container.listen hieronder bootst dat na — zonder listener is er niets
  // dat eager herbouwt en mist de test het probleem.
  test('na logout en login van een andere gebruiker verbindt de socket met het nieuwe token', () async {
    final container = ProviderContainer(
      overrides: [apiProvider.overrideWithValue(_FakeApiClient())],
    );
    addTearDown(container.dispose);
    container.listen<AsyncValue<List<NewsRequest>>>(requestProvider, (_, __) {});

    Future<String?> settleAndReadToken() async {
      await container.read(requestProvider.future);
      return container.read(requestProvider.notifier).connectedToken;
    }

    await container.read(authProvider.notifier).login('a', 'geheim');
    expect(await settleAndReadToken(), 'token-van-a');

    await container.read(authProvider.notifier).logout();
    expect(await settleAndReadToken(), isNull,
        reason: 'na uitloggen mag er geen verbinding met een token meer zijn');

    await container.read(authProvider.notifier).login('b', 'geheim');
    expect(await settleAndReadToken(), 'token-van-b',
        reason: 'na een gebruikerswissel moet er met het nieuwe token worden verbonden');
  });
}
