import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/api_client.dart';
import '../api/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';
import '../widgets/info_table.dart';
import '../widgets/section_header.dart';
import '../widgets/budget_bar.dart';
import 'dashboard_tab.dart' show BuildRunTile;
import 'runner_log_screen.dart';
import 'screenshots_screen.dart';
import 'story_handover_screen.dart';

class StoryDetailScreen extends ConsumerWidget {
  final String storyKey;
  const StoryDetailScreen({super.key, required this.storyKey});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncDetail = ref.watch(storyDetailProvider(storyKey));
    final asyncActive = ref.watch(activeJobProvider(storyKey));
    final asyncQuestion = ref.watch(poQuestionProvider(storyKey));
    final asyncHome = ref.watch(homeStateProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(storyKey),
        actions: [
          IconButton(
            icon: const Icon(Icons.assignment_outlined),
            tooltip: 'Briefing',
            onPressed: () => Navigator.of(context).push(MaterialPageRoute(
              builder: (_) => StoryHandoverScreen(storyKey: storyKey),
            )),
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              ref.invalidate(storyDetailProvider(storyKey));
              ref.invalidate(poQuestionProvider(storyKey));
              ref.invalidate(screenshotsProvider(storyKey));
              ref.invalidate(activeJobProvider(storyKey));
              ref.invalidate(homeStateProvider);
            },
          ),
        ],
      ),
      body: asyncDetail.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (detail) {
          final activeJob = asyncActive.maybeWhen(data: (a) => a, orElse: () => null);
          final question = asyncQuestion.maybeWhen(data: (q) => q, orElse: () => null);
          final home = asyncHome.maybeWhen(data: (h) => h, orElse: () => null);
          // Pipeline-phases zitten op de PrCard in home-state; matchen op
          // PR-nummer uit detail.prs.first.
          PrCard? prCard;
          if (home != null && detail.prs.isNotEmpty) {
            final prNum = (detail.prs.first['number'] as num?)?.toInt();
            if (prNum != null) {
              for (final p in home.openPrs) {
                if (p.number == prNum) { prCard = p; break; }
              }
            }
          }
          return _DetailBody(
            storyKey: storyKey, detail: detail, ref: ref,
            activeJob: activeJob, question: question,
            prCard: prCard,
          );
        },
      ),
    );
  }
}


bool _isStoryDone(String jiraStatus) {
  // JIRA-werkflow-namen die "afgerond" betekenen. Bevat NL + EN varianten.
  const done = {'Gereed', 'Klaar', 'Done', 'Closed'};
  return done.contains(jiraStatus);
}

class _DetailBody extends StatelessWidget {
  final String storyKey;
  final StoryDetail detail;
  final WidgetRef ref;
  final ActiveAgentJob? activeJob;
  final PoQuestion? question;
  final PrCard? prCard;
  const _DetailBody({
    required this.storyKey,
    required this.detail,
    required this.ref,
    required this.activeJob,
    required this.question,
    required this.prCard,
  });

