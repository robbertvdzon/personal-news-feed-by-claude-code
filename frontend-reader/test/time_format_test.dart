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
    expect(formatRelativeTime(isoAgo(Duration.zero)), 'zojuist');
  });

  test('5 minuten geleden geeft "5 min geleden"', () {
    expect(formatRelativeTime(isoAgo(const Duration(minutes: 5))),
        '5 min geleden');
  });

  test('3 uur geleden geeft "3 uur geleden"', () {
    expect(formatRelativeTime(isoAgo(const Duration(hours: 3))),
        '3 uur geleden');
  });

  test('precies 3 dagen geleden geeft nog "3 dagen geleden"', () {
    // De grens in de implementatie is `<= 3`, dus dit is de interessante kant:
    // 3 dagen valt nog in de relatieve tak, 4 dagen niet meer.
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
