import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import '../widgets/app_logo.dart';
import 'event_detail_screen.dart';

/// KAN-65: Events-tab. Toont de per-gebruiker ontdekte tech-events,
/// gesorteerd op datum met een duidelijk onderscheid tussen aankomende
/// en al-geweeste events.
class EventsScreen extends ConsumerWidget {
  const EventsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final events = ref.watch(eventsProvider);
    return Scaffold(
      appBar: AppBar(
        leading: const AppLogo(),
        title: const Text('Events'),
        actions: [
          IconButton(
            tooltip: 'Zoek nu naar nieuwe events',
            icon: const Icon(Icons.travel_explore),
            onPressed: () async {
              await ref.read(eventsProvider.notifier).discover();
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Event-zoekopdracht gestart — check straks de lijst')),
                );
              }
            },
          ),
          IconButton(
            tooltip: 'Lijst herladen',
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(eventsProvider.notifier).reload(),
          ),
        ],
      ),
      body: events.when(
        data: (items) {
          if (items.isEmpty) {
            return RefreshIndicator(
              onRefresh: () => ref.read(eventsProvider.notifier).reload(),
              child: ListView(
                children: const [
                  SizedBox(height: 120),
                  Center(child: Text('Nog geen events ontdekt')),
                  SizedBox(height: 8),
                  Center(
                    child: Padding(
                      padding: EdgeInsets.symmetric(horizontal: 32),
                      child: Text(
                        'Gebruik de zoekknop rechtsboven of wacht op de wekelijkse '
                        'automatische zoekopdracht.',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ),
                ],
              ),
            );
          }
          final upcoming = items.where((e) => e.isUpcoming).toList()
            ..sort(_byStartAscending);
          final past = items.where((e) => !e.isUpcoming).toList()
            ..sort(_byStartDescending);

          return RefreshIndicator(
            onRefresh: () => ref.read(eventsProvider.notifier).reload(),
            child: ListView(
              children: [
                if (upcoming.isNotEmpty) ...[
                  const _SectionHeader(label: 'Aankomend', icon: Icons.upcoming),
                  for (final e in upcoming) _EventTile(event: e),
                ],
                if (past.isNotEmpty) ...[
                  const _SectionHeader(label: 'Geweest', icon: Icons.history),
                  for (final e in past) _EventTile(event: e),
                ],
              ],
            ),
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
      ),
    );
  }

  static int _byStartAscending(Event a, Event b) =>
      (a.startDate ?? '9999').compareTo(b.startDate ?? '9999');

  static int _byStartDescending(Event a, Event b) =>
      (b.startDate ?? '').compareTo(a.startDate ?? '');
}

class _SectionHeader extends StatelessWidget {
  final String label;
  final IconData icon;
  const _SectionHeader({required this.label, required this.icon});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Row(children: [
        Icon(icon, size: 18, color: theme.colorScheme.primary),
        const SizedBox(width: 8),
        Text(
          label,
          style: theme.textTheme.titleMedium?.copyWith(
            color: theme.colorScheme.primary,
            fontWeight: FontWeight.bold,
          ),
        ),
      ]),
    );
  }
}

class _EventTile extends StatelessWidget {
  final Event event;
  const _EventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final subtitleParts = <String>[
      if (event.startDate != null && event.startDate!.isNotEmpty) formatEventDate(event),
      if (event.location.isNotEmpty) event.location,
    ];
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: ListTile(
        leading: const Icon(Icons.event),
        title: Text(event.name, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Text([
          if (subtitleParts.isNotEmpty) subtitleParts.join(' · '),
          if (event.organization != null && event.organization!.isNotEmpty) event.organization!,
        ].where((s) => s.isNotEmpty).join('\n')),
        isThreeLine: subtitleParts.isNotEmpty && (event.organization?.isNotEmpty ?? false),
        trailing: Chip(
          visualDensity: VisualDensity.compact,
          label: Text(event.category),
        ),
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => EventDetailScreen(event: event)),
        ),
      ),
    );
  }
}

/// Formatteert de datum-range van een event in het Nederlands.
/// "17–20 maart 2026" of "17 maart 2026" of de ruwe string als parsen faalt.
String formatEventDate(Event event) {
  final start = event.startDate;
  if (start == null || start.isEmpty) return 'datum onbekend';
  final s = DateTime.tryParse(start);
  if (s == null) return start;
  final end = event.endDate != null ? DateTime.tryParse(event.endDate!) : null;
  if (end == null || (end.year == s.year && end.month == s.month && end.day == s.day)) {
    return '${s.day} ${_months[s.month - 1]} ${s.year}';
  }
  if (end.month == s.month && end.year == s.year) {
    return '${s.day}–${end.day} ${_months[s.month - 1]} ${s.year}';
  }
  return '${s.day} ${_months[s.month - 1]} – ${end.day} ${_months[end.month - 1]} ${end.year}';
}

const _months = [
  'januari', 'februari', 'maart', 'april', 'mei', 'juni',
  'juli', 'augustus', 'september', 'oktober', 'november', 'december',
];
