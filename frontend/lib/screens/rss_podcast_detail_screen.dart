import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/api_client.dart';
import '../models/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';

/// KAN-62: detail-scherm voor RSS podcast-afleveringen (mediaType=PODCAST).
///
/// Apart van [RssItemDetailScreen] omdat de podcast-detail-weergave drie
/// extra secties heeft die niet relevant zijn voor artikelen:
///   1. Lange Claude-samenvatting (~400-600 woorden, 3-5 alinea's)
///   2. Key takeaways (5-10 bullet-list)
///   3. Inklapbaar ruw Whisper-transcript (lazy-loaded via een aparte
///      API-call zodat de feed-listing niet 50-90k chars per podcast
///      hoeft mee te sturen).
///
/// Bestandsnaam bewust verschillend van het bestaande
/// `podcast_detail_screen.dart` (dat is voor AI-gegenereerde podcasts
/// met scriptText/audioPath — refiner-aanname).
class RssPodcastDetailScreen extends ConsumerStatefulWidget {
  final List<RssItem> items;
  final int initialIndex;

  const RssPodcastDetailScreen({
    super.key,
    required this.items,
    required this.initialIndex,
  });

  @override
  ConsumerState<RssPodcastDetailScreen> createState() =>
      _RssPodcastDetailScreenState();
}

class _RssPodcastDetailScreenState
    extends ConsumerState<RssPodcastDetailScreen> {
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

  /// Pakt het meest actuele RssItem voor het huidige index uit de
  /// provider-state. Zo reflecteren AppBar-knoppen direct de updates
  /// die notifier-acties (like/star/unread) optimistisch doorvoeren.
  RssItem _liveItem(int idx) {
    final base = widget.items[idx];
    final live = ref.watch(rssProvider).value;
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
          IconButton(
            tooltip: 'Vind ik leuk',
            icon: Icon(
              current.liked == true ? Icons.thumb_up : Icons.thumb_up_outlined,
              color: current.liked == true ? Colors.green : null,
            ),
            onPressed: () => ref
                .read(rssProvider.notifier)
                .setFeedback(current.id, current.liked == true ? null : true),
          ),
          IconButton(
            tooltip: 'Niet relevant',
            icon: Icon(
              current.liked == false
                  ? Icons.thumb_down
                  : Icons.thumb_down_outlined,
              color: current.liked == false ? Colors.red : null,
            ),
            onPressed: () => ref.read(rssProvider.notifier).setFeedback(
                current.id, current.liked == false ? null : false),
          ),
          IconButton(
            tooltip: 'Bewaar',
            icon: Icon(
              current.starred ? Icons.star : Icons.star_outline,
              color: current.starred ? Colors.amber : null,
            ),
            onPressed: () =>
                ref.read(rssProvider.notifier).toggleStar(current.id),
          ),
          IconButton(
            tooltip: current.isRead
                ? 'Markeer als ongelezen'
                : 'Markeer als gelezen',
            icon: Icon(current.isRead
                ? Icons.mark_email_unread_outlined
                : Icons.mark_email_read_outlined),
            onPressed: () => ref
                .read(rssProvider.notifier)
                .setRead(current.id, !current.isRead),
          ),
        ],
      ),
      body: PageView.builder(
        controller: _ctrl,
        itemCount: widget.items.length,
        onPageChanged: (i) {
          setState(() => _idx = i);
          ref.read(rssProvider.notifier).setRead(widget.items[i].id, true);
        },
        itemBuilder: (ctx, i) => _PodcastDetailBody(item: _liveItem(i)),
      ),
    );
  }
}

/// Body voor één podcast in de PageView. State-ful want we cachen de
/// gefetchte transcript-tekst lokaal (zodat in-en-uitklappen geen
/// herhaalde HTTP-call doet) en het laad-resultaat per item.
class _PodcastDetailBody extends ConsumerStatefulWidget {
  final RssItem item;
  const _PodcastDetailBody({required this.item});

  @override
  ConsumerState<_PodcastDetailBody> createState() => _PodcastDetailBodyState();
}

class _PodcastDetailBodyState extends ConsumerState<_PodcastDetailBody> {
  Future<String?>? _transcriptFuture;

