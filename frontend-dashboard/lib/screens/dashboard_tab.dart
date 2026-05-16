import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/models.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';

// externalApplication zorgt dat Android de system browser / download-manager
// gebruikt i.p.v. de in-app webview, anders gebeurt er niks bij een .apk.
Future<void> _openDownload(String url) => launchUrl(
      Uri.parse(url),
      mode: LaunchMode.externalApplication,
    );

class DashboardTab extends ConsumerWidget {
  const DashboardTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(homeStateProvider);
    final apksAsync = ref.watch(apksProvider);
    return _TabFrame(
      title: 'Dashboard',
      subtitle: 'Overzicht van builds en productie',
      onRefresh: () async {
        ref.invalidate(homeStateProvider);
        ref.invalidate(apksProvider);
      },
      child: state.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (s) => ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 80),
          children: [
            const SectionHeader(title: 'Production', subtitle: 'main-branch'),
            _ProductionCard(
              main: s.main,
              lastMerged: s.closedPrs.isNotEmpty ? s.closedPrs.first : null,
            ),
            const SizedBox(height: 8),
            const SectionHeader(
                title: 'APK\'s',
                subtitle: 'Laatste builds van de twee Flutter-apps'),
            apksAsync.when(
              loading: () => const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (e, _) => Card(
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Text('APK-info niet beschikbaar: $e',
                      style: TextStyle(
                          color: Theme.of(context)
                              .colorScheme
                              .onSurfaceVariant)),
                ),
              ),
              data: (apks) => _ApkRow(apks: apks),
            ),
            const SizedBox(height: 8),
            SectionHeader(
                title: 'Recente builds (main)',
                subtitle: '${s.main.recentRuns.length} runs'),
            _BuildsList(runs: s.main.recentRuns),
          ],
        ),
      ),
    );
  }
}

class _ProductionCard extends StatelessWidget {
  final MainBuild main;
  final ClosedPr? lastMerged;
  const _ProductionCard({required this.main, required this.lastMerged});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 'X/Y groen'-pill bewust weggehaald — gaf weinig informatie en
            // toonde meestal '1/5 groen' ook bij gezonde state. Phases-detail
            // staat verderop in deze kaart.
            if (main.previewUrl.isNotEmpty)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  icon: const Icon(Icons.open_in_new, size: 14),
                  label: const Text('Preview'),
                  onPressed: () => launchUrl(Uri.parse(main.previewUrl)),
                ),
              ),
            if (main.message.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text(main.message,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w600),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis),
              ),
            Text(main.sha.isNotEmpty ? main.sha : '—',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
            Text(main.shaAge.isNotEmpty ? '${main.shaAge} geleden' : 'unknown age',
                style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12)),
            const SizedBox(height: 16),
            _SubHeader(text: 'Laatste merge naar main'),
            const SizedBox(height: 6),
            if (lastMerged == null)
              Text('—', style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13))
            else
              _LastMergeRow(pr: lastMerged!),
            if (main.phases.isNotEmpty) ...[
              const SizedBox(height: 16),
              _SubHeader(text: 'Builds & services'),
              const SizedBox(height: 6),
              _PhasesTable(phases: main.phases),
            ],
          ],
        ),
      ),
    );
  }
}

class _SubHeader extends StatelessWidget {
  final String text;
  const _SubHeader({required this.text});
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

class _LastMergeRow extends StatelessWidget {
  final ClosedPr pr;
  const _LastMergeRow({required this.pr});
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final number = pr.number.toString();
    final title = pr.title;
    final age = pr.mergedAge;
    final url = pr.htmlUrl;
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: url.isEmpty ? null : () => launchUrl(Uri.parse(url)),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          children: [
            Container(
              width: 28, height: 28,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: scheme.tertiaryContainer,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Icon(Icons.merge_type, size: 16, color: scheme.onTertiaryContainer),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('#$number — $title',
                      style: const TextStyle(fontSize: 14),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis),
                  Text(age.isEmpty ? '—' : '$age geleden',
                      style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
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
          crossAxisAlignment: CrossAxisAlignment.center,
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

class _ApkRow extends StatelessWidget {
  final ApkInfo apks;
  const _ApkRow({required this.apks});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      final wide = c.maxWidth >= 520;
      final newsCard = _ApkCard(
        title: 'Personal News Feed',
        subtitle: 'De nieuws-feed app voor je telefoon',
        apk: apks.pnf,
      );
      final dashCard = _ApkCard(
        title: 'Software Factory Dashboard',
        subtitle: 'Dit dashboard op je telefoon',
        apk: apks.dashboard,
      );
      if (wide) {
        return Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(child: newsCard),
            const SizedBox(width: 12),
            Expanded(child: dashCard),
          ],
        );
      }
      return Column(
        children: [newsCard, const SizedBox(height: 10), dashCard],
      );
    });
  }
}

