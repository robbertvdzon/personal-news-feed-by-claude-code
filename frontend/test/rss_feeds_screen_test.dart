import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

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

Widget _wrap({
  required _FakeRssFeedsNotifier rss,
  required _FakePodcastFeedsNotifier podcasts,
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
