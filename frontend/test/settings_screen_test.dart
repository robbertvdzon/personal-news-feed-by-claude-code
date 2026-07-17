import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:personal_news_feed/api/api_client.dart';
import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/auth_provider.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/settings_screen.dart';

/// Auth-fake zodat we in-/uitgelogd + admin/niet-admin kunnen forceren
/// zonder netwerk. De echte [AuthNotifier] leest anders SharedPreferences.
class _FakeAuthNotifier extends AuthNotifier {
  _FakeAuthNotifier(super.api, AuthState initial) {
    state = initial;
  }
}

/// Legt vast met welke waarde [setLarge] wordt aangeroepen zodat we de
/// switch-logica kunnen verifiëren zonder SharedPreferences te muteren.
class _FakeAppearanceNotifier extends AppearanceNotifier {
  bool? lastSet;

  @override
  Future<void> setLarge(bool v) async {
    lastSet = v;
    state = AppearanceState(largeFont: v);
  }
}

/// Voorkomt dat [RequestNotifier.build] een echte WebSocket/HTTP-call doet.
class _FakeRequestNotifier extends RequestNotifier {
  @override
  Future<List<NewsRequest>> build() async => const [];
}

Widget _wrap({
  required bool isAdmin,
  _FakeAppearanceNotifier? appearance,
}) {
  final auth = AuthState(
    token: 'test-token',
    username: 'tester',
    role: isAdmin ? 'admin' : 'user',
  );
  return ProviderScope(
    overrides: [
      authProvider.overrideWith((ref) => _FakeAuthNotifier(ApiClient(), auth)),
      appearanceProvider
          .overrideWith((ref) => appearance ?? _FakeAppearanceNotifier()),
      requestProvider.overrideWith(_FakeRequestNotifier.new),
    ],
    child: const MaterialApp(home: SettingsScreen()),
  );
}

void main() {
  setUp(() {
    // AppearanceNotifier + RequestNotifier fallbacks lezen SharedPreferences.
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('Weergave-sectie staat onderaan, ná Debug (niet-admin)', (tester) async {
    // Ruim genoeg canvas zodat de hele ListView in beeld past en we posities
    // van secties direct kunnen vergelijken.
    tester.view.physicalSize = const Size(1400, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(_wrap(isAdmin: false));
    await tester.pumpAndSettle();

    expect(find.text('Weergave'), findsOneWidget);
    expect(find.text('Grote tekst'), findsOneWidget);

    // Weergave staat visueel ónder Debug (grotere dy = lager op het scherm).
    final debugDy = tester.getTopLeft(find.text('Debug')).dy;
    final weergaveDy = tester.getTopLeft(find.text('Weergave')).dy;
    expect(weergaveDy, greaterThan(debugDy));

    // Er is geen aparte Weergave-sectie meer tussen Account en Categorieën:
    // Account staat boven Categorieën, Weergave onder beide.
    final accountDy = tester.getTopLeft(find.text('Account Settings')).dy;
    final categoriesDy = tester.getTopLeft(find.text('Categorieën').first).dy;
    expect(accountDy, lessThan(categoriesDy));
    expect(weergaveDy, greaterThan(categoriesDy));
  });

  testWidgets('Weergave-sectie staat ook ná de admin-only Beheer-sectie', (tester) async {
    tester.view.physicalSize = const Size(1400, 3600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(_wrap(isAdmin: true));
    await tester.pumpAndSettle();

    expect(find.text('Beheer'), findsOneWidget);
    expect(find.text('Weergave'), findsOneWidget);

    final beheerDy = tester.getTopLeft(find.text('Beheer')).dy;
    final weergaveDy = tester.getTopLeft(find.text('Weergave')).dy;
    expect(weergaveDy, greaterThan(beheerDy));
  });

  testWidgets('Grote tekst-switch roept setLarge aan met nieuwe waarde', (tester) async {
    tester.view.physicalSize = const Size(1400, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final appearance = _FakeAppearanceNotifier();
    await tester.pumpWidget(_wrap(isAdmin: false, appearance: appearance));
    await tester.pumpAndSettle();

    expect(find.byType(SwitchListTile), findsOneWidget);
    await tester.tap(find.byType(SwitchListTile));
    await tester.pumpAndSettle();

    expect(appearance.lastSet, isTrue);
  });
}
