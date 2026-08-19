import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:personal_news_feed_reader/local_store.dart';

/// Tests op [ReadStore] (lib/local_store.dart): de volledig lokale lees- en
/// sterretjesstatus van de reader-app.
///
/// Patroon volgt `frontend/test/auth_logout_ws_test.dart`:
/// `TestWidgetsFlutterBinding.ensureInitialized()` bovenin `main()` (anders is
/// er geen platformkanaal voor de mock) en per test een eigen
/// `SharedPreferences.setMockInitialValues({...})`.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // Elke test telt de notificaties met een teller via `addListener`; dat is de
  // enige manier om "hier is niets gebeurd" (geen no-op-schrijfactie, geen
  // notificatie) echt aan te tonen. Voor de schrijfkant doet een sentinelwaarde
  // in SharedPreferences hetzelfde werk.

  test('load() leest read_ids en starred_ids terug', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'read_ids': <String>['a', 'b'],
      'starred_ids': <String>['b'],
    });
    final store = ReadStore();

    await store.load();

    expect(store.isRead('a'), isTrue);
    expect(store.isRead('b'), isTrue);
    expect(store.isRead('c'), isFalse);
    expect(store.isStarred('b'), isTrue);
    expect(store.isStarred('a'), isFalse);
  });

  test('load() op een lege opslag levert lege sets op', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final store = ReadStore();

    await store.load();

    expect(store.isRead('a'), isFalse);
    expect(store.isStarred('a'), isFalse);
  });

  test('markRead is idempotent: de tweede aanroep waarschuwt en schrijft niet', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final store = ReadStore();
    await store.load();
    final prefs = await SharedPreferences.getInstance();

    var notifications = 0;
    store.addListener(() => notifications++);

    store.markRead('a');
    expect(store.isRead('a'), isTrue);
    expect(notifications, 1);
    expect(prefs.getStringList('read_ids'), <String>['a']);

    // Sentinel: als de tweede markRead tóch zou schrijven, wordt deze waarde
    // overschreven door ['a'].
    await prefs.setStringList('read_ids', <String>['sentinel']);

    store.markRead('a');

    expect(notifications, 1, reason: 'geen wijziging, dus geen notificatie');
    expect(prefs.getStringList('read_ids'), <String>['sentinel'],
        reason: 'geen wijziging, dus geen schrijfactie');
  });

  test('setRead(id, false) verwijdert de markering, en waarschuwt alleen bij een echte wijziging', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'read_ids': <String>['a'],
    });
    final store = ReadStore();
    await store.load();
    final prefs = await SharedPreferences.getInstance();

    var notifications = 0;
    store.addListener(() => notifications++);

    store.setRead('a', false);
    expect(store.isRead('a'), isFalse);
    expect(notifications, 1);
    expect(prefs.getStringList('read_ids'), <String>[]);

    await prefs.setStringList('read_ids', <String>['sentinel']);

    store.setRead('a', false);

    expect(notifications, 1, reason: 'al niet gelezen, dus geen notificatie');
    expect(prefs.getStringList('read_ids'), <String>['sentinel'],
        reason: 'al niet gelezen, dus geen schrijfactie');
  });

  test('markAllRead telt alleen de items die nieuw op gelezen zijn gezet', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final store = ReadStore();
    await store.load();

    var notifications = 0;
    store.addListener(() => notifications++);

    expect(store.markAllRead(<String>['a', 'b']), 2);
    expect(notifications, 1);

    // Deels overlappend: alleen 'c' is nieuw.
    expect(store.markAllRead(<String>['b', 'c']), 1);
    expect(notifications, 2);
    expect(store.isRead('c'), isTrue);
  });

  test('markAllRead zonder nieuwe id\'s geeft 0, zonder schrijfactie of notificatie', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final store = ReadStore();
    await store.load();
    final prefs = await SharedPreferences.getInstance();

    store.markAllRead(<String>['a', 'b']);

    var notifications = 0;
    store.addListener(() => notifications++);
    await prefs.setStringList('read_ids', <String>['sentinel']);

    expect(store.markAllRead(<String>['a', 'b']), 0);
    expect(store.markAllRead(const <String>[]), 0);

    expect(notifications, 0, reason: 'niets nieuws, dus geen notificatie');
    expect(prefs.getStringList('read_ids'), <String>['sentinel'],
        reason: 'niets nieuws, dus geen schrijfactie');
  });

  test('toggleStar zet het sterretje aan en weer uit', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final store = ReadStore();
    await store.load();
    final prefs = await SharedPreferences.getInstance();

    var notifications = 0;
    store.addListener(() => notifications++);

    store.toggleStar('a');
    expect(store.isStarred('a'), isTrue);
    expect(prefs.getStringList('starred_ids'), <String>['a']);

    store.toggleStar('a');
    expect(store.isStarred('a'), isFalse);
    expect(prefs.getStringList('starred_ids'), <String>[]);

    // Vastgepind gedrag: anders dan markRead/setRead schrijft en waarschuwt
    // toggleStar ONVOORWAARDELIJK — elke aanroep leidt tot een notificatie en
    // een setStringList. Verdedigbaar (een toggle wijzigt per definitie iets),
    // maar het is een asymmetrie met de rest van deze klasse.
    expect(notifications, 2);
  });

  test('zonder load() verandert de status alleen in het geheugen', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    // Bewust GEEN load(): _prefs blijft null en alle schrijfacties zijn
    // null-safe (`_prefs?.setStringList(...)`). In de echte app roept
    // main.dart eerst `await readStore.load()` aan, dus dit is vandaag geen
    // bug — het is een stilzwijgende afspraak die hier wordt vastgelegd.
    final store = ReadStore();
    final prefs = await SharedPreferences.getInstance();

    var notifications = 0;
    store.addListener(() => notifications++);

    store.markRead('a');
    store.toggleStar('a');

    expect(store.isRead('a'), isTrue);
    expect(store.isStarred('a'), isTrue);
    expect(notifications, 2, reason: 'in het geheugen wijzigt er wél iets');
    expect(prefs.getStringList('read_ids'), isNull,
        reason: 'zonder load() wordt er niets bewaard');
    expect(prefs.getStringList('starred_ids'), isNull,
        reason: 'zonder load() wordt er niets bewaard');
  });
}
