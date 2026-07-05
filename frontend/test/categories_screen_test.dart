import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/categories_screen.dart';

/// Fake-notifier voor [settingsProvider]: levert vaste data zonder netwerk
/// en houdt opgeslagen waarden lokaal bij zodat toggle/add/edit/delete
/// getest kan worden.
class _FakeSettingsNotifier extends SettingsNotifier {
  _FakeSettingsNotifier(this._initial);
  final List<CategorySettings> _initial;
  List<CategorySettings>? lastSaved;

  @override
  Future<List<CategorySettings>> build() async => _initial;

  @override
  Future<void> save(List<CategorySettings> cats) async {
    lastSaved = cats;
    state = AsyncData(cats);
  }
}

Widget _wrap(_FakeSettingsNotifier settings) {
  return ProviderScope(
    overrides: [settingsProvider.overrideWith(() => settings)],
    child: const MaterialApp(home: CategoriesScreen()),
  );
}

void main() {
  testWidgets('toont AppBar-titel en de categorieënlijst', (tester) async {
    await tester.pumpWidget(_wrap(_FakeSettingsNotifier([
      CategorySettings(id: 'tech', name: 'Tech'),
      CategorySettings(id: 'algemeen', name: 'Algemeen', isSystem: true),
    ])));
    await tester.pumpAndSettle();

    expect(find.text('Categorieën'), findsWidgets); // AppBar-titel
    expect(find.text('Tech'), findsOneWidget);
    expect(find.text('Algemeen'), findsOneWidget);
    expect(find.text('Categorie toevoegen'), findsOneWidget);
  });

  testWidgets('systeemcategorie toont "Systeem" en heeft geen edit-knop', (tester) async {
    await tester.pumpWidget(_wrap(_FakeSettingsNotifier([
      CategorySettings(id: 'tech', name: 'Tech'),
      CategorySettings(id: 'algemeen', name: 'Algemeen', isSystem: true),
    ])));
    await tester.pumpAndSettle();

    expect(find.text('Systeem'), findsOneWidget);
    // Alleen de niet-systeemcategorie (Tech) heeft een edit-knop.
    expect(find.byIcon(Icons.edit), findsOneWidget);
  });

  testWidgets('toggle slaat gewijzigde enabled-waarde op', (tester) async {
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

    expect(settings.lastSaved, isEmpty);
  });
}
