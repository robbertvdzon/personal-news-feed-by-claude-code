import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/api/api_client.dart';
import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/categories_screen.dart';

/// Fake-notifier voor [settingsProvider]: levert vaste categorieën zonder
/// netwerk en houdt de laatst opgeslagen lijst lokaal bij zodat toggle,
/// toevoegen, bewerken en verwijderen getest kunnen worden.
class _FakeSettingsNotifier extends SettingsNotifier {
  _FakeSettingsNotifier(this._initial);
  final List<CategorySettings> _initial;
  List<CategorySettings>? lastSaved;

  @override
  Future<List<CategorySettings>> build() async => _initial;

  @override
  Future<void> save(List<CategorySettings> categories) async {
    lastSaved = categories;
    state = AsyncData(categories);
  }
}

/// SF-1851: fake-notifier die het faalpad van de backend nabootst — een
/// HTTP 400 met een Nederlandse foutmelding in het `error`-veld. De state
/// wordt bewust níét gemuteerd, net als de echte [SettingsNotifier.save].
class _FailingSettingsNotifier extends SettingsNotifier {
  _FailingSettingsNotifier(this._initial, this._body);
  final List<CategorySettings> _initial;
  final String _body;
  int saveCalls = 0;

  @override
  Future<List<CategorySettings>> build() async => _initial;

  @override
  Future<void> save(List<CategorySettings> categories) async {
    saveCalls++;
    throw ApiException(400, _body);
  }
}

/// SF-1851: fake-notifier waarvan de save pas afrondt als de meegegeven
/// future compleet is — zo is de busy-state observeerbaar in de widgettest.
class _SlowSettingsNotifier extends SettingsNotifier {
  _SlowSettingsNotifier(this._initial, this._gate);
  final List<CategorySettings> _initial;
  final Future<void> _gate;

  @override
  Future<List<CategorySettings>> build() async => _initial;

  @override
  Future<void> save(List<CategorySettings> categories) async {
    await _gate;
    state = AsyncData(categories);
  }
}

Widget _wrap(SettingsNotifier settings) {
  return ProviderScope(
    overrides: [settingsProvider.overrideWith(() => settings)],
    child: const MaterialApp(home: CategoriesScreen()),
  );
}

void main() {
  testWidgets('toont AppBar-titel en categorielijst', (tester) async {
    await tester.pumpWidget(_wrap(_FakeSettingsNotifier([
      CategorySettings(id: 'tech', name: 'Tech'),
      CategorySettings(id: 'algemeen', name: 'Algemeen', isSystem: true),
    ])));
    await tester.pumpAndSettle();

    expect(find.text('Categorieën'), findsWidgets);
    expect(find.text('Tech'), findsOneWidget);
    expect(find.text('Algemeen'), findsOneWidget);
    expect(find.text('Categorie toevoegen'), findsOneWidget);
  });

  testWidgets('systeemcategorie toont subtitel "Systeem" en heeft geen edit-knop', (tester) async {
    await tester.pumpWidget(_wrap(_FakeSettingsNotifier([
      CategorySettings(id: 'tech', name: 'Tech'),
      CategorySettings(id: 'algemeen', name: 'Algemeen', isSystem: true),
    ])));
    await tester.pumpAndSettle();

    expect(find.text('Systeem'), findsOneWidget);
    // Precies één niet-systeemcategorie → precies één edit-knop.
    expect(find.byIcon(Icons.edit), findsOneWidget);
  });

  testWidgets('categorie in-/uitschakelen roept save aan met gewijzigde waarde', (tester) async {
    final settings = _FakeSettingsNotifier([
      CategorySettings(id: 'tech', name: 'Tech', enabled: true),
    ]);
    await tester.pumpWidget(_wrap(settings));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();

    expect(settings.lastSaved, isNotNull);
    expect(settings.lastSaved!.single.enabled, isFalse);
  });

  testWidgets('categorie toevoegen roept save aan met nieuwe categorie', (tester) async {
    final settings = _FakeSettingsNotifier(const []);
    await tester.pumpWidget(_wrap(settings));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Categorie toevoegen'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, 'Sport');
    await tester.tap(find.text('Opslaan'));
    await tester.pumpAndSettle();

    expect(settings.lastSaved, isNotNull);
    expect(settings.lastSaved!.single.name, 'Sport');
    expect(settings.lastSaved!.single.id, 'sport');
  });

  testWidgets('niet-systeemcategorie verwijderen roept save aan met lege lijst', (tester) async {
    final settings = _FakeSettingsNotifier([
      CategorySettings(id: 'tech', name: 'Tech'),
    ]);
    await tester.pumpWidget(_wrap(settings));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.edit).first);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Verwijderen'));
    await tester.pumpAndSettle();

    expect(settings.lastSaved, isNotNull);
    expect(settings.lastSaved, isEmpty);
  });

  testWidgets('schakelaar: 400 laat de lijst ongewijzigd en toont de servertekst',
      (tester) async {
    const serverMsg = 'Opslaan mislukt: categorieën niet geldig';
    final settings = _FailingSettingsNotifier(
      [CategorySettings(id: 'tech', name: 'Tech', enabled: true)],
      '{"error":"$serverMsg"}',
    );
    await tester.pumpWidget(_wrap(settings));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();

    expect(settings.saveCalls, 1);
    // Snackbar toont de Nederlandse servertekst, niet de rauwe JSON.
    expect(find.text(serverMsg), findsOneWidget);
    expect(find.textContaining('"error"'), findsNothing);
    // De schakelaar staat nog steeds aan: de state is niet gemuteerd.
    expect(tester.widget<Switch>(find.byType(Switch).first).value, isTrue);
    expect(find.text('Tech'), findsOneWidget);
  });

  testWidgets('verwijderen: 400 laat de categorie in de lijst staan', (tester) async {
    const serverMsg = 'Categorie kon niet verwijderd worden';
    final settings = _FailingSettingsNotifier(
      [CategorySettings(id: 'tech', name: 'Tech')],
      '{"error":"$serverMsg"}',
    );
    await tester.pumpWidget(_wrap(settings));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.edit).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Verwijderen'));
    await tester.pumpAndSettle();

    expect(settings.saveCalls, 1);
    expect(find.text(serverMsg), findsOneWidget);
    expect(find.text('Tech'), findsOneWidget);
  });

  testWidgets('bediening is uitgeschakeld tijdens het opslaan', (tester) async {
    final completer = Completer<void>();
    final settings = _SlowSettingsNotifier(
      [CategorySettings(id: 'tech', name: 'Tech', enabled: true)],
      completer.future,
    );
    await tester.pumpWidget(_wrap(settings));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch).first);
    await tester.pump();

    expect(tester.widget<Switch>(find.byType(Switch).first).onChanged, isNull);
    expect(tester.widget<IconButton>(find.byType(IconButton).first).onPressed, isNull);
    expect(
      tester.widget<ListTile>(find.widgetWithText(ListTile, 'Categorie toevoegen')).onTap,
      isNull,
    );

    completer.complete();
    await tester.pumpAndSettle();

    expect(tester.widget<Switch>(find.byType(Switch).first).onChanged, isNotNull);
    expect(tester.widget<Switch>(find.byType(Switch).first).value, isFalse);
  });
}
