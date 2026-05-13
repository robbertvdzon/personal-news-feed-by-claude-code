import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/auth_provider.dart';
import 'admin_screen.dart';
import 'feed_screen.dart';
import 'rss_screen.dart';
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
    final isAdmin = ref.watch(authProvider).isAdmin;

    final screens = <Widget>[
      const FeedScreen(),
      const RssScreen(),
      const PodcastScreen(),
      const SettingsScreen(),
      if (isAdmin) const AdminScreen(),
    ];

    final destinations = <NavigationDestination>[
      const NavigationDestination(icon: Icon(Icons.dynamic_feed), label: 'Feed'),
      const NavigationDestination(icon: Icon(Icons.rss_feed), label: 'RSS'),
      const NavigationDestination(icon: Icon(Icons.podcasts), label: 'Podcast'),
      const NavigationDestination(icon: Icon(Icons.settings), label: 'Settings'),
      if (isAdmin)
        const NavigationDestination(
          icon: Icon(Icons.admin_panel_settings),
          label: 'Admin',
        ),
    ];

    // Als de admin-rol verloren raakt terwijl je op de Admin-tab staat,
    // val terug op tab 0 zodat de lijst niet stuk gaat.
    if (_index >= screens.length) {
      _index = 0;
    }

    return Scaffold(
      body: IndexedStack(index: _index, children: screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: destinations,
      ),
    );
  }
}
