import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/models.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';
import 'story_detail_screen.dart';

const _phaseOrder = ['refine', 'develop', 'review', 'test'];

// Mapping van JIRA-phase (uit poller) naar (stage-index, status).
// status: 'done', 'active', 'fail', 'idle'
({int stageIdx, String status}) _phaseToStage(String phase) {
  switch (phase) {
    case 'refining':         return (stageIdx: 0, status: 'active');
    case 'refined':          return (stageIdx: 0, status: 'done');
    case 'developing':       return (stageIdx: 1, status: 'active');
    case 'developed':        return (stageIdx: 1, status: 'done');
    case 'reviewing':        return (stageIdx: 2, status: 'active');
    case 'reviewed-ok':      return (stageIdx: 2, status: 'done');
    case 'reviewed-changes': return (stageIdx: 2, status: 'fail');
    case 'testing':          return (stageIdx: 3, status: 'active');
    case 'tested-ok':        return (stageIdx: 3, status: 'done');
    case 'tested-fail':      return (stageIdx: 3, status: 'fail');
    case 'awaiting-po':      return (stageIdx: -1, status: 'fail');
    default:                 return (stageIdx: -1, status: 'idle');
  }
}

class StoriesTab extends ConsumerWidget {
  const StoriesTab({super.key});

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
                    Text('Stories',
                        style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                    Padding(
                      padding: EdgeInsets.only(top: 2),
                      child: Text('Stories die de AI op dit moment behandelt',
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
              // Combine AI-actieve stories + AI IN REVIEW (uit open_prs).
              final all = <_StoryRow>[];
              for (final c in s.aiActive) {
                all.add(_StoryRow.fromJira(c));
              }
              for (final p in s.openPrs) {
                final m = RegExp(r'^ai/([A-Z][A-Z0-9]+-[0-9]+)$').firstMatch(p.branch);
                final key = m?.group(1);
                if (key == null) continue;
                // Skip als al in aiActive (zou raar zijn maar safe).
                if (s.aiActive.any((c) => c.key == key)) continue;
                all.add(_StoryRow.fromPr(p, key));
              }
              if (all.isEmpty) {
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
                            const Text('Geen stories in behandeling',
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
                        child: _StoriesTable(rows: all),
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

class _StoryRow {
  final String key;
  final String title;
  final String status;
  final String aiPhase;
  final int runCount;
  final int tokensInput;
  final int tokensOutput;
  final double costUsd;
  final int aiLevel;

  _StoryRow({
    required this.key,
    required this.title,
    required this.status,
    required this.aiPhase,
    required this.runCount,
    required this.tokensInput,
    required this.tokensOutput,
    required this.costUsd,
    required this.aiLevel,
  });

  factory _StoryRow.fromJira(JiraCard c) => _StoryRow(
        key: c.key,
        title: c.title,
        status: c.status,
        aiPhase: c.aiPhase,
        runCount: c.runCount,
        tokensInput: c.tokensInput,
        tokensOutput: c.tokensOutput,
        costUsd: c.costUsd,
        aiLevel: c.aiLevel,
      );

  factory _StoryRow.fromPr(PrCard p, String key) => _StoryRow(
        key: key,
        title: p.title.replaceFirst(RegExp(r'^[A-Z]+-\d+:\s*'), ''),
        status: p.jiraStatus,
        aiPhase: p.aiPhase,
        runCount: 0, // niet beschikbaar via PR-card; eventueel later joinen
        tokensInput: 0,
        tokensOutput: 0,
        costUsd: 0,
        aiLevel: -1,
      );
}

class _StoriesTable extends StatelessWidget {
  final List<_StoryRow> rows;
  const _StoriesTable({required this.rows});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, c) {
        final wide = c.maxWidth >= 720;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Header
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
              child: Row(
                children: [
                  const Expanded(flex: 3, child: _Hdr('Story')),
                  Expanded(flex: 2, child: _Hdr(wide ? 'Status' : '')),
                  const Expanded(flex: 3, child: _Hdr('Fase')),
                  if (wide) const Expanded(child: _Hdr('Runs')),
                  if (wide) const Expanded(flex: 2, child: _Hdr('Tokens')),
                  if (wide) const Expanded(child: _Hdr('AI lvl')),
                  Expanded(flex: 2, child: _Hdr('Cost')),
                  const SizedBox(width: 12),
                ],
              ),
            ),
            Divider(height: 1, color: scheme.outlineVariant),
            for (int i = 0; i < rows.length; i++) ...[
              if (i > 0) Divider(height: 1, color: scheme.outlineVariant),
              _StoryRowWidget(row: rows[i], wide: wide),
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

class _StoryRowWidget extends StatelessWidget {
  final _StoryRow row;
  final bool wide;
  const _StoryRowWidget({required this.row, required this.wide});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final shortTitle = row.title.length > 30
        ? '${row.title.substring(0, 30)}…'
        : row.title;
    return InkWell(
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => StoryDetailScreen(storyKey: row.key)),
      ),
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
                  Text(row.key,
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
                  Text(shortTitle.isEmpty ? '—' : shortTitle,
                      style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
                ],
              ),
            ),
            Expanded(
              flex: 2,
              child: row.status.isEmpty
                  ? const SizedBox.shrink()
                  : _StatusBadge(label: row.status),
            ),
            Expanded(
              flex: 3,
              child: _PhasePips(aiPhase: row.aiPhase, status: row.status),
            ),
            if (wide)
              Expanded(child: Text('${row.runCount}', style: const TextStyle(fontSize: 13))),
            if (wide)
              Expanded(
                flex: 2,
                child: Text(
                  row.tokensInput > 0
                      ? '${formatTokens(row.tokensInput)} / ${formatTokens(row.tokensOutput)}'
                      : '—',
                  style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                ),
              ),
            if (wide)
              Expanded(
                child: Text(row.aiLevel >= 0 ? '${row.aiLevel}' : '—',
                    style: const TextStyle(fontSize: 13)),
              ),
            Expanded(
              flex: 2,
              child: Text(
                row.costUsd > 0 ? '\$${row.costUsd.toStringAsFixed(4)}' : '—',
                style: const TextStyle(fontSize: 13),
              ),
            ),
            Icon(Icons.chevron_right, color: scheme.onSurfaceVariant, size: 20),
          ],
        ),
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  final String label;
  const _StatusBadge({required this.label});

  (Color, Color) _colors(String s) {
    if (s == 'AI Ready') return (const Color(0xFFE5F0FE), const Color(0xFF1E40AF));
    if (s == 'AI IN PROGRESS') return (const Color(0xFFE5F0FE), const Color(0xFF1E40AF));
    if (s == 'AI Queued') return (const Color(0xFFEDE9FE), const Color(0xFF5B21B6));
    if (s == 'AI IN REVIEW') return (const Color(0xFFE6F7EC), const Color(0xFF1E6B3E));
    if (s == 'AI Needs Info') return (const Color(0xFFFFF4E5), const Color(0xFF8A5A0B));
    if (s == 'AI Paused') return (const Color(0xFFFDECEC), const Color(0xFF991B1B));
    return (const Color(0xFFF1F3F8), const Color(0xFF374151));
  }

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = _colors(label);
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
        child: Text(label,
            style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: fg)),
      ),
    );
  }
}

