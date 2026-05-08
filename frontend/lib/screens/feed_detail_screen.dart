import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';

class FeedItemDetailScreen extends ConsumerStatefulWidget {
  final List<FeedItem> items;
  final int initialIndex;

  const FeedItemDetailScreen({super.key, required this.items, required this.initialIndex});

  @override
  ConsumerState<FeedItemDetailScreen> createState() => _FeedItemDetailScreenState();
}

class _FeedItemDetailScreenState extends ConsumerState<FeedItemDetailScreen> {
  late PageController _ctrl;
  late int _idx;

  @override
  void initState() {
    super.initState();
    _idx = widget.initialIndex;
    _ctrl = PageController(initialPage: widget.initialIndex);
    Future.microtask(() {
      ref.read(feedProvider.notifier).setRead(widget.items[_idx].id, true);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('${_idx + 1}/${widget.items.length}'),
      ),
      body: PageView.builder(
        controller: _ctrl,
        itemCount: widget.items.length,
        onPageChanged: (i) {
          setState(() => _idx = i);
          ref.read(feedProvider.notifier).setRead(widget.items[i].id, true);
        },
        itemBuilder: (ctx, i) => _itemView(widget.items[i]),
      ),
    );
  }

  Widget _itemView(FeedItem it) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(it.title, style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 8),
          Wrap(spacing: 8, children: [
            if (it.source.isNotEmpty) Chip(label: Text(it.source)),
            Chip(label: Text(it.category)),
            if (it.publishedDate != null) Chip(label: Text(it.publishedDate!)),
          ]),
          const SizedBox(height: 16),
          if (it.isSummary)
            MarkdownBody(data: it.summary, selectable: true)
          else
            SelectableText(it.summary),
          const SizedBox(height: 24),
          if (it.url != null)
            FilledButton.icon(
              onPressed: () => launchUrl(Uri.parse(it.url!), mode: LaunchMode.externalApplication),
              icon: const Icon(Icons.open_in_new),
              label: const Text('Open bron'),
            ),
        ],
      ),
    );
  }
}
