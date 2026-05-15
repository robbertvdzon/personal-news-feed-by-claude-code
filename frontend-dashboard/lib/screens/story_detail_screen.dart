import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/api_client.dart';
import '../api/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';
import '../widgets/info_table.dart';
import '../widgets/section_header.dart';
import 'runner_log_screen.dart';
import 'story_handover_screen.dart';

class StoryDetailScreen extends ConsumerWidget {
  final String storyKey;
  const StoryDetailScreen({super.key, required this.storyKey});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncDetail = ref.watch(storyDetailProvider(storyKey));
    final asyncActive = ref.watch(activeJobProvider(storyKey));
    final asyncQuestion = ref.watch(poQuestionProvider(storyKey));
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
          return _DetailBody(
            storyKey: storyKey, detail: detail, ref: ref,
            activeJob: activeJob, question: question,
          );
        },
      ),
    );
  }
}


class _DetailBody extends StatelessWidget {
  final String storyKey;
  final StoryDetail detail;
  final WidgetRef ref;
  final ActiveAgentJob? activeJob;
  final PoQuestion? question;
  const _DetailBody({
    required this.storyKey,
    required this.detail,
    required this.ref,
    required this.activeJob,
    required this.question,
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
        _CommandsCard(storyKey: storyKey, ref: ref),
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
    // Eerste open PR voor preview-URL.
    final pr = prs.isNotEmpty ? prs.first : null;
    final prNum = pr?['number'];
    final previewUrl = prNum != null
        ? 'https://pnf-pr-$prNum.vdzonsoftware.nl'
        : null;
    return Wrap(
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
          FilledButton.icon(
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
      ],
    );
  }
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
  const _CommandsCard({required this.storyKey, required this.ref});

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
