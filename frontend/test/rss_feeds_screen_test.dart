import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/rss_feeds_screen.dart';

/// Fake voor [RssFeedsNotifier]: levert vaste data en houdt save() lokaal
/// bij zonder netwerk. Zie agent-tip pnf-flutter-asyncnotifier-test-override.
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
  required _FakePodcastFeedsNotifier podcast,
}) {
  return ProviderScope(
    overrides: [
      rssFeedsProvider.overrideWith(() => rss),
      podcastFeedsProvider.overrideWith(() => podcast),
    ],
    child: const MaterialApp(home: RssFeedsScreen()),
  );
}

void main() {
  testWidgets('toont AppBar-titel en beide secties met bestaande feeds',
      (tester) async {
    final rss = _FakeRssFeedsNotifier(['https://example.com/rss']);
    final podcast = _FakePodcastFeedsNotifier(
      [PodcastFeed(url: 'https://example.com/pod', transcribeEnabled: true)],
    );
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    // AppBar-titel + beide sectiekoppen.
    expect(find.widgetWithText(AppBar, 'RSS-feeds'), findsOneWidget);
    expect(find.text('Podcast-bronnen'), findsOneWidget);
    // Bestaande items zichtbaar.
    expect(find.text('https://example.com/rss'), findsOneWidget);
    expect(find.text('https://example.com/pod'), findsOneWidget);
    expect(find.text('Transcriberen aan'), findsOneWidget);
  });

  testWidgets('RSS-feed toevoegen roept save aan met de nieuwe URL',
      (tester) async {
    final rss = _FakeRssFeedsNotifier([]);
    final podcast = _FakePodcastFeedsNotifier([]);
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextField, 'Nieuwe feed-URL'), 'https://new.example/rss');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();

    expect(rss.lastSaved, ['https://new.example/rss']);
  });

  testWidgets('RSS-feed verwijderen roept save aan zonder die URL',
      (tester) async {
    final rss = _FakeRssFeedsNotifier(['https://a.example', 'https://b.example']);
    final podcast = _FakePodcastFeedsNotifier([]);
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    // Eerste close-knop (van de eerste feed).
    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(rss.lastSaved, ['https://b.example']);
  });

  testWidgets('podcast transcribeer-toggle slaat aangepaste waarde op',
      (tester) async {
    final rss = _FakeRssFeedsNotifier([]);
    final podcast = _FakePodcastFeedsNotifier(
      [PodcastFeed(url: 'https://example.com/pod', transcribeEnabled: true)],
    );
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();

    expect(podcast.lastSaved, isNotNull);
    expect(podcast.lastSaved!.single.transcribeEnabled, isFalse);
  });
}
