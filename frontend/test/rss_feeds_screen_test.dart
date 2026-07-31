import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/api/api_client.dart';
import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/rss_feeds_screen.dart';

/// Fake-notifier voor [rssFeedsProvider]: levert vaste data zonder netwerk
/// en houdt opgeslagen waarden lokaal bij zodat add/delete getest kan worden.
class _FakeRssFeedsNotifier extends RssFeedsNotifier {
  _FakeRssFeedsNotifier(this._initial);
  final List<String> _initial;
  List<String>? lastSaved;

  @override
  Future<List<String>> build() async => _initial;

  @override
  Future<void> save(List<String> feeds) async {
    lastSaved = feeds;
    state = AsyncData(feeds);
  }
}

/// SF-1552: fake-notifier die het faalpad van de backend nabootst — een
/// HTTP 400 met een Nederlandse foutmelding in het `error`-veld. De state
/// wordt bewust níét gemuteerd, net als de echte [RssFeedsNotifier.save].
class _FailingRssFeedsNotifier extends RssFeedsNotifier {
  _FailingRssFeedsNotifier(this._initial, this._body);
  final List<String> _initial;
  final String _body;
  int saveCalls = 0;

  @override
  Future<List<String>> build() async => _initial;

  @override
  Future<void> save(List<String> feeds) async {
    saveCalls++;
    throw ApiException(400, _body);
  }
}

/// SF-1552: fake-notifier waarvan de save pas afrondt als de meegegeven
/// future compleet is — zo is de busy-state observeerbaar in de widgettest.
class _SlowRssFeedsNotifier extends RssFeedsNotifier {
  _SlowRssFeedsNotifier(this._initial, this._gate);
  final List<String> _initial;
  final Future<void> _gate;

  @override
  Future<List<String>> build() async => _initial;

  @override
  Future<void> save(List<String> feeds) async {
    await _gate;
    state = AsyncData(feeds);
  }
}

/// Fake-notifier voor [podcastFeedsProvider], zelfde principe.
class _FakePodcastFeedsNotifier extends PodcastFeedsNotifier {
  _FakePodcastFeedsNotifier(this._initial);
  final List<PodcastFeed> _initial;
  List<PodcastFeed>? lastSaved;

  @override
  Future<List<PodcastFeed>> build() async => _initial;

  @override
  Future<void> save(List<PodcastFeed> feeds) async {
    lastSaved = feeds;
    state = AsyncData(feeds);
  }
}

/// SF-1552: podcast-tegenhanger van [_FailingRssFeedsNotifier] — bewijst dat
/// beide editors dezelfde message-extractie op het `error`-veld gebruiken.
class _FailingPodcastFeedsNotifier extends PodcastFeedsNotifier {
  _FailingPodcastFeedsNotifier(this._body);
  final String _body;

  @override
  Future<List<PodcastFeed>> build() async => const [];

  @override
  Future<void> save(List<PodcastFeed> feeds) async => throw ApiException(400, _body);
}

Widget _wrap({
  required RssFeedsNotifier rss,
  required PodcastFeedsNotifier podcasts,
}) {
  return ProviderScope(
    overrides: [
      rssFeedsProvider.overrideWith(() => rss),
      podcastFeedsProvider.overrideWith(() => podcasts),
    ],
    child: const MaterialApp(home: RssFeedsScreen()),
  );
}