  @override
  Widget build(BuildContext context) {
    final t = detail.totals;
    final wallclockSec = (() {
      if (detail.startedAt == null || detail.endedAt == null) return 0;
      try {
        return DateTime.parse(detail.endedAt!)
            .difference(DateTime.parse(detail.startedAt!))
            .inSeconds;
      } catch (_) { return 0; }
    })();
    final cpuSec = detail.runs.fold<int>(
        0, (acc, r) => acc + (r.durationMs ~/ 1000));
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        if (detail.jiraTitle.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(detail.jiraTitle,
                style: const TextStyle(
                    fontSize: 18, fontWeight: FontWeight.w700)),
          ),
        _StatusBanner(
          storyKey: storyKey,
          status: detail.jiraStatus,
          aiPhase: detail.aiPhase,
          activeJob: activeJob,
        ),
        const SizedBox(height: 12),
        if (question != null) ...[
          _PoQuestionCard(storyKey: storyKey, question: question!, ref: ref),
          const SizedBox(height: 12),
        ],
        _LinksRow(storyKey: storyKey, prs: detail.prs),
        const SizedBox(height: 16),
        // Commands maken alleen zin voor stories die nog actief zijn —
        // niet voor reeds gemergede (Gereed/Klaar/Done). Hide ze daar.
        if (!_isStoryDone(detail.jiraStatus)) ...[
          _CommandsCard(
            storyKey: storyKey,
            ref: ref,
            jiraStatus: detail.jiraStatus,
          ),
          const SizedBox(height: 16),
        ],
        _DeployCard(
          prCard: prCard,
          latestCommit: detail.commits.isNotEmpty ? detail.commits.first : null,
        ),
        const SizedBox(height: 16),
        _BudgetCard(
          tokensUsed: ((t['input'] as num?)?.toInt() ?? 0) +
              ((t['output'] as num?)?.toInt() ?? 0),
          tokenBudget: detail.tokenBudget,
        ),
        const SizedBox(height: 16),
        InfoTable(
          title: 'OVERZICHT',
          rows: [
            ('Gestart', formatTs(detail.startedAt)),
            ('Geëindigd', formatTs(detail.endedAt)),
            ('Final status', detail.finalStatus ?? 'lopend'),
            ('Aantal agent-runs', '${detail.runs.length}'),
            ('Agent-tijd (CPU)', formatSeconds(cpuSec)),
            ('Wallclock', formatSeconds(wallclockSec)),
            ('Input tokens', formatTokens((t['input'] as num?)?.toInt() ?? 0)),
            ('Output tokens', formatTokens((t['output'] as num?)?.toInt() ?? 0)),
            ('Cache-read tokens',
                formatTokens((t['cache_read'] as num?)?.toInt() ?? 0)),
            ('Cache-creation tokens',
                formatTokens((t['cache_creation'] as num?)?.toInt() ?? 0)),
            ('Geschatte kosten',
                '\$${((t['cost_usd'] as num?)?.toDouble() ?? 0).toStringAsFixed(4)}'),
          ],
        ),
        SectionHeader(title: 'Agent-runs', subtitle: '${detail.runs.length} runs'),
        Card(
          child: Column(
            children: [
              for (int i = 0; i < detail.runs.length; i++) ...[
                if (i > 0) const Divider(height: 1),
                _AgentRunTile(run: detail.runs[i]),
              ],
            ],
          ),
        ),
        if (detail.prs.isNotEmpty) ...[
          SectionHeader(title: 'Pull requests', subtitle: '${detail.prs.length}'),
          Card(
            child: Column(
              children: [
                for (int i = 0; i < detail.prs.length; i++) ...[
                  if (i > 0) const Divider(height: 1),
                  ListTile(
                    leading: const Icon(Icons.merge_type),
                    title: Text('#${detail.prs[i]["number"]} — '
                        '${detail.prs[i]["title"] ?? ""}'),
                    subtitle: Text(
                      detail.prs[i]['merged_at'] != null
                          ? 'merged'
                          : (detail.prs[i]['state']?.toString() ?? ''),
                    ),
                    trailing: const Icon(Icons.open_in_new, size: 16),
                    onTap: () {
                      final url = detail.prs[i]['html_url']?.toString();
                      if (url != null && url.isNotEmpty) {
                        launchUrl(Uri.parse(url));
                      }
                    },
                  ),
                ],
              ],
            ),
          ),
        ],
        if (detail.prBuilds.isNotEmpty) ...[
          SectionHeader(
              title: 'GitHub builds (deze PR)',
              subtitle: '${detail.prBuilds.length} runs'),
          Card(
            child: Column(
              children: [
                for (int i = 0; i < detail.prBuilds.length; i++) ...[
                  if (i > 0) const Divider(height: 1),
                  BuildRunTile(run: detail.prBuilds[i]),
                ],
              ],
            ),
          ),
        ],
        if (detail.commits.isNotEmpty) ...[
          SectionHeader(
              title: 'Commits op ai/$storyKey',
              subtitle: '${detail.commits.length}'),
          Card(
            child: Column(
              children: [
                for (int i = 0; i < detail.commits.length; i++) ...[
                  if (i > 0) const Divider(height: 1),
                  _CommitTile(commit: detail.commits[i]),
                ],
              ],
            ),
          ),
        ],
        const SizedBox(height: 40),
      ],
    );
  }
}

