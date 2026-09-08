import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/api/api_client.dart';
import 'package:personal_news_feed/providers/auth_provider.dart';
import 'package:personal_news_feed/screens/admin_screen.dart';

/// Auth-fake zodat de ingelogde gebruikersnaam vaststaat zonder netwerk of
/// SharedPreferences — `AdminScreen` leidt daar `isSelf` uit af.
/// Precedent: `settings_screen_test.dart`.
class _FakeAuthNotifier extends AuthNotifier {
  _FakeAuthNotifier(super.api, AuthState initial) {
    state = initial;
  }
}

/// SF-2242: fake-notifier die het faalpad van de backend nabootst — de
/// beheeractie gooit een [ApiException] met een foutbody zoals
/// `GlobalExceptionHandler` die serialiseert (`{"error": "..."}`).
class _FailingAdminUsersNotifier extends AdminUsersNotifier {
  _FailingAdminUsersNotifier(this._statusCode, this._body);
  final int _statusCode;
  final String _body;

  @override
  Future<List<AdminUser>> build() async => [
        AdminUser(id: '1', username: 'admin1', role: 'admin'),
        AdminUser(id: '2', username: 'bob', role: 'user'),
      ];

  @override
  Future<void> delete(String username) async => throw ApiException(_statusCode, _body);
}

Widget _wrap(int statusCode, String body) {
  const auth = AuthState(token: 'test-token', username: 'admin1', role: 'admin');
  return ProviderScope(
    overrides: [
      authProvider.overrideWith((ref) => _FakeAuthNotifier(ApiClient(), auth)),
      adminUsersProvider
          .overrideWith(() => _FailingAdminUsersNotifier(statusCode, body)),
    ],
    child: const MaterialApp(home: AdminScreen()),
  );
}

/// Doorloopt de UI-flow "menu openen → Verwijderen → bevestigen" voor de
/// gebruiker `bob` — bewust een ánder account dan de ingelogde admin, want
/// bij `isSelf` verbergt het scherm het menu-item `delete`.
Future<void> _deleteBob(WidgetTester tester, int statusCode, String body) async {
  await tester.pumpWidget(_wrap(statusCode, body));
  await tester.pumpAndSettle();

  await tester.tap(find.byType(PopupMenuButton<String>).last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Verwijderen'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Doorgaan'));
  await tester.pumpAndSettle();
}

void main() {
  // Let op: deze tests dekken de wéérgave van de servermelding, niet de
  // bereikbaarheid ervan. De 400-melding "Je kunt jezelf niet verwijderen"
  // is via de echte UI onbereikbaar (het scherm verbergt `delete` bij
  // `isSelf`); het faalpad wordt hier via een falende fake-notifier op een
  // ander account gedreven.
  testWidgets('ApiException toont de Nederlandse servermelding, niet de rauwe JSON',
      (tester) async {
    await _deleteBob(tester, 400, '{"error":"Je kunt jezelf niet verwijderen"}');

    expect(find.text('Je kunt jezelf niet verwijderen'), findsOneWidget);
    // Negatieve asserties: geen rauwe JSON en geen statuscode in beeld.
    expect(find.textContaining('{"error"'), findsNothing);
    expect(find.textContaining('400'), findsNothing);
    expect(find.textContaining('Fout:'), findsNothing);
  });

  testWidgets('Lege foutbody toont de Nederlandse fallbacktekst', (tester) async {
    await _deleteBob(tester, 500, '');

    expect(find.text('Actie mislukt'), findsOneWidget);
  });

  testWidgets('Foutbody zonder error-veld toont de body zelf', (tester) async {
    await _deleteBob(tester, 404, 'User not found: bob');

    expect(find.text('User not found: bob'), findsOneWidget);
  });
}
