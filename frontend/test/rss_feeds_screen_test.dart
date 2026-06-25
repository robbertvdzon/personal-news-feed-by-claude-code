import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/rss_feeds_screen.dart';

/// Fake-notifiers die de echte AsyncNotifier-klassen extenden en
/// build()/save() overriden zodat er geen netwerk nodig is. Zie
/// agent-tip pnf-flutter-asyncnotifier-test-override.
class _FakeRssFeedsNotifier extends RssFeedsNotifier {
  _FakeRssFeedsNotifier(this._initial);
  final List<String> _initial;
  List<String>? saved;

  @override
  Future<List<String>> build() async => _initial;

  @override
  Future<void> save(List<String> feeds) async {
    saved = feeds;
    state = AsyncData(feeds);
  }
}

class _FakePodcastFeedsNotifier extends PodcastFeedsNotifier {
  _FakePodcastFeedsNotifier(this._initial);
  final List<PodcastFeed> _initial;
  List<PodcastFeed>? saved;

  @override
  Future<List<PodcastFeed>> build() async => _initial;

  @override
  Future<void> save(List<PodcastFeed> feeds) async {
    saved = feeds;
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
  testWidgets('toont AppBar-titel, sectiekoppen en bestaande bronnen', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const ['https://example.com/feed.xml']),
      podcast: _FakePodcastFeedsNotifier(
        [PodcastFeed(url: 'https://pod.example/rss', transcribeEnabled: true)],
      ),
    ));
    await tester.pumpAndSettle();

    // 'RSS-feeds' komt voor als AppBar-titel én als sectiekop.
    expect(find.text('RSS-feeds'), findsWidgets);
    expect(find.text('Podcast-bronnen'), findsOneWidget);

    // Bestaande bronnen worden getoond.
    expect(find.text('https://example.com/feed.xml'), findsOneWidget);
    expect(find.text('https://pod.example/rss'), findsOneWidget);
    expect(find.text('Transcriberen aan'), findsOneWidget);
  });

  testWidgets('RSS-feed toevoegen via add-knop bewaart de URL', (tester) async {
    final rss = _FakeRssFeedsNotifier(const <String>[]);
    final podcast = _FakePodcastFeedsNotifier(const <PodcastFeed>[]);
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextField, 'Nieuwe feed-URL'), 'https://new.example/rss');
    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pumpAndSettle();

    expect(rss.saved, isNotNull);
    expect(rss.saved, contains('https://new.example/rss'));
  });

  testWidgets('RSS-feed verwijderen roept save aan zonder de feed', (tester) async {
    final rss = _FakeRssFeedsNotifier(const ['https://example.com/feed.xml']);
    final podcast = _FakePodcastFeedsNotifier(const <PodcastFeed>[]);
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(rss.saved, isNotNull);
    expect(rss.saved, isEmpty);
  });

  testWidgets('podcast-toggle bedienen bewaart de gewijzigde transcribe-flag', (tester) async {
    final rss = _FakeRssFeedsNotifier(const <String>[]);
    final podcast = _FakePodcastFeedsNotifier(
      [PodcastFeed(url: 'https://pod.example/rss', transcribeEnabled: true)],
    );
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();

    expect(podcast.saved, isNotNull);
    expect(podcast.saved!.single.transcribeEnabled, isFalse);
  });

  testWidgets('podcast verwijderen roept save aan zonder de bron', (tester) async {
    final rss = _FakeRssFeedsNotifier(const <String>[]);
    final podcast = _FakePodcastFeedsNotifier(
      [PodcastFeed(url: 'https://pod.example/rss', transcribeEnabled: true)],
    );
    await tester.pumpWidget(_wrap(rss: rss, podcast: podcast));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(podcast.saved, isNotNull);
    expect(podcast.saved, isEmpty);
  });
}