  Future<String?> _fetchTranscript() async {
    try {
      final r = await ref
          .read(apiProvider)
          .get('/api/rss/${widget.item.id}/transcript');
      if (r is Map && r['transcript'] is String) {
        return r['transcript'] as String;
      }
      return null;
    } on ApiException catch (e) {
      // 404 = nog geen transcript beschikbaar — we tonen dat netjes.
      if (e.statusCode == 404) return null;
      rethrow;
    }
  }

  void _ensureTranscriptStarted() {
    _transcriptFuture ??= _fetchTranscript();
  }

  @override
  Widget build(BuildContext context) {
    final it = widget.item;
    // Bottom-padding compenseert voor Android nav-bar / iOS home-
    // indicator zodat de bron-knoppen niet onder de system gesture-area
    // verdwijnen.
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Header(item: it),
          const SizedBox(height: 12),
          if (it.topics.isNotEmpty) ...[
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: it.topics
                  .map((t) => Chip(
                        label: Text(t),
                        visualDensity: VisualDensity.compact,
                        materialTapTargetSize:
                            MaterialTapTargetSize.shrinkWrap,
                      ))
                  .toList(),
            ),
            const SizedBox(height: 12),
          ],
          _LongSummarySection(item: it),
          const SizedBox(height: 24),
          _KeyTakeawaysSection(item: it),
          const SizedBox(height: 24),
          _TranscriptSection(
            item: it,
            onExpand: _ensureTranscriptStarted,
            future: () => _transcriptFuture,
          ),
          const SizedBox(height: 24),
          if (it.url.isNotEmpty)
            FilledButton.icon(
              onPressed: () =>
                  launchUrl(Uri.parse(it.url), mode: LaunchMode.externalApplication),
              icon: const Icon(Icons.play_circle_outline),
              label: const Text('Origineel afspelen'),
            ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  final RssItem item;
  const _Header({required this.item});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final duration = (item.durationSeconds ?? 0) > 0
        ? '${((item.durationSeconds! + 30) / 60).floor().clamp(1, 999)} min'
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.only(top: 4, right: 8),
            child: Icon(Icons.podcasts, color: theme.colorScheme.primary),
          ),
          Expanded(
            child: Text(item.title, style: theme.textTheme.headlineSmall),
          ),
        ]),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 4, children: [
          if (item.source.isNotEmpty) Chip(label: Text(item.source)),
          Chip(label: Text(item.category)),
          if (duration != null) Chip(label: Text(duration)),
          if (item.publishedDate != null) Chip(label: Text(item.publishedDate!)),
          if (item.isShowNotesBased)
            Tooltip(
              message:
                  'Voorlopige samenvatting op basis van de RSS show-notes. '
                  'Het echte transcript wordt op de achtergrond verwerkt.',
              child: Chip(
                avatar: const Icon(Icons.hourglass_top, size: 14),
                label: const Text('voorlopig'),
                backgroundColor: Colors.amber.shade100,
              ),
            ),
        ]),
      ],
    );
  }
}

/// Sectie 1 — lange Nederlandse samenvatting (~400-600 woorden, 3-5
/// alinea's). Valt terug op de korte [RssItem.summary] als de
/// uitgebreide versie nog niet is gegenereerd (de KAN-62-backfill
/// vult deze achteraf voor bestaande DONE-podcasts).
class _LongSummarySection extends StatelessWidget {
  final RssItem item;
  const _LongSummarySection({required this.item});

  @override
  Widget build(BuildContext context) {
    final long = item.longSummary;
    final hasLong = long != null && long.trim().isNotEmpty;
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Samenvatting', style: theme.textTheme.titleMedium),
        const SizedBox(height: 8),
        if (hasLong)
          MarkdownBody(data: long, selectable: true)
        else ...[
          MarkdownBody(
            data: item.summary.isNotEmpty ? item.summary : item.snippet,
            selectable: true,
          ),
          // Helpt de gebruiker begrijpen waarom de samenvatting nog kort
          // is — vooral relevant voor de 14 bestaande DONE-rijen die
          // wachten op de backfill (binnen ~20-30 min na deploy klaar).
          const SizedBox(height: 6),
          Text(
            'Uitgebreide samenvatting wordt op de achtergrond verwerkt.',
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.hintColor,
              fontStyle: FontStyle.italic,
            ),
          ),
        ],
      ],
    );
  }
}

