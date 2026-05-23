import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/api_client.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import 'events_screen.dart' show formatEventDate;

/// KAN-65: detailscherm van één tech-event met de Nederlandse
/// beschrijving, datum, locatie en bronlinks.
class EventDetailScreen extends ConsumerWidget {
  final Event event;
  const EventDetailScreen({super.key, required this.event});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return Scaffold(
      appBar: AppBar(
        title: Text(event.isUpcoming ? 'Aankomend event' : 'Event'),
        actions: [
          IconButton(
            tooltip: 'Verwijderen',
            icon: const Icon(Icons.delete_outline),
            onPressed: () async {
              await ref.read(eventsProvider.notifier).delete(event.id);
              if (context.mounted) Navigator.of(context).pop();
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(event.name, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 4, children: [
              Chip(
                avatar: const Icon(Icons.event, size: 16),
                label: Text(formatEventDate(event)),
              ),
              if (event.location.isNotEmpty)
                Chip(
                  avatar: const Icon(Icons.place, size: 16),
                  label: Text(event.location),
                ),
              if (event.organization != null && event.organization!.isNotEmpty)
                Chip(
                  avatar: const Icon(Icons.business, size: 16),
                  label: Text(event.organization!),
                ),
              Chip(label: Text(event.category)),
            ]),
            const SizedBox(height: 20),
            if (event.description.isNotEmpty) ...[
              Text('Onderwerpen', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 6),
              SelectableText(event.description),
              const SizedBox(height: 20),
            ],
            _VideosSection(eventId: event.id),
            if (event.sourceLinks.isNotEmpty) ...[
              Text('Bronnen', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 6),
              for (final link in event.sourceLinks)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.link),
                  title: Text(link, maxLines: 2, overflow: TextOverflow.ellipsis),
                  onTap: () => launchUrl(Uri.parse(link), mode: LaunchMode.externalApplication),
                ),
            ],
          ],
        ),
      ),
    );
  }
}

/// KAN-66: lijst van de ontdekte video's (keynotes/sessies) van het event.
/// Een tik opent de externe video-URL in de systeembrowser. Toont niets
/// zolang er nog geen video's zijn ontdekt.
///
/// KAN-67: per video een uitvouwbare kaart met titel, beschrijving en —
/// indien beschikbaar — de Nederlandse samenvatting. Heeft die nog geen
/// samenvatting dan staat er een "Maak samenvatting"-knop die de backend-
/// pipeline triggert (transcript + Claude). Tijdens het laden draait een
/// spinner en is de knop disabled; bij fout (HTTP 502 — geen transcript)
/// toont het een snackbar en blijft de knop staan voor een nieuwe poging.
class _VideosSection extends ConsumerWidget {
  final String eventId;
  const _VideosSection({required this.eventId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final videos = ref.watch(eventVideosProvider(eventId));
    return videos.when(
      data: (items) {
        if (items.isEmpty) return const SizedBox.shrink();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text("Video's", style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            for (final v in items) _VideoCard(eventId: eventId, video: v),
            const SizedBox(height: 20),
          ],
        );
      },
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
    );
  }
}

/// Kaart per video. Vouwt de samenvatting + knop in het hoofd; het
/// openen van de video gaat via een trailing-icoon-knop zodat het
/// indrukken van de kaart de samenvatting toont/maakt i.p.v. de
/// browser te openen.
class _VideoCard extends ConsumerStatefulWidget {
  final String eventId;
  final EventVideo video;
  const _VideoCard({required this.eventId, required this.video});

  @override
  ConsumerState<_VideoCard> createState() => _VideoCardState();
}

class _VideoCardState extends ConsumerState<_VideoCard> {
  bool _loading = false;

  Future<void> _requestSummary() async {
    setState(() => _loading = true);
    try {
      await requestVideoSummary(
        ref,
        eventId: widget.eventId,
        videoUrl: widget.video.videoUrl,
      );
    } on ApiException catch (e) {
      if (!mounted) return;
      final msg = e.statusCode == 502
          ? 'Samenvatting kon niet worden gemaakt — probeer het later opnieuw.'
          : 'Samenvatting mislukt (HTTP ${e.statusCode}).';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Samenvatting mislukt — controleer je verbinding.')),
      );
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final v = widget.video;
    final hasSummary = v.summaryNl != null && v.summaryNl!.isNotEmpty;
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 8, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Padding(
                  padding: EdgeInsets.only(top: 2),
                  child: Icon(Icons.play_circle_outline),
                ),
                const SizedBox(width: 8),
                Expanded(child: Text(v.title, style: theme.textTheme.titleSmall)),
                IconButton(
                  tooltip: 'Open video',
                  icon: const Icon(Icons.open_in_new, size: 20),
                  onPressed: () => launchUrl(
                    Uri.parse(v.videoUrl),
                    mode: LaunchMode.externalApplication,
                  ),
                ),
              ],
            ),
            if (v.descriptionNl != null && v.descriptionNl!.isNotEmpty) ...[
              const SizedBox(height: 4),
              Padding(
                padding: const EdgeInsets.only(left: 32),
                child: Text(v.descriptionNl!, style: theme.textTheme.bodyMedium),
              ),
            ],
            const SizedBox(height: 8),
            Padding(
              padding: const EdgeInsets.only(left: 32),
              child: hasSummary
                  ? _SummaryBlock(summary: v.summaryNl!)
                  : _SummaryButton(loading: _loading, onPressed: _requestSummary),
            ),
          ],
        ),
      ),
    );
  }
}

class _SummaryButton extends StatelessWidget {
  final bool loading;
  final VoidCallback onPressed;
  const _SummaryButton({required this.loading, required this.onPressed});

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: const [
          SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          SizedBox(width: 10),
          Text('Samenvatting wordt gemaakt…'),
        ],
      );
    }
    return Align(
      alignment: Alignment.centerLeft,
      child: OutlinedButton.icon(
        icon: const Icon(Icons.auto_awesome, size: 18),
        label: const Text('Maak samenvatting'),
        onPressed: onPressed,
      ),
    );
  }
}

class _SummaryBlock extends StatelessWidget {
  final String summary;
  const _SummaryBlock({required this.summary});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      padding: const EdgeInsets.all(10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.auto_awesome,
                  size: 16, color: theme.colorScheme.primary),
              const SizedBox(width: 6),
              Text(
                'Nederlandse samenvatting',
                style: theme.textTheme.labelMedium
                    ?.copyWith(color: theme.colorScheme.primary),
              ),
            ],
          ),
          const SizedBox(height: 6),
          SelectableText(summary, style: theme.textTheme.bodyMedium),
        ],
      ),
    );
  }
}
