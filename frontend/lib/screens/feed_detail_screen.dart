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

  /// Pakt het meest actuele FeedItem voor het huidige index uit de
  /// provider-state. Zo reflecteren AppBar-knoppen direct de updates die
  /// notifier-acties (like/star/unread) optimistisch doorvoeren.
  FeedItem _liveItem(int idx) {
    final base = widget.items[idx];
    final live = ref.watch(feedProvider).value;
    if (live == null) return base;
    for (final it in live) {
      if (it.id == base.id) return it;
    }
    return base;
  }

  @override
  Widget build(BuildContext context) {
    final current = _liveItem(_idx);
    return Scaffold(
      appBar: AppBar(
        title: Text('${_idx + 1}/${widget.items.length}'),
        actions: [
          // Like
          IconButton(
            tooltip: 'Vind ik leuk',
            icon: Icon(
              current.liked == true ? Icons.thumb_up : Icons.thumb_up_outlined,
              color: current.liked == true ? Colors.green : null,
            ),
            onPressed: () => ref.read(feedProvider.notifier)
                .setFeedback(current.id, current.liked == true ? null : true),
          ),
          // Dislike
          IconButton(
            tooltip: 'Niet relevant',
            icon: Icon(
              current.liked == false ? Icons.thumb_down : Icons.thumb_down_outlined,
              color: current.liked == false ? Colors.red : null,
            ),
            onPressed: () => ref.read(feedProvider.notifier)
                .setFeedback(current.id, current.liked == false ? null : false),
          ),
          // Star
          IconButton(
            tooltip: 'Bewaar',
            icon: Icon(
              current.starred ? Icons.star : Icons.star_outline,
              color: current.starred ? Colors.amber : null,
            ),
            onPressed: () => ref.read(feedProvider.notifier).toggleStar(current.id),
          ),
          // Read/unread toggle. Item wordt automatisch op gelezen gezet bij
          // openen + paginate; deze knop laat je hem terug op ongelezen
          // zetten (of weer op gelezen als je 'm per ongeluk hebt geopend).
          IconButton(
            tooltip: current.isRead ? 'Markeer als ongelezen' : 'Markeer als gelezen',
            icon: Icon(
              current.isRead ? Icons.mark_email_unread_outlined : Icons.mark_email_read_outlined,
            ),
            onPressed: () => ref.read(feedProvider.notifier)
                .setRead(current.id, !current.isRead),
          ),
        ],
      ),
      body: PageView.builder(
        controller: _ctrl,
        itemCount: widget.items.length,
        onPageChanged: (i) {
          setState(() => _idx = i);
          ref.read(feedProvider.notifier).setRead(widget.items[i].id, true);
        },
        itemBuilder: (ctx, i) => _itemView(_liveItem(i)),
      ),
    );
  }

  Widget _itemView(FeedItem it) {
    // Bottom-padding compenseert voor Android nav-bar / iOS home-indicator
    // zodat de "Open bron"-knop onderin niet onder de system gesture-area
    // verdwijnt.
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Headline = korte Nederlandse titel (titleNl). Voor legacy items
          // zonder titleNl valt displayTitle terug op het originele Engels.
          Text(it.displayTitle, style: Theme.of(context).textTheme.headlineSmall),
          // Originele (vaak Engelse) titel klein eronder als 'ie verschilt —
          // zo blijft de bron-titel herkenbaar voor wie het origineel zoekt.
          if (it.titleNl.isNotEmpty && it.titleNl != it.title) ...[
            const SizedBox(height: 4),
            Text(
              it.title,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).hintColor,
                    fontStyle: FontStyle.italic,
                  ),
            ),
          ],
          const SizedBox(height: 8),
          Wrap(spacing: 8, children: [
            if (it.source.isNotEmpty) Chip(label: Text(it.source)),
            Chip(label: Text(it.category)),
            if (it.publishedDate != null) Chip(label: Text(it.publishedDate!)),
          ]),
          const SizedBox(height: 16),
          // Alle samenvattingen als Markdown + selecteerbaar: Claude levert
          // headers, vet/cursief en lijsten in zowel de uitgebreide
          // feed-samenvatting (400-600 woorden) als de dagelijkse briefing
          // (600-1000 woorden). Selectable=true werkt op Flutter web zodat
          // je tekst kunt kopiëren met cmd/ctrl+c.
          MarkdownBody(data: it.summary, selectable: true),
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