void main() {
  testWidgets('toont AppBar-titel en beide sectiekoppen', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const ['https://example.com/rss']),
      podcasts: _FakePodcastFeedsNotifier([PodcastFeed(url: 'https://pod.example/feed')]),
    ));
    await tester.pumpAndSettle();

    // AppBar-titel + sectiekop "RSS-feeds" (kan op meerdere plekken staan).
    expect(find.text('RSS-feeds'), findsWidgets);
    expect(find.text('Podcast-bronnen'), findsOneWidget);
    expect(find.text('https://example.com/rss'), findsOneWidget);
    expect(find.text('https://pod.example/feed'), findsOneWidget);
  });

  testWidgets('RSS-feed toevoegen roept save aan met nieuwe URL', (tester) async {
    final rss = _FakeRssFeedsNotifier(const []);
    await tester.pumpWidget(_wrap(
      rss: rss,
      podcasts: _FakePodcastFeedsNotifier(const []),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, 'https://nieuw.example/rss');
    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pumpAndSettle();

    expect(rss.lastSaved, ['https://nieuw.example/rss']);
  });

  testWidgets('RSS-feed verwijderen roept save aan met lege lijst', (tester) async {
    final rss = _FakeRssFeedsNotifier(const ['https://weg.example/rss']);
    await tester.pumpWidget(_wrap(
      rss: rss,
      podcasts: _FakePodcastFeedsNotifier(const []),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(rss.lastSaved, isEmpty);
  });

  testWidgets('RSS-feed toevoegen: 400 toont servertekst, lijst en veld ongewijzigd',
      (tester) async {
    const serverMsg = "Ongeldige RSS-feed-URL 'http://127.0.0.1/rss': loopback-adres niet toegestaan";
    final rss = _FailingRssFeedsNotifier(
      const ['https://bestaand.example/rss'],
      '{"error":"$serverMsg"}',
    );
    await tester.pumpWidget(_wrap(
      rss: rss,
      podcasts: _FakePodcastFeedsNotifier(const []),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, 'http://127.0.0.1/rss');
    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pumpAndSettle();

    expect(rss.saveCalls, 1);
    // Snackbar toont de Nederlandse servertekst, niet de rauwe JSON.
    expect(find.text(serverMsg), findsOneWidget);
    // De URL is niet aan de lijst toegevoegd (hij staat alleen nog in het
    // invoerveld, niet als ListTile-titel)...
    expect(
      find.descendant(of: find.byType(ListTile), matching: find.text('http://127.0.0.1/rss')),
      findsNothing,
    );
    expect(find.text('https://bestaand.example/rss'), findsOneWidget);
    // ...en het invoerveld behoudt de ingetypte tekst.
    final field = tester.widget<TextField>(find.byType(TextField).first);
    expect(field.controller!.text, 'http://127.0.0.1/rss');
  });

  testWidgets('RSS-feed verwijderen: 400 laat feed staan en toont snackbar', (tester) async {
    const serverMsg = 'Opslaan mislukt: feeds niet geldig';
    final rss = _FailingRssFeedsNotifier(
      const ['https://blijft.example/rss'],
      '{"error":"$serverMsg"}',
    );
    await tester.pumpWidget(_wrap(
      rss: rss,
      podcasts: _FakePodcastFeedsNotifier(const []),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(rss.saveCalls, 1);
    expect(find.text(serverMsg), findsOneWidget);
    expect(find.text('https://blijft.example/rss'), findsOneWidget);
  });

  testWidgets('RSS-editor toont spinner en blokkeert invoer tijdens opslaan', (tester) async {
    final completer = Completer<void>();
    final rss = _SlowRssFeedsNotifier(const [], completer.future);
    await tester.pumpWidget(_wrap(
      rss: rss,
      podcasts: _FakePodcastFeedsNotifier(const []),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, 'https://traag.example/rss');
    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pump();

    // De +-knop van de RSS-editor is vervangen door een spinner (die van de
    // podcast-editor blijft staan) en het veld is uitgeschakeld.
    expect(find.byIcon(Icons.add), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(tester.widget<TextField>(find.byType(TextField).first).enabled, isFalse);

    completer.complete();
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.add), findsNWidgets(2));
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(tester.widget<TextField>(find.byType(TextField).first).controller!.text, isEmpty);
  });

  testWidgets('podcast-fout toont de tekst uit het error-veld, niet de rauwe JSON', (tester) async {
    const serverMsg = 'Kon podcast-feed niet ophalen: 404';
    final podcasts = _FailingPodcastFeedsNotifier('{"error":"$serverMsg"}');
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const []),
      podcasts: podcasts,
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).last, 'https://kapot.example/feed');
    await tester.tap(find.byIcon(Icons.add).last);
    await tester.pumpAndSettle();

    expect(find.text(serverMsg), findsOneWidget);
    expect(find.textContaining('"error"'), findsNothing);
  });

  testWidgets('podcast-transcriptie-toggle slaat gewijzigde waarde op', (tester) async {
    final podcasts = _FakePodcastFeedsNotifier([
      PodcastFeed(url: 'https://pod.example/feed', transcribeEnabled: true),
    ]);
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const []),
      podcasts: podcasts,
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();

    expect(podcasts.lastSaved, isNotNull);
    expect(podcasts.lastSaved!.single.transcribeEnabled, isFalse);
  });
}
