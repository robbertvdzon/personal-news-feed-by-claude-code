import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import '../util/time_format.dart';
import '../widgets/feed_card.dart';
import 'rss_detail_screen.dart';

class RssScreen extends ConsumerStatefulWidget {
  const RssScreen({super.key});

  @override
  ConsumerState<RssScreen> createState() => _RssScreenState();
}

class _RssScreenState extends ConsumerState<RssScreen> {
  String? _selectedCategory;
  bool _otherOnly = false;
  bool _hideRead = true;

  @override
  Widget build(BuildContext context) {
    final rss = ref.watch(rssProvider);
    final settings = ref.watch(settingsProvider);
    return Scaffold(
      appBar: AppBar(
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
        // Eigen, altijd-zichtbare 'Verberg gelezen' switch — los van de
        // categorie-chips zodat het verschil tussen filter en weergave-
        // optie duidelijk blijft.
        SwitchListTile(
          dense: true,
          title: const Text('Verberg gelezen'),
          value: _hideRead,
          onChanged: (v) => setState(() => _hideRead = v),
        ),
        settings.when(
          data: (cats) => SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            child: Row(children: [
              ...cats.where((c) => c.enabled).map(
                    (c) => Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 4),
                      child: ChoiceChip(
                        label: Text(c.name),
                        selected: _selectedCategory == c.id,
                        onSelected: (s) => setState(() => _selectedCategory = s ? c.id : null),
                      ),
                    ),
                  ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: FilterChip(
                  label: const Text('Overig'),
                  selected: _otherOnly,
                  onSelected: (v) => setState(() => _otherOnly = v),
                ),
              ),
            ]),
          ),
          loading: () => const SizedBox.shrink(),
          error: (e, _) => const SizedBox.shrink(),
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
                            // Toon liever de Nederlandse AI-samenvatting; val terug op
                            // de ruwe RSS-snippet als die nog niet beoordeeld is.
                            snippet: it.summary.isNotEmpty ? it.summary : it.snippet,
                            isRead: it.isRead,
                            starred: it.starred,
                            liked: it.liked,
                            trailing: Tooltip(
                              message: it.feedReason,
                              child: Chip(
                                visualDensity: VisualDensity.compact,
                                label: Text(it.inFeed ? 'in feed' : 'niet in feed'),
                                backgroundColor: it.inFeed ? Colors.green.shade100 : null,
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

  List<RssItem> _filter(List<RssItem> items) {
    return items.where((it) {
      if (_otherOnly && it.category != 'overig') return false;
      if (_selectedCategory != null && it.category != _selectedCategory) return false;
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
      await ref.read(rssProvider.notifier).markAllRead();
    }
  }

  void _open(List<RssItem> items, int idx) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => RssItemDetailScreen(items: items, initialIndex: idx),
    ));
  }
}
