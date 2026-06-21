import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/data_providers.dart';
import '../util/deep_link.dart';
import 'feed_screen.dart';
import 'feed_detail_screen.dart';
import 'rss_screen.dart';
import 'rss_detail_screen.dart';
import 'podcast_screen.dart';
import 'events_screen.dart';
import 'settings_screen.dart';

class MainShell extends ConsumerStatefulWidget {
  const MainShell({super.key});

  @override
  ConsumerState<MainShell> createState() => _MainShellState();
}

class _MainShellState extends ConsumerState<MainShell> {
  int _index = 0;

  @override
  void initState() {
    super.initState();
    // Geopend via een bookmark-URL (/feed/<id> of /rss/<id>)? Toon alleen
    // dat ene item zodra de bijbehorende lijst geladen is. Eénmalig.
    final link = pendingDeepLink;
    pendingDeepLink = null;
    if (link != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _openDeepLink(link));
    }
  }

  /// Zoekt het item op id in de al geladen feed/rss-lijst en opent het als
  /// los detail (1/1). Staat het er niet (meer) in, of faalt het laden, dan
  /// een nette melding — geen next/back, conform de bookmark-keuze.
  Future<void> _openDeepLink(DeepLink link) async {
    try {
      if (link.type == 'rss') {
        setState(() => _index = 1);
        final items = await ref.read(rssProvider.future);
        final i = items.indexWhere((e) => e.id == link.id);
        if (!mounted) return;
        if (i < 0) return _notAvailable();
        await Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => RssItemDetailScreen(items: [items[i]], initialIndex: 0),
        ));
      } else {
        setState(() => _index = 0);
        final items = await ref.read(feedProvider.future);
        final i = items.indexWhere((e) => e.id == link.id);
        if (!mounted) return;
        if (i < 0) return _notAvailable();
        await Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => FeedItemDetailScreen(items: [items[i]], initialIndex: 0),
        ));
      }
    } catch (_) {
      if (mounted) _notAvailable();
    }
  }

  void _notAvailable() {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Dit item is niet meer beschikbaar.')),
    );
  }

  static const _screens = <Widget>[
    FeedScreen(),
    RssScreen(),
    PodcastScreen(),
    EventsScreen(),
    SettingsScreen(),
  ];

  static const _destinations = <NavigationDestination>[
    NavigationDestination(icon: Icon(Icons.dynamic_feed), label: 'Feed'),
    NavigationDestination(icon: Icon(Icons.rss_feed), label: 'RSS'),
    NavigationDestination(icon: Icon(Icons.podcasts), label: 'Podcast'),
    NavigationDestination(icon: Icon(Icons.event), label: 'Events'),
    NavigationDestination(icon: Icon(Icons.settings), label: 'instellingen'),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _index, children: _screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: _destinations,
      ),
    );
  }
}