class _LinksRow extends ConsumerWidget {
  final String storyKey;
  final List<Map<String, dynamic>> prs;
  const _LinksRow({required this.storyKey, required this.prs});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pr = prs.isNotEmpty ? prs.first : null;
    final prNum = pr?['number'];
    final previewUrl = prNum != null
        ? 'https://pnf-pr-$prNum.vdzonsoftware.nl'
        : null;
    final shotsAsync = ref.watch(screenshotsProvider(storyKey));
    final shotsCount = shotsAsync.maybeWhen(
        data: (s) => s.length, orElse: () => 0);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'LINKS',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontSize: 12,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.4,
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  icon: const Icon(Icons.open_in_new, size: 16),
                  label: const Text('JIRA'),
                  onPressed: () => launchUrl(
                      Uri.parse('https://vdzon.atlassian.net/browse/$storyKey')),
                ),
                if (pr != null)
                  OutlinedButton.icon(
                    icon: const Icon(Icons.merge_type, size: 16),
                    label: Text('PR #${pr['number']}'),
                    onPressed: () => launchUrl(Uri.parse(pr['html_url'] as String)),
                  ),
                if (previewUrl != null)
                  OutlinedButton.icon(
                    icon: const Icon(Icons.science, size: 16),
                    label: const Text('Test op preview'),
                    onPressed: () => launchUrl(Uri.parse(previewUrl)),
                  ),
                OutlinedButton.icon(
                  icon: const Icon(Icons.assignment_outlined, size: 16),
                  label: const Text('Briefing'),
                  onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                    builder: (_) => StoryHandoverScreen(storyKey: storyKey),
                  )),
                ),
                if (shotsCount > 0)
                  FilledButton.tonalIcon(
                    icon: const Icon(Icons.image_outlined, size: 16),
                    label: Text('Show screenshots ($shotsCount)'),
                    onPressed: () =>
                        Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => ScreenshotsScreen(storyKey: storyKey),
                    )),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Bepaal een korte tekst die uitlegt wat er met de story gebeurt:
/// 'Actief: <role>' als er een Claude-job loopt, anders een 'Wacht op …'
/// of 'Klaar voor merge' afhankelijk van status + ai_phase.
String _pipelineLabel(String status, String phase, ActiveAgentJob? job) {
  if (job != null) return 'Actief: ${job.role}-job draait nu';
  // Done.
  const done = {'Gereed', 'Klaar', 'Done', 'Closed'};
  if (done.contains(status)) return 'Afgerond';
  // Manuele pauze.
  if (status == 'AI Paused') return 'Gepauzeerd — klik Continue om te hervatten';
  // PO-input nodig.
  if (status == 'AI Needs Info') {
    if (phase == 'awaiting-po') {
      return 'Wacht op PO-antwoord (of klik Continue)';
    }
    return 'Wacht op PO-input';
  }
  // Idle, wacht op poller-pickup. Mapping per phase.
  final waitTarget = {
    '':                  'refiner (bij AI Ready)',
    'refined':           'developer',
    'awaiting-po':       'resume agent',
    'developed':         'reviewer',
    'reviewed-ok':       'tester',
    'reviewed-changes':  'developer (loopback)',
    'tested-fail':       'developer (loopback)',
    'tested-ok':         'mens — klaar voor merge',
  };
  final target = waitTarget[phase];
  if (target == null) {
    return 'Wacht op poller (phase=$phase)';
  }
  if (phase == 'tested-ok') return 'Klaar voor merge';
  return 'Wacht op poller — volgende: $target';
}

