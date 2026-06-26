import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/rss_feeds_screen.dart';

/// SF-220: tests voor de nieuwe RSS-feeds-subpagina. We injecteren fake
/// notifiers (geen netwerk) zodat zowel de gewone RSS-editor als de
/// podcast-editor met vaste data renderen en hun save() lokaal opslaat.
class _FakeRssFeedsNotifier extends RssFeedsNotifier {
  final List<String> initial;
  _FakeRssFeedsNotifier(this.initial);

  @override
  Future<List<String>> build() async => initial;

  @override
  Future<void> save(List<String> feeds) async {
    state = AsyncData(feeds);
  }
}

class _FakePodcastFeedsNotifier extends PodcastFeedsNotifier {
  final List<PodcastFeed> initial;
  _FakePodcastFeedsNotifier(this.initial);

  @override
  Future<List<PodcastFeed>> build() async => initial;

  @override
  Future<void> save(List<PodcastFeed> feeds) async {
    state = AsyncData(feeds);
  }
}

Widget _wrap({
  required List<String> rss,
  required List<PodcastFeed> podcasts,
}) {
  return ProviderScope(
    overrides: [
      rssFeedsProvider.overrideWith(() => _FakeRssFeedsNotifier(rss)),
      podcastFeedsProvider.overrideWith(() => _FakePodcastFeedsNotifier(podcasts)),
    ],
    child: const MaterialApp(home: RssFeedsScreen()),
  );
}

void main() {
  testWidgets('toont AppBar-titel en beide sectiekoppen', (tester) async {
    await tester.pumpWidget(_wrap(rss: const [], podcasts: const []));
    await tester.pumpAndSettle();

    // AppBar-titel + sectiekop 'RSS-feeds' (twee keer 'RSS-feeds' tekst).
    expect(find.text('RSS-feeds'), findsWidgets);
    expect(find.text('Podcast-bronnen'), findsOneWidget);
  });

  testWidgets('toont bestaande RSS-feed-URLs monospace', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: const ['https://example.com/feed.xml'],
      podcasts: const [],
    ));
    await tester.pumpAndSettle();

    expect(find.text('https://example.com/feed.xml'), findsOneWidget);
  });

  testWidgets('RSS-feed toevoegen roept save aan en toont nieuwe URL', (tester) async {
    await tester.pumpWidget(_wrap(rss: const [], podcasts: const []));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextField, 'Nieuwe feed-URL'), 'https://new.example/rss');
    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pumpAndSettle();

    expect(find.text('https://new.example/rss'), findsOneWidget);
  });

  testWidgets('lege RSS-feed-URL wordt genegeerd', (tester) async {
    await tester.pumpWidget(_wrap(rss: const [], podcasts: const []));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pumpAndSettle();

    // Geen ListTile met monospace-URL toegevoegd.
    expect(find.byType(Switch), findsNothing);
  });

  testWidgets('toont podcast-bron met transcribe-toggle en label', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: const [],
      podcasts: [PodcastFeed(url: 'https://pod.example/feed', transcribeEnabled: true)],
    ));
    await tester.pumpAndSettle();

    expect(find.text('https://pod.example/feed'), findsOneWidget);
    expect(find.text('Transcriberen aan'), findsOneWidget);
    expect(find.byType(Switch), findsOneWidget);
  });

  testWidgets('podcast-toggle uitzetten slaat op en toont nieuw label', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: const [],
      podcasts: [PodcastFeed(url: 'https://pod.example/feed', transcribeEnabled: true)],
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();

    expect(find.text('Transcriberen uit'), findsOneWidget);
  });

  testWidgets('podcast-bron verwijderen haalt hem uit de lijst', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: const [],
      podcasts: [PodcastFeed(url: 'https://pod.example/feed', transcribeEnabled: true)],
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.close));
    await tester.pumpAndSettle();

    expect(find.text('https://pod.example/feed'), findsNothing);
  });
}
