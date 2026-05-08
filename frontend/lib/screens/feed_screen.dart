import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import '../util/time_format.dart';
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
  bool _hideRead = true;

  @override
  Widget build(BuildContext context) {
    final feed = ref.watch(feedProvider);
    final settings = ref.watch(settingsProvider);
    return Scaffold(
      appBar: AppBar(
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
          // Eigen, altijd-zichtbare 'Verberg gelezen' switch — los van de
          // categorie-chips zodat het verschil tussen filter (categorie/
          // samenvatting/ster) en weergave-optie (gelezen wel/niet tonen)
          // duidelijk blijft.
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

  List<FeedItem> _filter(List<FeedItem> items) {
    return items.where((it) {
      if (_selectedCategory != null && it.category != _selectedCategory) return false;
      if (_summaryOnly && !it.isSummary) return false;
      if (_starredOnly && !it.starred) return false;
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
      await ref.read(feedProvider.notifier).markAllRead();
    }
  }

  void _open(List<FeedItem> items, int idx) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => FeedItemDetailScreen(items: items, initialIndex: idx),
    ));
  }
}
