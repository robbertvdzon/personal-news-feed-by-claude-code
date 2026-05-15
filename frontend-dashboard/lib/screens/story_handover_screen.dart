import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/models.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';

class StoryHandoverScreen extends ConsumerWidget {
  final String storyKey;
  const StoryHandoverScreen({super.key, required this.storyKey});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncData = ref.watch(storyHandoverProvider(storyKey));
    return Scaffold(
      appBar: AppBar(title: Text('Briefing — $storyKey')),
      body: asyncData.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (data) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (data.jiraTitle.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(data.jiraTitle,
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.w700)),
              ),
            _AgentRoleSection(
              title: 'Refiner — context en aannames',
              icon: Icons.psychology_outlined,
              runs: data.refiner,
            ),
            _AgentRoleSection(
              title: 'Developer — wat is gebouwd',
              icon: Icons.code,
              runs: data.developer,
            ),
            _AgentRoleSection(
              title: 'Reviewer — code-review',
              icon: Icons.rate_review_outlined,
              runs: data.reviewer,
            ),
            _AgentRoleSection(
              title: 'Tester — test-rapport',
              icon: Icons.science_outlined,
              runs: data.tester,
            ),
            const SizedBox(height: 40),
          ],
        ),
      ),
    );
  }
}

class _AgentRoleSection extends StatelessWidget {
  final String title;
  final IconData icon;
  final List<HandoverRun> runs;
  const _AgentRoleSection({required this.title, required this.icon, required this.runs});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final present = runs.any((r) => r.summaryText.isNotEmpty);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 36, height: 36,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: present
                          ? scheme.primaryContainer
                          : scheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Icon(icon,
                        color: present
                            ? scheme.onPrimaryContainer
                            : scheme.onSurfaceVariant,
                        size: 18),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(title,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w600)),
                  ),
                  if (runs.length > 1)
                    _IterationBadge(count: runs.length),
                ],
              ),
              const SizedBox(height: 12),
              if (!present)
                Text('Nog niet gedraaid.',
                    style: TextStyle(
                        color: scheme.onSurfaceVariant,
                        fontStyle: FontStyle.italic))
              else
                Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (int i = 0; i < runs.length; i++) ...[
                      if (i > 0) const SizedBox(height: 16),
                      _RunBlock(run: runs[i], iteration: i + 1, total: runs.length),
                    ],
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _IterationBadge extends StatelessWidget {
  final int count;
  const _IterationBadge({required this.count});
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: scheme.secondaryContainer,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text('$count× gedraaid',
          style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: scheme.onSecondaryContainer)),
    );
  }
}

class _RunBlock extends StatelessWidget {
  final HandoverRun run;
  final int iteration;
  final int total;
  const _RunBlock({required this.run, required this.iteration, required this.total});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final ts = formatTs(run.startedAt);
    final headerLabel = total > 1 ? 'Iteratie $iteration/$total · $ts' : ts;
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(10),
      ),
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(headerLabel,
                    style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.3,
                        color: scheme.onSurfaceVariant)),
              ),
              if (run.verdict.isNotEmpty) ...[
                _VerdictPill(verdict: run.verdict),
                const SizedBox(width: 6),
              ],
              _OutcomePill(outcome: run.outcome),
            ],
          ),
          const SizedBox(height: 8),
          if (run.summaryText.isEmpty)
            Text('(geen samenvatting)',
                style: TextStyle(
                    color: scheme.onSurfaceVariant, fontStyle: FontStyle.italic))
          else
            SelectableText(
              _stripJsonLine(run.summaryText),
              style: const TextStyle(fontSize: 14, height: 1.5),
            ),
        ],
      ),
    );
  }

  String _stripJsonLine(String s) {
    // Verwijder de slot-JSON (`{"phase": ...}`) onderaan agent-summaries.
    final re = RegExp(r'\n?\s*\{\s*"phase".*?\}\s*$', dotAll: true);
    return s.replaceAll(re, '').trim();
  }
}

class _OutcomePill extends StatelessWidget {
  final String outcome;
  const _OutcomePill({required this.outcome});
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final ok = outcome == 'success';
    return StatusPill(
      label: outcome,
      bg: ok ? scheme.tertiaryContainer : scheme.errorContainer,
      fg: ok ? scheme.onTertiaryContainer : scheme.onErrorContainer,
    );
  }
}

class _VerdictPill extends StatelessWidget {
  final String verdict;  // 'OK' / 'CHANGES' / 'PASS' / 'FAIL'
  const _VerdictPill({required this.verdict});
  @override
  Widget build(BuildContext context) {
    final approved = verdict == 'OK' || verdict == 'PASS';
    final bg = approved ? const Color(0xFFE6F7EC) : const Color(0xFFFDECEC);
    final fg = approved ? const Color(0xFF1E6B3E) : const Color(0xFF991B1B);
    final label = {
      'OK': '✅ Approved',
      'PASS': '✅ Tests OK',
      'CHANGES': '⚠ Changes vereist',
      'FAIL': '❌ Tests FAIL',
    }[verdict] ?? verdict;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(20)),
      child: Text(label,
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: fg)),
    );
  }
}