class _StatusBanner extends StatelessWidget {
  final String storyKey;
  final String status;
  final String aiPhase;
  final ActiveAgentJob? activeJob;
  const _StatusBanner({
    required this.storyKey,
    required this.status,
    required this.aiPhase,
    required this.activeJob,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final phase = aiPhase;
    final running = activeJob != null;
    final needsInfo = status == 'AI Needs Info';
    final bg = running
        ? scheme.primaryContainer
        : (needsInfo ? scheme.errorContainer : scheme.secondaryContainer);
    final fg = running
        ? scheme.onPrimaryContainer
        : (needsInfo ? scheme.onErrorContainer : scheme.onSecondaryContainer);
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                running
                    ? Icons.sync
                    : (needsInfo ? Icons.help_outline : Icons.info_outline),
                color: fg,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  status,
                  style: TextStyle(
                      color: fg, fontSize: 17, fontWeight: FontWeight.w700),
                ),
              ),
              if (phase.isNotEmpty)
                StatusPill(label: phase, bg: scheme.surface, fg: scheme.onSurface),
            ],
          ),
          // Tekstlabel zegt expliciet wat er gebeurt — actief of wachtend.
          // Kleur volgt de bg-rules; alleen de tekst is nieuw.
          Padding(
            padding: const EdgeInsets.only(top: 6, left: 34),
            child: Text(
              _pipelineLabel(status, phase, activeJob),
              style: TextStyle(
                  color: fg.withValues(alpha: 0.85),
                  fontSize: 13,
                  fontStyle: FontStyle.italic),
            ),
          ),
          if (running) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: scheme.surface,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Row(
                children: [
                  Icon(Icons.smart_toy_outlined,
                      color: scheme.onSurfaceVariant, size: 20),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(activeJob!.role.toUpperCase(),
                            style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w700,
                                color: scheme.onSurfaceVariant)),
                        Text(activeJob!.jobName,
                            style: const TextStyle(
                                fontSize: 11, fontFamily: 'monospace'),
                            overflow: TextOverflow.ellipsis),
                      ],
                    ),
                  ),
                  FilledButton.tonalIcon(
                    icon: const Icon(Icons.description_outlined, size: 16),
                    label: const Text('Live log'),
                    onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => RunnerLogScreen(jobName: activeJob!.jobName),
                    )),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _PoQuestionCard extends ConsumerStatefulWidget {
  final String storyKey;
  final PoQuestion question;
  final WidgetRef ref;
  const _PoQuestionCard({required this.storyKey, required this.question, required this.ref});

  @override
  ConsumerState<_PoQuestionCard> createState() => _PoQuestionCardState();
}

