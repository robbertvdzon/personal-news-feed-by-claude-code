import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/rss_feeds_screen.dart';

/// Fake notifiers die de netwerk-`build()` vervangen door vaste data,
/// zodat de subpagina zonder backend getest kan worden. `save()` houdt
/// de aangeleverde lijst lokaal bij in `saved` zodat we toevoegen/
/// verwijderen kunnen verifiëren.
class _FakeRssFeedsNotifier extends RssFeedsNotifier {
  _FakeRssFeedsNotifier(this.initial);
  final List<String> initial;
  List<String>? saved;

  @override
  Future<List<String>> build() async => initial;

  @override
  Future<void> save(List<String> feeds) async {
    saved = feeds;
    state = AsyncData(feeds);
  }
}

class _FakePodcastFeedsNotifier extends PodcastFeedsNotifier {
  _FakePodcastFeedsNotifier(this.initial);
  final List<PodcastFeed> initial;
  List<PodcastFeed>? saved;

  @override
  Future<List<PodcastFeed>> build() async => initial;

  @override
  Future<void> save(List<PodcastFeed> feeds) async {
    saved = feeds;
    state = AsyncData(feeds);
  }
}

Widget _wrap(
  _FakeRssFeedsNotifier rss,
  _FakePodcastFeedsNotifier podcasts,
) {
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
    final rss = _FakeRssFeedsNotifier(['https://example.com/feed.xml']);
    final podcasts = _FakePodcastFeedsNotifier(
      [PodcastFeed(url: 'https://pod.example/rss', transcribeEnabled: true)],
    );
    await tester.pumpWidget(_wrap(rss, podcasts));
    await tester.pumpAndSettle();

    // AppBar-titel + sectiekoppen.
    expect(find.widgetWithText(AppBar, 'RSS-feeds'), findsOneWidget);
    expect(find.text('Podcast-bronnen'), findsOneWidget);

    // De bestaande feed-URL's worden getoond.
    expect(find.text('https://example.com/feed.xml'), findsOneWidget);
    expect(find.text('https://pod.example/rss'), findsOneWidget);
    // Podcast-toggle-label.
    expect(find.text('Transcriberen aan'), findsOneWidget);
  });

  testWidgets('RSS-feed toevoegen roept save aan met nieuwe URL', (tester) async {
    final rss = _FakeRssFeedsNotifier(const []);
    final podcasts = _FakePodcastFeedsNotifier(const []);
    await tester.pumpWidget(_wrap(rss, podcasts));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.widgetWithText(TextField, 'Nieuwe feed-URL'),
      'https://new.example/feed',
    );
    await tester.tap(find.widgetWithIcon(IconButton, Icons.add).first);
    await tester.pumpAndSettle();

    expect(rss.saved, ['https://new.example/feed']);
  });

  testWidgets('RSS-feed verwijderen roept save aan zonder die URL', (tester) async {
    final rss = _FakeRssFeedsNotifier(['https://a.example', 'https://b.example']);
    final podcasts = _FakePodcastFeedsNotifier(const []);
    await tester.pumpWidget(_wrap(rss, podcasts));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithIcon(IconButton, Icons.close).first);
    await tester.pumpAndSettle();

    expect(rss.saved, ['https://b.example']);
  });

  testWidgets('podcast-transcribeer-toggle slaat omgekeerde waarde op', (tester) async {
    final rss = _FakeRssFeedsNotifier(const []);
    final podcasts = _FakePodcastFeedsNotifier(
      [PodcastFeed(url: 'https://pod.example/rss', transcribeEnabled: true)],
    );
    await tester.pumpWidget(_wrap(rss, podcasts));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();

    expect(podcasts.saved, isNotNull);
    expect(podcasts.saved!.single.transcribeEnabled, isFalse);
  });
}
