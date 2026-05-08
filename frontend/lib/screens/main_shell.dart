import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/data_providers.dart';
import 'feed_screen.dart';
import 'rss_screen.dart';
import 'queue_screen.dart';
import 'podcast_screen.dart';
import 'settings_screen.dart';

class MainShell extends ConsumerStatefulWidget {
  const MainShell({super.key});

  @override
  ConsumerState<MainShell> createState() => _MainShellState();
}

class _MainShellState extends ConsumerState<MainShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final activeRequests = ref.watch(activeRequestCountProvider);
    final screens = const [
      FeedScreen(),
      RssScreen(),
      QueueScreen(),
      PodcastScreen(),
      SettingsScreen(),
    ];
    return Scaffold(
      body: IndexedStack(index: _index, children: screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: [
          const NavigationDestination(icon: Icon(Icons.dynamic_feed), label: 'Feed'),
          const NavigationDestination(icon: Icon(Icons.rss_feed), label: 'RSS'),
          NavigationDestination(
            icon: Badge.count(
              count: activeRequests,
              isLabelVisible: activeRequests > 0,
              child: const Icon(Icons.queue),
            ),
            label: 'Queue',
          ),
          const NavigationDestination(icon: Icon(Icons.podcasts), label: 'Podcast'),
          const NavigationDestination(icon: Icon(Icons.settings), label: 'Settings'),
        ],
      ),
    );
  }
}