/// Sectie 2 — bullet-list van 5-10 takeaways. Verborgen wanneer de
/// lijst leeg is (b.v. niet-podcast items of nog-niet-gebackfilled
/// rijen). Eén regel per bullet, geen sub-bullets — exact zoals de
/// story specificeert.
class _KeyTakeawaysSection extends StatelessWidget {
  final RssItem item;
  const _KeyTakeawaysSection({required this.item});

  @override
  Widget build(BuildContext context) {
    final takeaways = item.keyTakeaways;
    if (takeaways.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Key takeaways', style: theme.textTheme.titleMedium),
        const SizedBox(height: 8),
        for (final t in takeaways)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Padding(
                  padding: EdgeInsets.only(top: 7, right: 8),
                  child: Icon(Icons.circle, size: 6),
                ),
                Expanded(child: SelectableText(t)),
              ],
            ),
          ),
      ],
    );
  }
}

/// Sectie 3 — inklapbaar ruw Whisper-transcript. Default ingeklapt;
/// bij eerste open wordt de transcript-tekst via een aparte HTTP-call
/// opgehaald zodat de feed-listing niet 50-90k chars per podcast hoeft
/// mee te sturen. Voor show-notes-based cards (`summarySource='show_notes'`)
/// toont 'ie een placeholder i.p.v. een leeg vak (story AC #5).
class _TranscriptSection extends StatefulWidget {
  final RssItem item;
  final VoidCallback onExpand;
  final Future<String?>? Function() future;

  const _TranscriptSection({
    required this.item,
    required this.onExpand,
    required this.future,
  });

  @override
  State<_TranscriptSection> createState() => _TranscriptSectionState();
}

class _TranscriptSectionState extends State<_TranscriptSection> {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // Cards die nog geen transcript hebben (NEEDS_TRANSCRIPT, summary
    // source = show_notes): toon de placeholder uit AC #5 i.p.v. een
    // 404-laad-knop.
    if (widget.item.isShowNotesBased) {
      return Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(children: [
          const Icon(Icons.hourglass_top, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Transcript wordt nog verwerkt.',
              style: theme.textTheme.bodyMedium,
            ),
          ),
        ]),
      );
    }
    return Theme(
      // ExpansionTile heeft default een divider-border die clasht met
      // de andere secties — uitschakelen via ListTileTheme.
      data: theme.copyWith(dividerColor: Colors.transparent),
      child: ExpansionTile(
        tilePadding: EdgeInsets.zero,
        childrenPadding: const EdgeInsets.only(top: 8, bottom: 8),
        title: Text('Ruw transcript', style: theme.textTheme.titleMedium),
        subtitle: Text(
          'Volledige Whisper-output (lang)',
          style: theme.textTheme.bodySmall?.copyWith(color: theme.hintColor),
        ),
        onExpansionChanged: (open) {
          if (open) {
            setState(widget.onExpand);
          }
        },
        children: [
          FutureBuilder<String?>(
            future: widget.future(),
            builder: (ctx, snap) {
              if (snap.connectionState != ConnectionState.done) {
                return const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16),
                  child: Center(child: CircularProgressIndicator()),
                );
              }
              if (snap.hasError) {
                return Text(
                  'Kon transcript niet ophalen: ${snap.error}',
                  style: TextStyle(color: theme.colorScheme.error),
                );
              }
              final text = snap.data;
              if (text == null || text.trim().isEmpty) {
                return Text(
                  'Geen transcript beschikbaar voor deze aflevering.',
                  style: theme.textTheme.bodyMedium
                      ?.copyWith(fontStyle: FontStyle.italic),
                );
              }
              // SelectableText want gebruikers willen vaak even iets
              // kunnen kopiëren uit de transcript-tekst.
              return Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(
                  text,
                  style: theme.textTheme.bodySmall?.copyWith(height: 1.4),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}
