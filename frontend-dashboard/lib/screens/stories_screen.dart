import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/models.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';
import 'story_detail_screen.dart';

class StoriesScreen extends ConsumerWidget {
  const StoriesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncStories = ref.watch(storiesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Alle stories')),
      body: asyncStories.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (rows) => RefreshIndicator(
          onRefresh: () async => ref.invalidate(storiesProvider),
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: rows.length,
            separatorBuilder: (_, __) => const SizedBox(height: 10),
            itemBuilder: (_, i) => _StoryTile(row: rows[i]),
          ),
        ),
      ),
    );
  }
}

class _StoryTile extends StatelessWidget {
  final StoryRow row;
  const _StoryTile({required this.row});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = row.finalStatus.isEmpty ? 'lopend' : row.finalStatus;
    final done = status.toLowerCase() == 'gereed' ||
        status.toLowerCase() == 'klaar' ||
        status.toLowerCase() == 'done';
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => StoryDetailScreen(storyKey: row.storyKey),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: done
                          ? scheme.tertiaryContainer
                          : scheme.secondaryContainer,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Icon(
                      done ? Icons.check_circle_outline : Icons.pending_outlined,
                      color: done
                          ? scheme.onTertiaryContainer
                          : scheme.onSecondaryContainer,
                      size: 20,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(row.storyKey,
                            style: const TextStyle(
                                fontSize: 15, fontWeight: FontWeight.w700)),
                        Text(
                          '${formatTs(row.startedAt)} · $status',
                          style: TextStyle(
                              color: scheme.onSurfaceVariant, fontSize: 12),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  TokenChip(
                      icon: Icons.smart_toy_outlined,
                      label: 'agents',
                      value: '${row.runCount}'),
                  TokenChip(
                      icon: Icons.schedule,
                      label: 'agent-tijd',
                      value: formatSeconds(row.durationMsSum ~/ 1000)),
                  TokenChip(
                      icon: Icons.arrow_downward,
                      label: 'in',
                      value: formatTokens(row.input)),
                  TokenChip(
                      icon: Icons.arrow_upward,
                      label: 'out',
                      value: formatTokens(row.output)),
                  TokenChip(
                      icon: Icons.attach_money,
                      label: 'cost',
                      value: '\$${row.costUsd.toStringAsFixed(4)}'),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