class _PoQuestionCardState extends ConsumerState<_PoQuestionCard> {
  final _ctrl = TextEditingController();
  bool _busy = false;
  String? _result;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final text = _ctrl.text.trim();
    if (text.isEmpty) return;
    setState(() => _busy = true);
    try {
      await ref.read(apiProvider).sendPoAnswer(widget.storyKey, text);
      setState(() {
        _busy = false;
        _result = 'Antwoord verstuurd. Status → AI Queued; poller pakt op binnen 30s.';
      });
      ref.invalidate(poQuestionProvider(widget.storyKey));
      ref.invalidate(homeStateProvider);
    } on ApiException catch (e) {
      setState(() {
        _busy = false;
        _result = 'Fout: ${e.statusCode} ${e.body}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.help_outline, color: scheme.error),
                const SizedBox(width: 8),
                Text('Vraag van de agent',
                    style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: scheme.onSurface)),
              ],
            ),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(10),
              ),
              child: SelectableText(widget.question.text.trim(),
                  style: const TextStyle(fontSize: 13, height: 1.45)),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _ctrl,
              maxLines: 4,
              minLines: 2,
              decoration: const InputDecoration(
                labelText: 'Jouw antwoord',
                hintText: 'Beantwoord hier; wordt direct als JIRA-comment geplaatst.',
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                if (_result != null)
                  Expanded(
                    child: Text(_result!,
                        style: TextStyle(
                            color: _result!.startsWith('Fout')
                                ? scheme.error
                                : scheme.primary,
                            fontSize: 12)),
                  )
                else
                  const Spacer(),
                FilledButton.icon(
                  icon: const Icon(Icons.send, size: 16),
                  label: const Text('Verstuur'),
                  onPressed: _busy ? null : _send,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _CommandsCard extends StatelessWidget {
  final String storyKey;
  final WidgetRef ref;
  final String jiraStatus;
  const _CommandsCard({
    required this.storyKey,
    required this.ref,
    required this.jiraStatus,
  });

  bool get _isPaused =>
      jiraStatus == 'AI Paused' || jiraStatus == 'AI Needs Info';

  Future<void> _send(BuildContext context, String cmd, {bool confirm = false}) async {
    if (confirm) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          title: Text('Bevestig \'$cmd\''),
          content: Text(
              'Weet je zeker dat je \'$cmd\' op $storyKey wilt uitvoeren? '
              'Niet ongedaan te maken.'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Annuleren'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Doorvoeren'),
            ),
          ],
        ),
      );
      if (ok != true) return;
    }
    try {
      await ref.read(apiProvider).sendCommand(storyKey, cmd);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(
              "Commando '$cmd' gepost — poller pakt 't op in de volgende tick."),
        ));
      }
    } on ApiException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text('Commando-fout: ${e.statusCode}')));
      }
    }
  }

  Future<void> _resume(BuildContext context, {int? budgetValue}) async {
    try {
      await ref.read(apiProvider).resumeStory(storyKey, budgetValue: budgetValue);
      if (context.mounted) {
        final suffix = budgetValue == null
            ? ''
            : ' (budget→$budgetValue)';
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text("Status → AI Queued$suffix. Poller pakt 'm op binnen ~30s."),
        ));
      }
    } on ApiException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text('Resume fout: ${e.statusCode} ${e.body}')));
      }
    }
  }

  Future<void> _askBudgetAndResume(BuildContext context) async {
    final ctrl = TextEditingController();
    final v = await showDialog<int>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Budget instellen + hervatten'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            labelText: 'Aantal tokens',
            hintText: 'bv. 120000',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Annuleren'),
          ),
          FilledButton(
            onPressed: () {
              final n = int.tryParse(ctrl.text.trim());
              if (n != null && n > 0) Navigator.pop(context, n);
            },
            child: const Text('Bevestig'),
          ),
        ],
      ),
    );
    if (v != null && context.mounted) {
      await _resume(context, budgetValue: v);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'COMMANDO\'S',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontSize: 12,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.4,
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                // Eerste knop: Continue als de story gepauzeerd is, anders
                // Pause. Op dezelfde plek zodat de spier-geheugen-klik niet
                // per ongeluk Merge wordt.
                if (_isPaused)
                  OutlinedButton.icon(
                    icon: const Icon(Icons.play_arrow, size: 16),
                    label: const Text('Continue'),
                    onPressed: () => _resume(context),
                  )
                else
                  OutlinedButton.icon(
                    icon: const Icon(Icons.pause, size: 16),
                    label: const Text('Pause'),
                    onPressed: () => _send(context, 'pause'),
                  ),
                OutlinedButton.icon(
                  icon: const Icon(Icons.merge_type, size: 16),
                  label: const Text('Merge'),
                  onPressed: () => _send(context, 'merge', confirm: true),
                ),
                FilledButton.tonalIcon(
                  icon: const Icon(Icons.delete_outline, size: 16),
                  label: const Text('Delete'),
                  style: FilledButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.errorContainer,
                    foregroundColor: Theme.of(context).colorScheme.onErrorContainer,
                  ),
                  onPressed: () => _send(context, 'delete', confirm: true),
                ),
                FilledButton.tonalIcon(
                  icon: const Icon(Icons.refresh, size: 16),
                  label: const Text('Re-implement'),
                  style: FilledButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.errorContainer,
                    foregroundColor: Theme.of(context).colorScheme.onErrorContainer,
                  ),
                  onPressed: () => _send(context, 're-implement', confirm: true),
                ),
                // Set budget + continue alleen bij gepauzeerde stories.
                if (_isPaused)
                  OutlinedButton.icon(
                    icon: const Icon(Icons.account_balance_wallet_outlined, size: 16),
                    label: const Text('Set budget + continue…'),
                    onPressed: () => _askBudgetAndResume(context),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentRunTile extends StatelessWidget {
  final AgentRun run;
  const _AgentRunTile({required this.run});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final ok = run.outcome == 'success';
    final fail = run.outcome.contains('fail');
    return ListTile(
      leading: Icon(
        ok ? Icons.check_circle : (fail ? Icons.error : Icons.help_outline),
        color: ok ? scheme.primary : (fail ? scheme.error : scheme.onSurfaceVariant),
      ),
      title: Row(
        children: [
          Text(run.role,
              style: const TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(width: 8),
          StatusPill(label: run.outcome),
        ],
      ),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Wrap(
          spacing: 6,
          runSpacing: 4,
          children: [
            Text(formatTs(run.startedAt),
                style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12)),
            TokenChip(label: 'in', value: formatTokens(run.input)),
            TokenChip(label: 'out', value: formatTokens(run.output)),
            TokenChip(
                label: 'duur',
                value: formatSeconds(run.durationMs ~/ 1000)),
            TokenChip(
                label: 'cost',
                value: '\$${run.costUsd.toStringAsFixed(4)}'),
          ],
        ),
      ),
      trailing: run.jobName.isEmpty
          ? null
          : IconButton(
              icon: const Icon(Icons.description_outlined),
              tooltip: 'Log',
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => RunnerLogScreen(jobName: run.jobName),
              )),
            ),
    );
  }
}

