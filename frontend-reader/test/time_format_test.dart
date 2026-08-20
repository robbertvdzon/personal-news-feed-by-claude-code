import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed_reader/time_format.dart';

/// Tests op [formatRelativeTime] (lib/time_format.dart), een pure functie.
///
/// De invoer wordt steeds relatief aan `DateTime.now()` gebouwd, want de
/// functie gebruikt die zelf — hardgecodeerde datums geven een test die over
/// een jaar iets anders betekent.
String isoAgo(Duration d) =>
    DateTime.now().subtract(d).toUtc().toIso8601String();

void main() {
  test('lege invoer geeft een lege string', () {
    expect(formatRelativeTime(''), '');
  });

  test('onparseerbare invoer geeft een lege string', () {
    expect(formatRelativeTime('geen datum'), '');
  });

  test('net nu geeft "zojuist"', () {
    // Twin-drift: de hoofd-app antwoordt hier 'net binnen'
    // (frontend/lib/util/time_format.dart:18). Het woord 'zojuist' bestaat
    // daar ook, maar betekent iets ánders: het is gereserveerd voor een
    // negatief tijdsverschil (`diff.isNegative`, :17) — een guard die deze
    // reader mist. Wie ooit de twee time_format.dart-varianten samenvoegt,
    // moet die twee betekenissen dus uit elkaar houden.
    expect(formatRelativeTime(isoAgo(Duration.zero)), 'zojuist');
  });

  test('5 minuten geleden geeft "5 min geleden"', () {
    // Twin-drift: de hoofd-app schrijft de eenheid voluit en kent een
    // enkelvoudsvorm — '5 minuten geleden' (en '1 minuut geleden'),
    // frontend/lib/util/time_format.dart:21.
    expect(formatRelativeTime(isoAgo(const Duration(minutes: 5))),
        '5 min geleden');
  });

  test('3 uur geleden geeft "3 uur geleden"', () {
    expect(formatRelativeTime(isoAgo(const Duration(hours: 3))),
        '3 uur geleden');
  });

  test('1 dag geleden geeft "1 dagen geleden"', () {
    // Karakteriseringstest: dit gedrag is bekend onjuist. De reader kent geen
    // enkelvoudsvorm (lib/time_format.dart:11 interpoleert altijd
    // '$dagen dagen geleden'), dus precies één dag leest als '1 dagen
    // geleden'. De hoofd-app antwoordt hier '1 dag geleden'
    // (frontend/lib/util/time_format.dart:29).
    //
    // De assertie legt vast wat de code vandaag doet, niet wat ze zou moeten
    // doen: 'corrigeer' hem dus niet los — hij mag alleen wijzigen in dezelfde
    // diff die de implementatie meeverandert.
    expect(formatRelativeTime(isoAgo(const Duration(days: 1))),
        '1 dagen geleden');
  });

  test('precies 3 dagen geleden geeft nog "3 dagen geleden"', () {
    // De grens in de implementatie is `<= 3`, dus dit is de interessante kant:
    // 3 dagen valt nog in de relatieve tak, 4 dagen niet meer.
    // Twin-drift: de hoofd-app trekt de grens op `< 3`
    // (frontend/lib/util/time_format.dart:27) en toont bij precies 3 dagen
    // dus de absolute datum (dd-mm-jjjj) in plaats van een relatieve tekst.
    expect(formatRelativeTime(isoAgo(const Duration(days: 3))),
        '3 dagen geleden');
  });

  test('10 dagen geleden geeft een datum in dd-mm-jjjj met nul-padding', () {
    final moment = DateTime.now().subtract(const Duration(days: 10));
    final local = moment.toLocal();
    final expected = '${local.day.toString().padLeft(2, '0')}-'
        '${local.month.toString().padLeft(2, '0')}-${local.year}';

    final formatted = formatRelativeTime(moment.toUtc().toIso8601String());

    expect(formatted, expected);
    expect(formatted, matches(RegExp(r'^\d{2}-\d{2}-\d{4}$')));
  });
}
