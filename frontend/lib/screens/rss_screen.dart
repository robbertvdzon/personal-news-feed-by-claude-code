import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import '../util/time_format.dart';
import '../widgets/app_logo.dart';
import '../widgets/feed_card.dart';
import 'rss_detail_screen.dart';

/// Speciale "tab"-id voor "alle items, geen categorie-filter".
const _allTabId = '__all__';
/// Speciale "tab"-id voor cross-cutting filter "alleen bewaarde items".
const _starredTabId = '__starred__';
/// Speciale "tab"-id voor de Overig-categorie (vangnet: items zonder
/// of met systeem-categorie). Apart in de tab-rij zodat je 'm los
/// kunt aanklikken.
const _otherTabId = 'overig';

class RssScreen extends ConsumerStatefulWidget {
  const RssScreen({super.key});

  @override
  ConsumerState<RssScreen> createState() => _RssScreenState();
}

class _RssScreenState extends ConsumerState<RssScreen> {
  String _selectedTab = _allTabId;
  bool _hideRead = true;

  @override
  Widget build(BuildContext context) {
    final rss = ref.watch(rssProvider);
    final settings = ref.watch(settingsProvider);
    final cats = settings.value ?? const <CategorySettings>[];

    final tabs = <_RssTab>[
      const _RssTab(id: _allTabId, name: 'Alles'),
      const _RssTab(id: _starredTabId, name: 'Bewaard'),
      // Niet-systeem categorieën die ingeschakeld zijn
      ...cats.where((c) => c.enabled && !c.isSystem).map(
            (c) => _RssTab(id: c.id, name: c.name),
          ),
      // Overig (systeem-categorie) altijd als laatste tab.
      const _RssTab(id: _otherTabId, name: 'Overig'),
    ];
    if (!tabs.any((t) => t.id == _selectedTab)) _selectedTab = _allTabId;

    return Scaffold(
      appBar: AppBar(
        leading: const AppLogo(),
        title: const Text('RSS'),
        actions: [
          IconButton(
            tooltip: 'Vernieuwen van bron — haal nieuwe artikelen op uit alle feeds',
            icon: const Icon(Icons.cloud_download),
            onPressed: () async {
              await ref.read(rssProvider.notifier).refresh();
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Verversing gestart')));
              }
            },
          ),
          IconButton(
            tooltip: 'AI feed-selectie opnieuw uitvoeren op bestaande items',
            icon: const Icon(Icons.auto_awesome),
            onPressed: () async {
              await ref.read(rssProvider.notifier).reselect();
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('AI-selectie opnieuw gestart — check backend log')));
              }
            },
          ),
          IconButton(
            tooltip: 'Markeer alles als gelezen',
            icon: const Icon(Icons.done_all),
            onPressed: () => _confirmMarkAllRead(context),
          ),
          IconButton(
            tooltip: 'Lijst herladen',
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(rssProvider.notifier).reload(),
          ),
        ],
      ),
      body: Column(children: [
        SwitchListTile(
          dense: true,
          title: const Text('Verberg gelezen'),
          value: _hideRead,
          onChanged: (v) => setState(() => _hideRead = v),
        ),
        _RssCategoryTabBar(
          tabs: tabs,
          selectedId: _selectedTab,
          onSelected: (id) => setState(() => _selectedTab = id),
          countFor: (id) => _countFor(rss.value ?? const [], id),
        ),
        Expanded(
          child: rss.when(
            data: (items) {
              final filtered = _filter(items);
              return RefreshIndicator(
                onRefresh: () => ref.read(rssProvider.notifier).reload(),
                child: filtered.isEmpty
                    ? const Center(child: Text('Geen RSS items'))
                    : ListView.builder(
                        itemCount: filtered.length,
                        itemBuilder: (ctx, i) {
                          final it = filtered[i];
                          return ItemCard(
                            title: it.title,
                            source: it.source,
                            category: it.category,
                            date: it.publishedDate,
                            relativeTime: formatRelativeTime(it.timestamp),
                            snippet: it.summary.isNotEmpty ? it.summary : it.snippet,
                            isRead: it.isRead,
                            starred: it.starred,
                            liked: it.liked,
                            backgroundColor: it.inFeed
                                ? Colors.purpleAccent.shade100
                                : Colors.yellow.shade100,
                            trailing: Tooltip(
                              message: it.feedReason,
                              child: Chip(
                                visualDensity: VisualDensity.compact,
                                label: Text(it.inFeed ? 'in feed' : 'niet in feed'),
                                backgroundColor: it.inFeed ? Colors.purpleAccent.shade100 : null,
                              ),
                            ),
                            onTap: () => _open(filtered, i),
                            onStar: () => ref.read(rssProvider.notifier).toggleStar(it.id),
                            onFeedback: (v) => ref.read(rssProvider.notifier).setFeedback(it.id, v),
                            onDelete: () => ref.read(rssProvider.notifier).delete(it.id),
                          );
                        },
                      ),
              );
            },
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('Fout: $e')),
          ),
        ),
      ]),
    );
  }

  int _countFor(List<RssItem> items, String tabId) {
    return items.where((it) {
      if (!_matchesTab(it, tabId)) return false;
      if (_hideRead && it.isRead) return false;
      return true;
    }).length;
  }

  List<RssItem> _filter(List<RssItem> items) {
    return items.where((it) {
      if (!_matchesTab(it, _selectedTab)) return false;
      if (_hideRead && it.isRead) return false;
      return true;
    }).toList();
  }

  bool _matchesTab(RssItem it, String tabId) {
    if (tabId == _allTabId) return true;
    if (tabId == _starredTabId) return it.starred;
    if (tabId == _otherTabId) {
      // Overig = items zonder of met de systeem-categorie 'overig'.
      return it.category.isEmpty || it.category == 'overig';
    }
    return it.category == tabId;
  }

  Future<void> _confirmMarkAllRead(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Markeer alles als gelezen?'),
        content: const Text(
          'Alle RSS-items worden als gelezen aangemerkt. Deze actie kan via '
          'het detail-scherm per item ongedaan worden gemaakt.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Alles als gelezen')),
        ],
      ),
    );
    if (ok == true) {
      final n = await ref.read(rssProvider.notifier).markAllRead();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(n == null
              ? 'Server kon de actie niet uitvoeren — is je backend up-to-date '
                  'met POST /api/rss/markAllRead? Lokaal staat alles wel op '
                  'gelezen, maar bij een refresh komt de oude state terug.'
              : '$n RSS-items als gelezen aangemerkt.'),
          backgroundColor: n == null ? Colors.red : null,
          duration: Duration(seconds: n == null ? 8 : 3),
        ),
      );
    }
  }

  void _open(List<RssItem> items, int idx) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => RssItemDetailScreen(items: items, initialIndex: idx),
    ));
  }
}

class _RssTab {
  final String id;
  final String name;
  const _RssTab({required this.id, required this.name});
}

/// Tab-bar voor RSS — bijna identiek aan de FeedScreen-versie, maar
/// op `_RssTab` getypeerd. Niet uitgefactored omdat de typed lijst
/// kort is en herhalen leesbaarder is dan een generiek-typed widget.
class _RssCategoryTabBar extends StatelessWidget {
  final List<_RssTab> tabs;
  final String selectedId;
  final ValueChanged<String> onSelected;
  final int Function(String tabId) countFor;

  const _RssCategoryTabBar({
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
