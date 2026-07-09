import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/api/api_client.dart';
import 'package:personal_news_feed/models/models.dart';
import 'package:personal_news_feed/providers/auth_provider.dart';
import 'package:personal_news_feed/providers/data_providers.dart';
import 'package:personal_news_feed/screens/main_shell.dart';

/// Fake-notifiers voor de providers die [MainShell] via de IndexedStack
/// direct laat bouwen (Feed/Rss/Podcast/Events/Settings/Request), zodat de
/// test geen netwerk/WebSocket raakt.
class _FakeAuthNotifier extends AuthNotifier {
  _FakeAuthNotifier(super.api, AuthState initial) {
    state = initial;
  }
}

class _FakeFeedNotifier extends FeedNotifier {
  @override
  Future<List<FeedItem>> build() async => const [];
}

class _FakeRssNotifier extends RssNotifier {
  @override
  Future<List<RssItem>> build() async => const [];
}

class _FakePodcastNotifier extends PodcastNotifier {
  @override
  Future<List<Podcast>> build() async => const [];
}

class _FakeEventsNotifier extends EventsNotifier {
  @override
  Future<List<Event>> build() async => const [];
}

class _FakeSettingsNotifier extends SettingsNotifier {
  @override
  Future<List<CategorySettings>> build() async => const [];
}

class _FakeRequestNotifier extends RequestNotifier {
  @override
  Future<List<NewsRequest>> build() async => const [];
}

Widget _wrap() {
  final auth = AuthState(token: 'test-token', username: 'tester', role: 'user');
  return ProviderScope(
    overrides: [
      authProvider.overrideWith((ref) => _FakeAuthNotifier(ApiClient(), auth)),
      feedProvider.overrideWith(_FakeFeedNotifier.new),
      rssProvider.overrideWith(_FakeRssNotifier.new),
      podcastProvider.overrideWith(_FakePodcastNotifier.new),
      eventsProvider.overrideWith(_FakeEventsNotifier.new),
      settingsProvider.overrideWith(_FakeSettingsNotifier.new),
      requestProvider.overrideWith(_FakeRequestNotifier.new),
    ],
    child: const MaterialApp(home: MainShell()),
  );
}

void main() {
  testWidgets('bottom-navigatie toont "Instellingen" i.p.v. "Settings"',
      (WidgetTester tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pump();

    expect(find.widgetWithText(NavigationDestination, 'Instellingen'), findsOneWidget);
    expect(find.text('Settings'), findsNothing);
  });

  testWidgets('settings-tab blijft navigeerbaar via het settings-icoon',
      (WidgetTester tester) async {
    await tester.pumpWidget(_wrap());
    await tester.pump();

    await tester.tap(find.widgetWithText(NavigationDestination, 'Instellingen'));
    await tester.pumpAndSettle();

    expect(
      find.descendant(
          of: find.byType(AppBar), matching: find.text('Instellingen')),
      findsOneWidget,
    );
  });
}
