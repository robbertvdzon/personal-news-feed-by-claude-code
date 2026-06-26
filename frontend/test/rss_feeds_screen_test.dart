import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/rss_feeds_screen.dart';

/// Fake voor [rssFeedsProvider]: levert vaste data en houdt save() lokaal
/// bij zonder netwerk (zie agent-tip pnf-flutter-asyncnotifier-test-override).
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
  bool throwOnSave = false;

  @override
  Future<List<PodcastFeed>> build() async => _initial;

  @override
  Future<void> save(List<PodcastFeed> feeds) async {
    if (throwOnSave) throw Exception('invalid url');
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
  testWidgets('toont beide sectiekoppen en de AppBar-titel', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const ['https://a.example/feed']),
      podcast: _FakePodcastFeedsNotifier([PodcastFeed(url: 'https://p.example/rss')]),
    ));
    await tester.pumpAndSettle();

    // AppBar-titel + beide sectiekoppen.
    expect(find.text('RSS-feeds'), findsWidgets);
    expect(find.text('Podcast-bronnen'), findsOneWidget);
    // Bestaande feeds worden getoond.
    expect(find.text('https://a.example/feed'), findsOneWidget);
    expect(find.text('https://p.example/rss'), findsOneWidget);
  });

  testWidgets('RSS-feed toevoegen roept save() aan met de nieuwe URL', (tester) async {
    final rss = _FakeRssFeedsNotifier(const ['https://a.example/feed']);
    await tester.pumpWidget(_wrap(
      rss: rss,
      podcast: _FakePodcastFeedsNotifier(const []),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextField, 'Nieuwe feed-URL'), 'https://b.example/feed');
    await tester.tap(find.byIcon(Icons.add).first);
    await tester.pumpAndSettle();

    expect(rss.lastSaved, ['https://a.example/feed', 'https://b.example/feed']);
  });

  testWidgets('RSS-feed verwijderen roept save() aan zonder die feed', (tester) async {
    final rss = _FakeRssFeedsNotifier(const ['https://a.example/feed', 'https://b.example/feed']);
    await tester.pumpWidget(_wrap(
      rss: rss,
      podcast: _FakePodcastFeedsNotifier(const []),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(rss.lastSaved, ['https://b.example/feed']);
  });

  testWidgets('podcast-bron toevoegen roept save() aan met transcribe aan', (tester) async {
    final podcast = _FakePodcastFeedsNotifier(const []);
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const []),
      podcast: podcast,
    ));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextField, 'Nieuwe podcast-RSS-URL'), 'https://p.example/rss');
    // De podcast-editor staat als laatste sectie; haar add-knop is de laatste.
    await tester.tap(find.byIcon(Icons.add).last);
    await tester.pumpAndSettle();

    expect(podcast.lastSaved, isNotNull);
    expect(podcast.lastSaved!.single.url, 'https://p.example/rss');
    expect(podcast.lastSaved!.single.transcribeEnabled, isTrue);
  });

  testWidgets('podcast transcribe-toggle slaat omgekeerde waarde op', (tester) async {
    final podcast =
        _FakePodcastFeedsNotifier([PodcastFeed(url: 'https://p.example/rss', transcribeEnabled: true)]);
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const []),
      podcast: podcast,
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();

    expect(podcast.lastSaved!.single.transcribeEnabled, isFalse);
  });

  testWidgets('ongeldige podcast-URL toont een snackbar', (tester) async {
    final podcast = _FakePodcastFeedsNotifier(const [])..throwOnSave = true;
    await tester.pumpWidget(_wrap(
      rss: _FakeRssFeedsNotifier(const []),
      podcast: podcast,
    ));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextField, 'Nieuwe podcast-RSS-URL'), 'not-a-url');
    await tester.tap(find.byIcon(Icons.add).last);
    await tester.pump(); // start async save
    await tester.pump(); // laat snackbar verschijnen

    expect(find.byType(SnackBar), findsOneWidget);
    expect(find.text('Kon feed niet ophalen'), findsOneWidget);
  });
}
