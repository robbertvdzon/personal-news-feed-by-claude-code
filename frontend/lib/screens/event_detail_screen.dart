import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
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
