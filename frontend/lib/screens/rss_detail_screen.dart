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
                  Chip(label: Text(it.inFeed ? 'in feed' : 'niet in feed'),
                      backgroundColor: it.inFeed ? Colors.green.shade100 : null),
                ]),
                const SizedBox(height: 16),
                Text(it.summary.isEmpty ? it.snippet : it.summary),
                if (it.feedReason.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Reden: ${it.feedReason}', style: Theme.of(context).textTheme.bodySmall),
                ],
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
