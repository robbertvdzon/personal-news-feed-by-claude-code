import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/models.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';
import 'story_detail_screen.dart';

class ReleasesTab extends ConsumerWidget {
  const ReleasesTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(homeStateProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
          child: Row(
            children: [
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Recent gemerged',
                        style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                    Padding(
                      padding: EdgeInsets.only(top: 2),
                      child: Text('Alle PR\'s die naar main zijn gemerged',
                          style: TextStyle(fontSize: 13)),
                    ),
                  ],
                ),
              ),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: () => ref.invalidate(homeStateProvider),
              ),
            ],
          ),
        ),
        Expanded(
          child: state.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('Fout: $e')),
            data: (s) {
              if (s.closedPrs.isEmpty) {
                return Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(32),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.inbox_outlined,
                                color: Theme.of(context).colorScheme.onSurfaceVariant,
                                size: 40),
                            const SizedBox(height: 12),
                            const Text('Nog niets gemerged',
                                style: TextStyle(fontSize: 16)),
                          ],
                        ),
                      ),
                    ),
                  ),
                );
              }
              return RefreshIndicator(
                onRefresh: () async => ref.invalidate(homeStateProvider),
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(24, 0, 24, 80),
                  children: [
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(8),
                        child: _ClosedTable(rows: s.closedPrs),
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _ClosedTable extends StatelessWidget {
  final List<ClosedPr> rows;
  const _ClosedTable({required this.rows});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, c) {
        final wide = c.maxWidth >= 720;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
              child: Row(
                children: [
                  const Expanded(flex: 3, child: _Hdr('Story / PR')),
                  Expanded(flex: 2, child: _Hdr(wide ? 'Status' : '')),
                  const Expanded(flex: 2, child: _Hdr('Gemerged')),
                  if (wide) const Expanded(child: _Hdr('Runs')),
                  if (wide) const Expanded(flex: 2, child: _Hdr('Tokens')),
                  Expanded(flex: 2, child: _Hdr('Cost')),
                  const SizedBox(width: 12),
                ],
              ),
            ),
            Divider(height: 1, color: scheme.outlineVariant),
            for (int i = 0; i < rows.length; i++) ...[
              if (i > 0) Divider(height: 1, color: scheme.outlineVariant),
              _ClosedRowWidget(row: rows[i], wide: wide),
            ],
          ],
        );
      },
    );
  }
}

class _Hdr extends StatelessWidget {
  final String text;
  const _Hdr(this.text);
  @override
  Widget build(BuildContext context) {
    return Text(text.toUpperCase(),
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          letterSpacing: 0.4,
        ));
  }
}

class _ClosedRowWidget extends StatelessWidget {
  final ClosedPr row;
  final bool wide;
  const _ClosedRowWidget({required this.row, required this.wide});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final hasStoryKey = row.storyKey.isNotEmpty;
    final title = row.title.replaceFirst(RegExp(r'^[A-Z]+-\d+:\s*'), '');
    final shortTitle = title.length > 40 ? '${title.substring(0, 40)}…' : title;
    return InkWell(
      onTap: () {
        if (hasStoryKey) {
          Navigator.of(context).push(
            MaterialPageRoute(
                builder: (_) => StoryDetailScreen(storyKey: row.storyKey)),
          );
        } else {
          launchUrl(Uri.parse(row.htmlUrl));
        }
      },
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(
              flex: 3,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    hasStoryKey ? row.storyKey : 'PR #${row.number}',
                    style:
                        const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                  ),
                  Text(shortTitle.isEmpty ? '—' : shortTitle,
                      style: TextStyle(
                          fontSize: 12, color: scheme.onSurfaceVariant)),
                ],
              ),
            ),
            Expanded(
              flex: 2,
              child: _MergedBadge(),
            ),
            Expanded(
              flex: 2,
              child: Text(
                row.mergedAge.isEmpty ? '—' : '${row.mergedAge} gel.',
                style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
              ),
            ),
            if (wide)
              Expanded(
                child: Text(
                  row.runCount > 0 ? '${row.runCount}' : '—',
                  style: const TextStyle(fontSize: 13),
                ),
              ),
            if (wide)
              Expanded(
                flex: 2,
                child: Text(
                  row.tokensInput > 0
                      ? '${formatTokens(row.tokensInput)} / ${formatTokens(row.tokensOutput)}'
                      : '—',
                  style:
                      TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                ),
              ),
            Expanded(
              flex: 2,
              child: Text(
                row.costUsd > 0 ? '\$${row.costUsd.toStringAsFixed(4)}' : '—',
                style: const TextStyle(fontSize: 13),
              ),
            ),
            Icon(
              hasStoryKey ? Icons.chevron_right : Icons.open_in_new,
              color: scheme.onSurfaceVariant,
              size: 18,
            ),
          ],
        ),
      ),
    );
  }
}

class _MergedBadge extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: const Color(0xFFE6F7EC),
          borderRadius: BorderRadius.circular(6),
        ),
        child: const Text('Gemerged',
            style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: Color(0xFF1E6B3E))),
      ),
    );
  }
}
