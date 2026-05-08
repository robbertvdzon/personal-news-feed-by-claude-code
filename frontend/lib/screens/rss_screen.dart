import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
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
  bool _showRead = false;

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
              if (mounted) ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Verversing gestart')));
            },
          ),
          IconButton(
            tooltip: 'AI feed-selectie opnieuw uitvoeren op bestaande items',
            icon: const Icon(Icons.auto_awesome),
            onPressed: () async {
              await ref.read(rssProvider.notifier).reselect();
              if (mounted) ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('AI-selectie opnieuw gestart — check backend log')));
            },
          ),
          IconButton(
            tooltip: 'Lijst herladen',
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(rssProvider.notifier).reload(),
          ),
        ],
      ),
      body: Column(children: [
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
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: FilterChip(
                  label: Text(_showRead ? 'Verberg gelezen' : 'Toon gelezen'),
                  selected: _showRead,
                  onSelected: (v) => setState(() => _showRead = v),
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
      if (!_showRead && it.isRead) return false;
      return true;
    }).toList();
  }

  void _open(List<RssItem> items, int idx) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => RssItemDetailScreen(items: items, initialIndex: idx),
    ));
  }
}
