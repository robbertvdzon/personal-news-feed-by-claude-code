import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import '../util/time_format.dart';
import '../widgets/app_logo.dart';
import '../widgets/feed_card.dart';
import 'feed_detail_screen.dart';
import 'rss_podcast_detail_screen.dart';

// Speciale tab-id's. We gebruiken dezelfde tab-rij voor "categorie"-tabs
// en cross-cutting filters (Bewaard, Samenvatting). Tab-id's met dubbele
// underscore botsen niet met categorie-id's (die zijn slug-achtig).
const _allTabId = '__all__';
const _starredTabId = '__starred__';
const _summaryTabId = '__summary__';

/// KAN-60 (AC8): media-type filter onafhankelijk van de categorie-tab.
enum _MediaFilter { all, rss, podcasts }

class FeedScreen extends ConsumerStatefulWidget {
  const FeedScreen({super.key});

  @override
  ConsumerState<FeedScreen> createState() => _FeedScreenState();
}

class _FeedScreenState extends ConsumerState<FeedScreen> {
  String _selectedTab = _allTabId;
  bool _hideRead = true;
  // KAN-60 (AC8): session-scoped filter — Alles / RSS / Podcasts.
  _MediaFilter _mediaFilter = _MediaFilter.all;

  @override
  Widget build(BuildContext context) {
    final feed = ref.watch(feedProvider);
    final settings = ref.watch(settingsProvider);
    final cats = settings.value ?? const <CategorySettings>[];

    // Tab-volgorde: Alles → Bewaard → Samenvatting → categorieën.
    final tabs = <_FeedTab>[
      const _FeedTab(id: _allTabId, name: 'Alles'),
      const _FeedTab(id: _starredTabId, name: 'Bewaard'),
      const _FeedTab(id: _summaryTabId, name: 'Samenvatting'),
      ...cats.where((c) => c.enabled).map((c) => _FeedTab(id: c.id, name: c.name)),
    ];
    if (!tabs.any((t) => t.id == _selectedTab)) _selectedTab = _allTabId;

    return Scaffold(
      appBar: AppBar(
        leading: const AppLogo(),
        title: const Text('Feed'),
        actions: [
          IconButton(
            tooltip: 'Markeer alles als gelezen',
            icon: const Icon(Icons.done_all),
            onPressed: () => _confirmMarkAllRead(context),
          ),
          IconButton(
            tooltip: 'Lijst herladen',
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(feedProvider.notifier).reload(),
          ),
        ],
      ),
      body: Column(
        children: [
          SwitchListTile(
            dense: true,
            title: const Text('Verberg gelezen'),
            value: _hideRead,
            onChanged: (v) => setState(() => _hideRead = v),
          ),
          _MediaFilterBar(
            selected: _mediaFilter,
            onSelected: (f) => setState(() => _mediaFilter = f),
          ),
          _CategoryTabBar(
            tabs: tabs,
            selectedId: _selectedTab,
            onSelected: (id) => setState(() => _selectedTab = id),
            countFor: (id) => _countFor(feed.value ?? const [], id),
          ),
          Expanded(
            child: feed.when(
              data: (items) {
                final filtered = _filter(items);
                return RefreshIndicator(
                  onRefresh: () => ref.read(feedProvider.notifier).reload(),
                  child: filtered.isEmpty
                      ? const Center(child: Text('Geen items'))
                      : ListView.builder(
                          itemCount: filtered.length,
                          itemBuilder: (ctx, i) {
                            final it = filtered[i];
                            return ItemCard(
                              title: it.displayTitle,
                              source: it.source,
                              category: it.category,
                              date: it.publishedDate,
                              relativeTime: formatRelativeTime(it.createdAt),
                              snippet: it.listPreview,
                              isRead: it.isRead,
                              starred: it.starred,
                              liked: it.liked,
                              trailing: it.isSummary ? const Chip(label: Text('Samenvatting')) : null,
                              onTap: () => _open(filtered, i),
                              onStar: () => ref.read(feedProvider.notifier).toggleStar(it.id),
                              onFeedback: (v) => ref.read(feedProvider.notifier).setFeedback(it.id, v),
                              onDelete: () => ref.read(feedProvider.notifier).delete(it.id),
                            );
                          },
                        ),
                );
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(child: Text('Fout: $e')),
            ),
          ),
        ],
      ),
    );
  }

  /// Match een item tegen één tab (categorie-tab óf speciale tab).
  bool _matchesTab(FeedItem it, String tabId) {
    switch (tabId) {
      case _allTabId:
        return true;
      case _starredTabId:
        return it.starred;
      case _summaryTabId:
        return it.isSummary;
      default:
        return it.category == tabId;
    }
  }

  /// KAN-60 (AC8): media-type filter, AND-gecombineerd met categorie-tab.
  bool _matchesMediaFilter(FeedItem it) {
    switch (_mediaFilter) {
      case _MediaFilter.all:
        return true;
      case _MediaFilter.rss:
        return !it.isPodcast;
      case _MediaFilter.podcasts:
        return it.isPodcast;
    }
  }

  /// Telt items die zichtbaar zouden zijn voor `tabId` na verberg-gelezen.
  int _countFor(List<FeedItem> items, String tabId) {
    return items.where((it) {
      if (!_matchesTab(it, tabId)) return false;
      if (!_matchesMediaFilter(it)) return false;
      if (_hideRead && it.isRead) return false;
      return true;
    }).length;
  }

  List<FeedItem> _filter(List<FeedItem> items) {
    return items.where((it) {
      if (!_matchesTab(it, _selectedTab)) return false;
      if (!_matchesMediaFilter(it)) return false;
      if (_hideRead && it.isRead) return false;
      return true;
    }).toList();
  }

  Future<void> _confirmMarkAllRead(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Markeer alles als gelezen?'),
        content: const Text(
          'Alle feed-items worden als gelezen aangemerkt. Deze actie kan via '
          'het detail-scherm per item ongedaan worden gemaakt.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Alles als gelezen')),
        ],
      ),
    );
    if (ok == true) {
      final n = await ref.read(feedProvider.notifier).markAllRead();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(n == null
              ? 'Server kon de actie niet uitvoeren — is je backend up-to-date '
                  'met POST /api/feed/markAllRead? Lokaal staat alles wel op '
                  'gelezen, maar bij een refresh komt de oude state terug.'
              : '$n feed-items als gelezen aangemerkt.'),
          backgroundColor: n == null ? Colors.red : null,
          duration: Duration(seconds: n == null ? 8 : 3),
        ),
      );
    }
  }

  void _open(List<FeedItem> items, int idx) {
    final tapped = items[idx];
    // KAN-62: voor podcast-feed-items routeren we naar het dedicated
    // podcast-detail-scherm. Dat werkt op `RssItem`-objecten, dus
    // zoeken we de matchende RssItem op via `sourceRssIds` (één
    // FeedItem ↔ één onderliggend RssItem voor podcasts). Lukt het
    // niet (bv. RssItem is in een andere user-state, of de matching
    // is verwijderd), dan vallen we terug op de generieke
    // FeedItemDetailScreen — geen blocker, gewoon de korte versie.
    if (tapped.isPodcast) {
      final allRss = ref.read(rssProvider).value ?? const <RssItem>[];
      final podcasts = allRss.where((it) => it.isPodcast).toList();
      final matchId = tapped.sourceRssIds
          .firstWhere((id) => podcasts.any((p) => p.id == id), orElse: () => '');
      if (matchId.isNotEmpty) {
        final initialIdx = podcasts.indexWhere((p) => p.id == matchId);
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => RssPodcastDetailScreen(
            items: podcasts,
            initialIndex: initialIdx < 0 ? 0 : initialIdx,
          ),
        ));
        return;
      }
    }
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => FeedItemDetailScreen(items: items, initialIndex: idx),
    ));
  }
}

