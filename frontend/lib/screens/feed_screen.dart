import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import '../widgets/feed_card.dart';
import 'feed_detail_screen.dart';

class FeedScreen extends ConsumerStatefulWidget {
  const FeedScreen({super.key});

  @override
  ConsumerState<FeedScreen> createState() => _FeedScreenState();
}

class _FeedScreenState extends ConsumerState<FeedScreen> {
  String? _selectedCategory;
  bool _summaryOnly = false;
  bool _starredOnly = false;
  bool _showRead = false;

  @override
  Widget build(BuildContext context) {
    final feed = ref.watch(feedProvider);
    final settings = ref.watch(settingsProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Feed'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: () => ref.read(feedProvider.notifier).reload()),
        ],
      ),
      body: Column(
        children: [
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
                          onSelected: (s) => setState(
                              () => _selectedCategory = s ? c.id : null),
                        ),
                      ),
                    ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: FilterChip(
                    label: const Text('Samenvatting'),
                    selected: _summaryOnly,
                    onSelected: (v) => setState(() => _summaryOnly = v),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: FilterChip(
                    label: const Text('Bewaard'),
                    selected: _starredOnly,
                    onSelected: (v) => setState(() => _starredOnly = v),
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
                              title: it.title,
                              source: it.source,
                              category: it.category,
                              date: it.publishedDate,
                              snippet: it.summary,
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

  List<FeedItem> _filter(List<FeedItem> items) {
    return items.where((it) {
      if (_selectedCategory != null && it.category != _selectedCategory) return false;
      if (_summaryOnly && !it.isSummary) return false;
      if (_starredOnly && !it.starred) return false;
      if (!_showRead && it.isRead) return false;
      return true;
    }).toList();
  }

  void _open(List<FeedItem> items, int idx) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => FeedItemDetailScreen(items: items, initialIndex: idx),
    ));
  }
}
