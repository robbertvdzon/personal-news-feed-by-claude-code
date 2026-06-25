import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/rss_feeds_screen.dart';

/// SF-220: de RSS- en podcast-editors zijn van de settings-pagina naar
/// [RssFeedsScreen] verhuisd. Deze tests renderen de subpagina met
/// vaste provider-data en verifiëren dat beide secties met hun editors
/// verschijnen — inclusief de styling-conventies (monospace-URL,
/// sectiekoppen, spinner, 'Fout: …'-tekst).

class _FakeRssFeedsNotifier extends RssFeedsNotifier {
  final List<String> data;
  _FakeRssFeedsNotifier(this.data);
  @override
  Future<List<String>> build() async => data;
}

class _FakePodcastFeedsNotifier extends PodcastFeedsNotifier {
  final List<PodcastFeed> data;
  _FakePodcastFeedsNotifier(this.data);
  @override
  Future<List<PodcastFeed>> build() async => data;
}

/// Notifier die nooit voltooit, zodat de provider in de loading-staat
/// blijft hangen en de spinner-tak van `.when()` zichtbaar is.
class _PendingRssFeedsNotifier extends RssFeedsNotifier {
  @override
  Future<List<String>> build() => Completer<List<String>>().future;
}

class _PendingPodcastFeedsNotifier extends PodcastFeedsNotifier {
  @override
  Future<List<PodcastFeed>> build() => Completer<List<PodcastFeed>>().future;
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

    // AppBar-titel 'RSS-feeds' + de RSS-sectiekop 'RSS-feeds' (2 keer).
    expect(find.text('RSS-feeds'), findsNWidgets(2));
    expect(find.text('Podcast-bronnen'), findsOneWidget);
  });

  testWidgets('rendert RSS-feed-URLs in monospace en de podcast-toggle', (tester) async {
    await tester.pumpWidget(_wrap(
      rss: const ['https://example.com/rss'],
      podcasts: [PodcastFeed(url: 'https://pod.example/feed', transcribeEnabled: true)],
    ));
    await tester.pumpAndSettle();

    final rssText = tester.widget<Text>(find.text('https://example.com/rss'));
    expect(rssText.style?.fontFamily, 'monospace');

    expect(find.text('https://pod.example/feed'), findsOneWidget);
    expect(find.text('Transcriberen aan'), findsOneWidget);
    expect(find.byType(Switch), findsOneWidget);

    // Eén invoerveld per editor (RSS + podcast).
    expect(find.byType(TextField), findsNWidgets(2));
  });

  testWidgets('toont een spinner zolang de providers laden', (tester) async {
    // Providers die nooit voltooien → beide secties tonen de
    // loading-tak (CircularProgressIndicator) van `.when()`.
    await tester.pumpWidget(ProviderScope(
      overrides: [
        rssFeedsProvider.overrideWith(() => _PendingRssFeedsNotifier()),
        podcastFeedsProvider.overrideWith(() => _PendingPodcastFeedsNotifier()),
      ],
      child: const MaterialApp(home: RssFeedsScreen()),
    ));
    await tester.pump();

    expect(find.byType(CircularProgressIndicator), findsNWidgets(2));
  });
}