class _FeedTab {
  final String id;
  final String name;
  const _FeedTab({required this.id, required this.name});
}

/// KAN-60 (AC8): zelfde 'Alles | RSS | Podcasts'-chip-rij als op de
/// RSS-tab. Sessie-state. Chips zijn altijd zichtbaar — zelfs als er
/// (nog) geen podcast-items in de feed staan; klikken op 'Podcasts'
/// geeft dan 0 items (refiner-aanname).
class _MediaFilterBar extends StatelessWidget {
  final _MediaFilter selected;
  final ValueChanged<_MediaFilter> onSelected;

  const _MediaFilterBar({required this.selected, required this.onSelected});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Wrap(
        spacing: 8,
        children: [
          ChoiceChip(
            label: const Text('Alles'),
            selected: selected == _MediaFilter.all,
            onSelected: (_) => onSelected(_MediaFilter.all),
          ),
          ChoiceChip(
            label: const Text('RSS'),
            avatar: const Icon(Icons.article_outlined, size: 16),
            selected: selected == _MediaFilter.rss,
            onSelected: (_) => onSelected(_MediaFilter.rss),
          ),
          ChoiceChip(
            label: const Text('Podcasts'),
            avatar: const Icon(Icons.podcasts, size: 16),
            selected: selected == _MediaFilter.podcasts,
            onSelected: (_) => onSelected(_MediaFilter.podcasts),
          ),
        ],
      ),
    );
  }
}

/// Horizontaal scrollende rij van tabs met tellers. Geen TabController:
/// selectie via callback zodat we de teller per tab kunnen herrekenen
/// op basis van de andere actieve filters (verberg gelezen).
class _CategoryTabBar extends StatelessWidget {
  final List<_FeedTab> tabs;
  final String selectedId;
  final ValueChanged<String> onSelected;
  final int Function(String tabId) countFor;

  const _CategoryTabBar({
    required this.tabs,
    required this.selectedId,
    required this.onSelected,
    required this.countFor,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: theme.dividerColor)),
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: tabs.map((t) {
            final selected = t.id == selectedId;
            final count = countFor(t.id);
            return InkWell(
              onTap: () => onSelected(t.id),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(mainAxisSize: MainAxisSize.min, children: [
                      Text(
                        t.name,
                        style: TextStyle(
                          color: selected ? theme.colorScheme.primary : theme.textTheme.bodyMedium?.color,
                          fontWeight: selected ? FontWeight.bold : FontWeight.normal,
                        ),
                      ),
                      const SizedBox(width: 6),
                      if (count > 0)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: selected
                                ? theme.colorScheme.primary
                                : theme.colorScheme.surfaceContainerHighest,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Text(
                            '$count',
                            style: TextStyle(
                              fontSize: 12,
                              color: selected
                                  ? theme.colorScheme.onPrimary
                                  : theme.colorScheme.onSurfaceVariant,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                    ]),
                    const SizedBox(height: 6),
                    Container(
                      height: 3,
                      width: 32,
                      decoration: BoxDecoration(
                        color: selected ? theme.colorScheme.primary : Colors.transparent,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            );
          }).toList(),
        ),
      ),
    );
  }
}