class _ApkCard extends StatelessWidget {
  final String title;
  final String subtitle;
  final ApkEntry apk;
  const _ApkCard({
    required this.title,
    required this.subtitle,
    required this.apk,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final hasUrl = apk.url.isNotEmpty;
    final dateLine = apk.builtAt != null && apk.builtAt!.isNotEmpty
        ? 'Build: ${formatTs(apk.builtAt)}'
        : 'datum onbekend';
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: hasUrl ? () => _openDownload(apk.url) : null,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                width: 44, height: 44,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: scheme.primaryContainer,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(Icons.android,
                    color: scheme.onPrimaryContainer, size: 24),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w600)),
                    Text(subtitle,
                        style: TextStyle(
                            fontSize: 12, color: scheme.onSurfaceVariant)),
                    const SizedBox(height: 4),
                    Text(dateLine,
                        style: TextStyle(
                            fontSize: 11, color: scheme.onSurfaceVariant)),
                  ],
                ),
              ),
              FilledButton.tonalIcon(
                icon: const Icon(Icons.download, size: 16),
                label: const Text('APK'),
                onPressed: hasUrl ? () => _openDownload(apk.url) : null,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _BuildsList extends StatelessWidget {
  final List<BuildRun> runs;
  const _BuildsList({required this.runs});

  @override
  Widget build(BuildContext context) {
    if (runs.isEmpty) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Center(child: Text('Geen recente builds.')),
        ),
      );
    }
    return Card(
      child: Column(
        children: [
          for (int i = 0; i < runs.length; i++) ...[
            if (i > 0) const Divider(height: 1),
            BuildRunTile(run: runs[i]),
          ],
        ],
      ),
    );
  }
}

class BuildRunTile extends StatelessWidget {
  final BuildRun run;
  const BuildRunTile({super.key, required this.run});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final running = run.status != 'completed';
    final ok = run.conclusion == 'success';
    final fail = run.conclusion == 'failure';
    final cancelled =
        run.conclusion == 'cancelled' || run.conclusion == 'skipped';
    final IconData icon;
    final Color color;
    if (running) {
      icon = Icons.sync;
      color = const Color(0xFF1E40AF);
    } else if (ok) {
      icon = Icons.check_circle;
      color = const Color(0xFF1E6B3E);
    } else if (fail) {
      icon = Icons.error;
      color = const Color(0xFF991B1B);
    } else if (cancelled) {
      icon = Icons.do_disturb_on_outlined;
      color = scheme.onSurfaceVariant;
    } else {
      icon = Icons.help_outline;
      color = scheme.onSurfaceVariant;
    }
    return ListTile(
      leading: Icon(icon, color: color),
      title: Text(run.name,
          maxLines: 1, overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
      subtitle: Text(
        '${running ? "running" : (run.conclusion.isEmpty ? "?" : run.conclusion)}'
        '${run.headSha.isNotEmpty ? " · ${run.headSha}" : ""}'
        '${run.age.isNotEmpty ? " · ${run.age} geleden" : ""}',
        style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
      ),
      trailing: const Icon(Icons.open_in_new, size: 16),
      onTap: run.htmlUrl.isEmpty
          ? null
          : () => launchUrl(Uri.parse(run.htmlUrl)),
    );
  }
}

class _TabFrame extends StatelessWidget {
  final String title;
  final String? subtitle;
  final Widget child;
  final Future<void> Function()? onRefresh;
  const _TabFrame({required this.title, this.subtitle, required this.child, this.onRefresh});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: const TextStyle(
                            fontSize: 22, fontWeight: FontWeight.w700)),
                    if (subtitle != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(subtitle!,
                            style: TextStyle(
                                color: scheme.onSurfaceVariant, fontSize: 13)),
                      ),
                  ],
                ),
              ),
              if (onRefresh != null)
                IconButton(icon: const Icon(Icons.refresh), onPressed: onRefresh),
            ],
          ),
        ),
        Expanded(
          child: onRefresh != null
              ? RefreshIndicator(onRefresh: onRefresh!, child: child)
              : child,
        ),
      ],
    );
  }
}
