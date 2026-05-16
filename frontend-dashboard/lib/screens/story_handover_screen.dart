import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/models.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';

/// Centrale markdown-renderer voor de briefing. Selectable + opent
/// links in een externe browser. Tabel-styling matched de overige
/// info-tables (kleine border-radius, surfaceContainerHighest header).
MarkdownStyleSheet _markdownStyle(BuildContext context) {
  final scheme = Theme.of(context).colorScheme;
  return MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
    p: const TextStyle(fontSize: 14, height: 1.5),
    listBullet: const TextStyle(fontSize: 14, height: 1.5),
    code: TextStyle(
      fontFamily: 'monospace',
      fontSize: 13,
      backgroundColor: scheme.surfaceContainerHighest,
    ),
    codeblockDecoration: BoxDecoration(
      color: scheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(6),
    ),
    tableBody: const TextStyle(fontSize: 13),
    tableHead: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
    tableBorder: TableBorder.all(color: scheme.outlineVariant, width: 0.5),
    tableHeadAlign: TextAlign.left,
    tableCellsPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
    h1: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
    h2: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
    h3: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
  );
}

void _onLinkTap(String? text, String? href, String? title) {
  if (href != null && href.isNotEmpty) launchUrl(Uri.parse(href));
}

/// Chronologische timeline van alle agent-iteraties, nieuwste boven.
/// Per iteratie: welke rol, hoeveelste keer voor die rol, eindverdict +
/// outcome, en de samenvatting. Onderaan: PO-dialoog (vragen + antwoorden).
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
        data: (data) {
          final timeline = _buildTimeline(data);
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (data.jiraTitle.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(data.jiraTitle,
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w700)),
                ),
              if (timeline.isEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Center(
                      child: Text('Nog geen agent-runs.',
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                              fontStyle: FontStyle.italic)),
                    ),
                  ),
                )
              else
                for (final entry in timeline) ...[
                  _TimelineCard(entry: entry),
                  const SizedBox(height: 12),
                ],
              if (data.poDialogue.isNotEmpty) ...[
                const SizedBox(height: 4),
                const SectionHeader(
                  title: 'PO-dialoog',
                  subtitle: 'Vragen van de agents en jouw antwoorden',
                ),
                for (final entry in data.poDialogue) _PoDialogueCard(entry: entry),
              ],
              const SizedBox(height: 40),
            ],
          );
        },
      ),
    );
  }
}

/// Eén entry in de chronologische timeline.
class _TimelineEntry {
  final HandoverRun run;
  final int iteration;   // 1-based count voor deze rol
  final int totalForRole;
  _TimelineEntry(this.run, this.iteration, this.totalForRole);
}

/// Flatten alle runs naar één lijst, nieuwste eerst, met per entry de
/// iteration-count voor die rol. De backend levert runs per rol in
/// chronologische volgorde (oudst eerst), dus index = iteration-1.
List<_TimelineEntry> _buildTimeline(HandoverData data) {
  final entries = <_TimelineEntry>[];
  void addRole(List<HandoverRun> runs) {
    for (int i = 0; i < runs.length; i++) {
      entries.add(_TimelineEntry(runs[i], i + 1, runs.length));
    }
  }
  addRole(data.refiner);
  addRole(data.developer);
  addRole(data.reviewer);
  addRole(data.tester);
  // Sorteer op startedAt DESC. Runs zonder startedAt landen onderaan.
  entries.sort((a, b) {
    final ta = a.run.startedAt;
    final tb = b.run.startedAt;
    if (ta == null && tb == null) return 0;
    if (ta == null) return 1;
    if (tb == null) return -1;
    return tb.compareTo(ta);
  });
  return entries;
}