class _DeployCard extends StatelessWidget {
  final PrCard? prCard;
  final Map<String, dynamic>? latestCommit;
  const _DeployCard({required this.prCard, required this.latestCommit});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'DEPLOY',
              style: TextStyle(
                color: scheme.onSurfaceVariant,
                fontSize: 12,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.4,
              ),
            ),
            const SizedBox(height: 10),
            if (latestCommit != null) _LatestCommitRow(commit: latestCommit!),
            if (latestCommit != null) const SizedBox(height: 10),
            if (prCard == null || prCard!.phases.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Text(
                  prCard == null
                      ? 'Geen open PR gevonden (al gemerged of nog niet aangemaakt).'
                      : 'Pipeline-status nog niet beschikbaar.',
                  style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
                ),
              )
            else
              _PhasesTable(phases: prCard!.phases),
          ],
        ),
      ),
    );
  }
}

class _LatestCommitRow extends StatelessWidget {
  final Map<String, dynamic> commit;
  const _LatestCommitRow({required this.commit});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fullSha = commit['sha'] as String? ?? '';
    final sha = fullSha.length >= 7 ? fullSha.substring(0, 7) : fullSha;
    final commitData = (commit['commit'] as Map?) ?? {};
    final msg = (commitData['message'] as String? ?? '').split('\n').first;
    final when = ((commitData['author'] as Map?) ?? {})['date'] as String? ?? '';
    final url = commit['html_url']?.toString() ?? '';
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: url.isEmpty ? null : () => launchUrl(Uri.parse(url)),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
        child: Row(
          children: [
            Icon(Icons.commit, size: 18, color: scheme.onSurfaceVariant),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Laatste commit',
                      style: TextStyle(
                          fontSize: 11,
                          color: scheme.onSurfaceVariant,
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0.3)),
                  Text(msg.isEmpty ? '—' : msg,
                      style: const TextStyle(fontSize: 13),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis),
                  Text(
                    sha.isEmpty ? formatTs(when) : '$sha · ${formatTs(when)}',
                    style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            if (url.isNotEmpty)
              Icon(Icons.open_in_new, size: 14, color: scheme.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}

class _PhasesTable extends StatelessWidget {
  final List<Map<String, dynamic>> phases;
  const _PhasesTable({required this.phases});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
            child: Row(
              children: const [
                Expanded(flex: 3, child: _ColHdr('Onderdeel')),
                Expanded(flex: 2, child: _ColHdr('Status')),
                Expanded(flex: 3, child: _ColHdr('Detail')),
                Expanded(flex: 2, child: _ColHdr('Sinds')),
              ],
            ),
          ),
          Divider(height: 1, color: scheme.outlineVariant),
          for (int i = 0; i < phases.length; i++) ...[
            if (i > 0) Divider(height: 1, color: scheme.outlineVariant),
            _PhaseRow(phase: phases[i]),
          ],
        ],
      ),
    );
  }
}

class _ColHdr extends StatelessWidget {
  final String text;
  const _ColHdr(this.text);
  @override
  Widget build(BuildContext context) {
    return Text(text.toUpperCase(),
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          letterSpacing: 0.4,
        ));
  }
}