class _PhasePips extends StatelessWidget {
  final String aiPhase;
  final String status;
  const _PhasePips({required this.aiPhase, required this.status});

  @override
  Widget build(BuildContext context) {
    final mapping = _phaseToStage(aiPhase);
    return Row(
      children: List.generate(_phaseOrder.length, (i) {
        String s;
        if (mapping.stageIdx < 0) {
          // unknown / awaiting-po → alles idle behalve eerste actief bij Ready
          s = (i == 0 && status == 'AI Ready') ? 'active' : 'idle';
        } else if (i < mapping.stageIdx) {
          s = 'done';
        } else if (i == mapping.stageIdx) {
          s = mapping.status;
        } else {
          s = 'idle';
        }
        Color color;
        IconData icon;
        switch (s) {
          case 'done':   color = const Color(0xFF1E6B3E); icon = Icons.check_circle; break;
          case 'active': color = const Color(0xFF1E40AF); icon = Icons.sync; break;
          case 'fail':   color = const Color(0xFF991B1B); icon = Icons.error; break;
          default:       color = const Color(0xFFC4C9D4); icon = Icons.radio_button_unchecked;
        }
        return Tooltip(
          message: _phaseOrder[i],
          child: Padding(
            padding: const EdgeInsets.only(right: 6),
            child: Icon(icon, size: 18, color: color),
          ),
        );
      }),
    );
  }
}