class _TimelineCard extends StatelessWidget {
  final _TimelineEntry entry;
  const _TimelineCard({required this.entry});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final run = entry.run;
    final roleInfo = _roleInfo(run.role);
    final iterationLabel = entry.totalForRole > 1
        ? '${roleInfo.label} #${entry.iteration} van ${entry.totalForRole}'
        : roleInfo.label;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 40, height: 40,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: scheme.primaryContainer,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(roleInfo.icon,
                      color: scheme.onPrimaryContainer, size: 20),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(iterationLabel,
                          style: const TextStyle(
                              fontSize: 15, fontWeight: FontWeight.w700)),
                      Text(formatTs(run.startedAt),
                          style: TextStyle(
                              fontSize: 12, color: scheme.onSurfaceVariant)),
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
                if (run.verdict.isNotEmpty) _VerdictPill(verdict: run.verdict),
                if (run.hadQuestion)
                  // Maakt zichtbaar dat de agent de iteratie afsloot met een
                  // PO-vraag — outcome 'success' zegt dan niet dat de story
                  // verder kan, maar dat de agent z'n actie netjes voltooid
                  // heeft. Antwoord staat in de PO-dialoog onderaan.
                  const StatusPill(
                    label: 'vraag aan PO',
                    bg: Color(0xFFFFF4E5),
                    fg: Color(0xFF8A5A0B),
                  ),
                _OutcomePill(outcome: run.outcome),
              ],
            ),
            const SizedBox(height: 12),
            if (run.summaryText.isEmpty)
              Text('(geen samenvatting)',
                  style: TextStyle(
                      color: scheme.onSurfaceVariant,
                      fontStyle: FontStyle.italic))
            else
              MarkdownBody(
                data: _stripJsonLine(run.summaryText),
                selectable: true,
                shrinkWrap: true,
                onTapLink: _onLinkTap,
                styleSheet: _markdownStyle(context),
              ),
          ],
        ),
      ),
    );
  }

  String _stripJsonLine(String s) {
    final re = RegExp(r'\n?\s*\{\s*"phase".*?\}\s*$', dotAll: true);
    return s.replaceAll(re, '').trim();
  }
}

class _RoleInfo {
  final String label;
  final IconData icon;
  const _RoleInfo(this.label, this.icon);
}

_RoleInfo _roleInfo(String role) {
  switch (role) {
    case 'refiner':
      return const _RoleInfo('Refiner', Icons.psychology_outlined);
    case 'developer':
      return const _RoleInfo('Developer', Icons.code);
    case 'reviewer':
      return const _RoleInfo('Reviewer', Icons.rate_review_outlined);
    case 'tester':
      return const _RoleInfo('Tester', Icons.science_outlined);
    default:
      return _RoleInfo(role, Icons.smart_toy_outlined);
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
  final String verdict;
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
        }[verdict] ??
        verdict;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(20)),
      child: Text(label,
          style: TextStyle(
              fontSize: 11, fontWeight: FontWeight.w700, color: fg)),
    );
  }
}

class _PoDialogueCard extends StatelessWidget {
  final PoDialogueEntry entry;
  const _PoDialogueCard({required this.entry});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final agentLabel = entry.agent.isEmpty
        ? 'Agent'
        : entry.agent[0].toUpperCase() + entry.agent.substring(1);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(Icons.help_outline, color: scheme.error, size: 18),
                  const SizedBox(width: 8),
                  Text('$agentLabel — vraag',
                      style: const TextStyle(
                          fontSize: 14, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  if (entry.questionCreated != null)
                    Text(formatTs(entry.questionCreated),
                        style: TextStyle(
                            fontSize: 11, color: scheme.onSurfaceVariant)),
                ],
              ),
              const SizedBox(height: 6),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: MarkdownBody(
                  data: entry.questionText,
                  selectable: true,
                  shrinkWrap: true,
                  onTapLink: _onLinkTap,
                  styleSheet: _markdownStyle(context).copyWith(
                    p: const TextStyle(fontSize: 13, height: 1.45),
                  ),
                ),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Icon(Icons.reply, color: scheme.primary, size: 18),
                  const SizedBox(width: 8),
                  const Text('Jouw antwoord',
                      style: TextStyle(
                          fontSize: 14, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  if (entry.answerCreated != null)
                    Text(formatTs(entry.answerCreated),
                        style: TextStyle(
                            fontSize: 11, color: scheme.onSurfaceVariant)),
                ],
              ),
              const SizedBox(height: 6),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: entry.hasAnswer
                      ? scheme.tertiaryContainer.withValues(alpha: 0.4)
                      : scheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: entry.hasAnswer
                    ? MarkdownBody(
                        data: entry.answerText,
                        selectable: true,
                        shrinkWrap: true,
                        onTapLink: _onLinkTap,
                        styleSheet: _markdownStyle(context).copyWith(
                          p: const TextStyle(fontSize: 13, height: 1.45),
                        ),
                      )
                    : Text(
                        '(nog geen antwoord)',
                        style: TextStyle(
                          fontSize: 13,
                          height: 1.45,
                          fontStyle: FontStyle.italic,
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