class _PhaseRow extends StatelessWidget {
  final Map<String, dynamic> phase;
  const _PhaseRow({required this.phase});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final label = (phase['label'] as String?) ?? '';
    final status = (phase['status'] as String?) ?? '';
    final detail = (phase['detail'] as String?) ?? '';
    final since = (phase['since'] as String?) ?? '';
    final link = (phase['link'] as String?) ?? '';
    final ok = status == 'pass';
    final running = status == 'running';
    final fail = status == 'fail';
    final color = ok
        ? const Color(0xFF1E6B3E)
        : (running
            ? const Color(0xFF1E40AF)
            : (fail ? const Color(0xFF991B1B) : const Color(0xFF6B7280)));
    final icon = ok
        ? Icons.check_circle_outline
        : (running
            ? Icons.sync
            : (fail ? Icons.error_outline : Icons.radio_button_unchecked));
    return InkWell(
      onTap: link.isEmpty ? null : () => launchUrl(Uri.parse(link)),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
        child: Row(
          children: [
            Expanded(
              flex: 3,
              child: Text(label,
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500)),
            ),
            Expanded(
              flex: 2,
              child: Row(
                children: [
                  Icon(icon, size: 14, color: color),
                  const SizedBox(width: 6),
                  Flexible(
                    child: Text(
                      ok ? 'Pass' : (running ? 'Running' : (fail ? 'Failed' : 'Pending')),
                      style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: color),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              flex: 3,
              child: Text(
                detail.isEmpty ? '—' : detail,
                style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            Expanded(
              flex: 2,
              child: Text(
                since.isEmpty ? '—' : '$since geleden',
                style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CommitTile extends StatelessWidget {
  final Map<String, dynamic> commit;
  const _CommitTile({required this.commit});

  @override
  Widget build(BuildContext context) {
    final sha = (commit['sha'] as String? ?? '').substring(
        0, (commit['sha'] as String? ?? '').length > 7 ? 7 : 0);
    final commitData = (commit['commit'] as Map?) ?? {};
    final msg =
        (commitData['message'] as String? ?? '').split('\n').first;
    final author =
        ((commitData['author'] as Map?) ?? {})['name'] as String? ?? '';
    final when = ((commitData['author'] as Map?) ?? {})['date']
            as String? ??
        '';
    return ListTile(
      dense: true,
      leading: const Icon(Icons.commit, size: 18),
      title: Text(msg, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text('$sha · $author · ${formatTs(when)}',
          style: TextStyle(
              fontSize: 11,
              color: Theme.of(context).colorScheme.onSurfaceVariant)),
      onTap: () {
        final url = commit['html_url']?.toString();
        if (url != null && url.isNotEmpty) launchUrl(Uri.parse(url));
      },
    );
  }
}

class _BudgetCard extends StatelessWidget {
  final int tokensUsed;
  final int tokenBudget;
  const _BudgetCard({required this.tokensUsed, required this.tokenBudget});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    if (tokenBudget <= 0) {
      // Story zonder AI Token Budget — toon neutraal, geen balk.
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Icon(Icons.account_balance_wallet_outlined,
                  color: scheme.onSurfaceVariant, size: 18),
              const SizedBox(width: 10),
              Text('Budget: n.v.t.',
                  style: TextStyle(
                      fontSize: 13, color: scheme.onSurfaceVariant)),
              const SizedBox(width: 12),
              Text('(${formatTokens(tokensUsed)} tokens gebruikt)',
                  style: TextStyle(
                      fontSize: 12, color: scheme.onSurfaceVariant)),
            ],
          ),
        ),
      );
    }
    final pctInt = ((tokensUsed / tokenBudget) * 100).round();
    final remaining = tokenBudget - tokensUsed;
    final remainingLabel = remaining < 0
        ? '${formatTokens(-remaining)} over budget'
        : '${formatTokens(remaining)} tokens over';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(Icons.account_balance_wallet_outlined,
                    color: scheme.primary, size: 18),
                const SizedBox(width: 8),
                Text('BUDGET',
                    style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: scheme.onSurface,
                        letterSpacing: 0.4)),
                const Spacer(),
                Text('$pctInt%',
                    style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                        color: scheme.onSurface)),
              ],
            ),
            const SizedBox(height: 10),
            BudgetBar(
              tokensUsed: tokensUsed,
              tokenBudget: tokenBudget,
              height: 10,
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Text(
                  '${formatTokens(tokensUsed)} / '
                  '${formatTokens(tokenBudget)} tokens',
                  style: TextStyle(
                      fontSize: 12, color: scheme.onSurfaceVariant),
                ),
                const Spacer(),
                Text(remainingLabel,
                    style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: remaining < 0
                            ? const Color(0xFFC62828)
                            : scheme.onSurfaceVariant)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
