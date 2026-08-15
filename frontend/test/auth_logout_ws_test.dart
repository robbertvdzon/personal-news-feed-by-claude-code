import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/auth_provider.dart';
import 'package:personal_news_feed/providers/data_providers.dart';

/// Telt hoe vaak de provider is opgebouwd; elke build zet in de echte
/// [RequestNotifier] een nieuwe WebSocket op met het huidige token, en elke
/// dispose sluit de vorige.
class _CountingRequestNotifier extends RequestNotifier {
  static int builds = 0;

  @override
  Future<List<NewsRequest>> build() async {
    builds++;
    return const [];
  }
}

/// SF-2166: na uitloggen mag de WebSocket van de vorige gebruiker niet
/// blijven luisteren. [requestProvider] is niet autoDispose, dus
/// `AuthNotifier.logout` invalideert hem expliciet — daarmee sluit de socket
/// en wordt hij bij een volgende login opgezet met het nieuwe token.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    _CountingRequestNotifier.builds = 0;
  });

  test('logout invalideert requestProvider, zodat de websocket opnieuw wordt opgezet', () async {
    final container = ProviderContainer(
      overrides: [requestProvider.overrideWith(_CountingRequestNotifier.new)],
    );
    addTearDown(container.dispose);

    await container.read(requestProvider.future);
    expect(_CountingRequestNotifier.builds, 1);

    await container.read(authProvider.notifier).logout();

    // De volgende lezer (na login) krijgt een verse provider — en dus een
    // verse verbinding met het token van de nu ingelogde gebruiker.
    await container.read(requestProvider.future);
    expect(_CountingRequestNotifier.builds, 2,
        reason: 'requestProvider is bij logout niet ge-invalideerd');
  });

  test('logout wist het token, zodat een nieuwe verbinding niet met het oude token opgaat', () async {
    final container = ProviderContainer(
      overrides: [requestProvider.overrideWith(_CountingRequestNotifier.new)],
    );
    addTearDown(container.dispose);
    final api = container.read(apiProvider);
    api.setToken('token-van-a');

    await container.read(authProvider.notifier).logout();

    expect(api.token, isNull);
    expect(container.read(authProvider).isLoggedIn, isFalse);
  });
}
