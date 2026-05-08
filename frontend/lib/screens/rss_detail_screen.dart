import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';

class RssItemDetailScreen extends ConsumerStatefulWidget {
  final List<RssItem> items;
  final int initialIndex;

  const RssItemDetailScreen({super.key, required this.items, required this.initialIndex});

  @override
  ConsumerState<RssItemDetailScreen> createState() => _RssItemDetailScreenState();
}

class _RssItemDetailScreenState extends ConsumerState<RssItemDetailScreen> {
  late PageController _ctrl;
  late int _idx;

  @override
  void initState() {
    super.initState();
    _idx = widget.initialIndex;
    _ctrl = PageController(initialPage: _idx);
    Future.microtask(() {
      ref.read(rssProvider.notifier).setRead(widget.items[_idx].id, true);
    });
  }

  Future<void> _moreAbout(RssItem it) async {
    final controller = TextEditingController(text: it.title);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Meer hierover'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(labelText: 'Onderwerp'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Maak verzoek')),
        ],
      ),
    );
    if (ok != true) return;
    await ref.read(requestProvider.notifier).create(
          subject: controller.text,
          sourceItemId: it.id,
          sourceItemTitle: it.title,
        );
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Verzoek gemaakt')));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('${_idx + 1}/${widget.items.length}')),
      body: PageView.builder(
        controller: _ctrl,
        itemCount: widget.items.length,
        onPageChanged: (i) {
          setState(() => _idx = i);
          ref.read(rssProvider.notifier).setRead(widget.items[i].id, true);
        },
        itemBuilder: (ctx, i) {
          final it = widget.items[i];
          return SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(it.title, style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 8),
                Wrap(spacing: 8, children: [
                  if (it.source.isNotEmpty)
                    InkWell(
                      onTap: () {
                        final u = it.feedUrl.isNotEmpty ? it.feedUrl : it.url;
                        if (u.isNotEmpty) launchUrl(Uri.parse(u), mode: LaunchMode.externalApplication);
                      },
                      child: Chip(label: Text(it.source)),
                    ),
                  Chip(label: Text(it.category)),
                  if (it.publishedDate != null) Chip(label: Text(it.publishedDate!)),
                ]),
                const SizedBox(height: 12),
                _FeedReasonBanner(item: it),
                if (it.topics.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: it.topics
                        .map((t) => Chip(
                              label: Text(t),
                              visualDensity: VisualDensity.compact,
                              materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                            ))
                        .toList(),
                  ),
                ],
                const SizedBox(height: 16),
                Text(it.summary.isEmpty ? it.snippet : it.summary),
                const SizedBox(height: 24),
                Wrap(spacing: 8, children: [
                  if (it.url.isNotEmpty)
                    FilledButton.icon(
                      onPressed: () => launchUrl(Uri.parse(it.url), mode: LaunchMode.externalApplication),
                      icon: const Icon(Icons.open_in_new),
                      label: const Text('Open bron'),
                    ),
                  OutlinedButton.icon(
                    onPressed: () => _moreAbout(it),
                    icon: const Icon(Icons.search),
                    label: const Text('Meer hierover'),
                  ),
                ]),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _FeedReasonBanner extends StatelessWidget {
  final RssItem item;
  const _FeedReasonBanner({required this.item});

  @override
  Widget build(BuildContext context) {
    final inFeed = item.inFeed;
    final color = inFeed ? Colors.green : Colors.orange;
    final icon = inFeed ? Icons.check_circle : Icons.info_outline;
    final headline = inFeed ? 'In persoonlijke feed' : 'Niet in persoonlijke feed';
    final reason = item.feedReason.isNotEmpty
        ? item.feedReason
        : 'Geen reden door AI gegeven (mogelijk nog niet beoordeeld of API-key ontbreekt).';
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        border: Border.all(color: color.withValues(alpha: 0.4)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(headline, style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 4),
                Text(reason),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
