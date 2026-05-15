import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';
import 'story_detail_screen.dart';
import 'stories_screen.dart';

class HomeShell extends ConsumerStatefulWidget {
  const HomeShell({super.key});
  @override
  ConsumerState<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends ConsumerState<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _index,
        children: const [_HomeBody(), StoriesScreen()],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.home_outlined),
                                selectedIcon: Icon(Icons.home),
                                label: 'Home'),
          NavigationDestination(icon: Icon(Icons.history_outlined),
                                selectedIcon: Icon(Icons.history),
                                label: 'Stories'),
        ],
      ),
    );
  }
}

class _HomeBody extends ConsumerWidget {
  const _HomeBody();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stateAsync = ref.watch(homeStateProvider);
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 12,
        title: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 32,
              height: 32,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(Icons.precision_manufacturing,
                  size: 18,
                  color: Theme.of(context).colorScheme.onPrimaryContainer),
            ),
            const SizedBox(width: 10),
            const Text('Software Factory'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Uitloggen',
            onPressed: () => ref.read(authProvider.notifier).logout(),
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: (v) {
              if (v == 'apk-dashboard') {
                _launchUrl('/download/dashboard.apk');
              } else if (v == 'apk-pnf') {
                _launchUrl('https://github.com/robbertvdzon/personal-news-feed-by-claude-code/releases/latest/download/app-release.apk');
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(
                value: 'apk-dashboard',
                child: ListTile(
                  leading: Icon(Icons.android),
                  title: Text('Dashboard APK'),
                  contentPadding: EdgeInsets.zero,
                ),
              ),
              PopupMenuItem(
                value: 'apk-pnf',
                child: ListTile(
                  leading: Icon(Icons.android),
                  title: Text('PNF-app APK (laatste main-build)'),
                  contentPadding: EdgeInsets.zero,
                ),
              ),
            ],
          ),
        ],
      ),
      body: stateAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (state) => RefreshIndicator(
          onRefresh: () async => ref.invalidate(homeStateProvider),
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (state.main.sha.isNotEmpty) ...[
                const SectionHeader(title: 'Production', subtitle: 'main-branch'),
                _MainBuildCard(main: state.main),
                const SizedBox(height: 8),
              ],
              SectionHeader(
                  title: '🤖 AI bezig',
                  subtitle: '${state.aiActive.length} '
                      '${state.aiActive.length == 1 ? "story" : "stories"} in de pipeline'),
              if (state.aiActive.isEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text('Niets te doen.',
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.onSurfaceVariant)),
                  ),
                )
              else
                for (final c in state.aiActive)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: _AiCard(card: c),
                  ),
              SectionHeader(
                  title: 'Open PR\'s', subtitle: '${state.openPrs.length}'),
              if (state.openPrs.isEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text("Geen open PR's.",
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.onSurfaceVariant)),
                  ),
                )
              else
                for (final pr in state.openPrs)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: _PrCardWidget(pr: pr),
                  ),
              SectionHeader(title: 'Recent gemerged'),
              if (state.closedPrs.isEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text('—',
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.onSurfaceVariant)),
                  ),
                )
              else
                Card(
                  child: Column(
                    children: [
                      for (int i = 0; i < state.closedPrs.length; i++) ...[
                        if (i > 0) const Divider(height: 1),
                        ListTile(
                          dense: true,
                          title: Text('#${state.closedPrs[i]["number"]}'
                              ' — ${state.closedPrs[i]["title"]}'),
                          subtitle: Text(
                              '${state.closedPrs[i]["merged_age"]} geleden'),
                          trailing: const Icon(Icons.open_in_new, size: 16),
                        ),
                      ],
                    ],
                  ),
                ),
              const SizedBox(height: 80),
            ],
          ),
        ),
      ),
    );
  }
}

class _AiCard extends StatelessWidget {
  final JiraCard card;
  const _AiCard({required this.card});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => StoryDetailScreen(storyKey: card.key),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('${card.key} — ${card.title}',
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w600)),
                  ),
                  StatusPill(label: card.status),
                ],
              ),
              const SizedBox(height: 6),
              Row(
                children: [
                  Text('sinds ${card.age}',
                      style: TextStyle(
                          color: scheme.onSurfaceVariant, fontSize: 12)),
                  if (card.aiPhase.isNotEmpty) ...[
                    const SizedBox(width: 8),
                    StatusPill(
                      label: card.aiPhase,
                      bg: scheme.tertiaryContainer,
                      fg: scheme.onTertiaryContainer,
                    ),
                  ],
                ],
              ),
              if (card.tokensInput > 0 || card.costUsd > 0 || card.runCount > 0) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    if (card.runCount > 0)
                      TokenChip(
                          icon: card.runCount > 10
                              ? Icons.warning_amber_outlined
                              : Icons.smart_toy_outlined,
                          label: 'agents',
                          value: '${card.runCount}'),
                    TokenChip(label: 'in', value: formatTokens(card.tokensInput)),
                    TokenChip(label: 'out', value: formatTokens(card.tokensOutput)),
                    TokenChip(
                        label: 'cache-r',
                        value: formatTokens(card.tokensCacheRead)),
                    TokenChip(
                        label: 'cost',
                        value: '\$${card.costUsd.toStringAsFixed(4)}'),
                  ],
                ),
              ],
              if (card.runCount > 10) ...[
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.errorContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.warning_amber_outlined,
                          size: 14, color: Theme.of(context).colorScheme.onErrorContainer),
                      const SizedBox(width: 6),
                      Text('Veel runs — mogelijke loop',
                          style: TextStyle(
                              fontSize: 11,
                              color: Theme.of(context).colorScheme.onErrorContainer,
                              fontWeight: FontWeight.w600)),
                    ],
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _PrCardWidget extends StatelessWidget {
  final PrCard pr;
  const _PrCardWidget({required this.pr});

  static final _branchRe = RegExp(r'^ai/([A-Z][A-Z0-9]+-[0-9]+)$');

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final keyMatch = _branchRe.firstMatch(pr.branch);
    final storyKey = keyMatch?.group(1);
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: storyKey != null
            ? () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => StoryDetailScreen(storyKey: storyKey),
                  ),
                )
            : () => _launchUrl(pr.htmlUrl),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('#${pr.number} — ${pr.title}',
                        style: const TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w600)),
                  ),
                  if (storyKey != null)
                    StatusPill(
                      label: storyKey,
                      bg: scheme.tertiaryContainer,
                      fg: scheme.onTertiaryContainer,
                    ),
                ],
              ),
              const SizedBox(height: 6),
              if (pr.jiraStatus.isNotEmpty || pr.aiPhase.isNotEmpty)
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    if (pr.jiraStatus.isNotEmpty)
                      StatusPill(label: pr.jiraStatus),
                    if (pr.aiPhase.isNotEmpty)
                      StatusPill(
                        label: pr.aiPhase,
                        bg: scheme.tertiaryContainer,
                        fg: scheme.onTertiaryContainer,
                      ),
                  ],
                ),
              const SizedBox(height: 6),
              Text(
                  '${pr.author} · ${pr.branch} · updated ${pr.updatedAge}',
                  style:
                      TextStyle(color: scheme.onSurfaceVariant, fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }
}

class _MainBuildCard extends StatelessWidget {
  final MainBuild main;
  const _MainBuildCard({required this.main});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final phasesOk = main.phases.where((p) => p['status'] == 'pass').length;
    final phasesTotal = main.phases.length;
    final allGreen = phasesTotal > 0 && phasesOk == phasesTotal;
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: main.previewUrl.isNotEmpty ? () => _launchUrl(main.previewUrl) : null,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 32,
                    height: 32,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: allGreen ? scheme.tertiaryContainer : scheme.secondaryContainer,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Icon(
                      allGreen ? Icons.check_circle : Icons.build_circle_outlined,
                      color: allGreen ? scheme.onTertiaryContainer : scheme.onSecondaryContainer,
                      size: 18,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(main.sha.isNotEmpty ? main.sha : '—',
                            style: const TextStyle(
                                fontSize: 14, fontWeight: FontWeight.w700, fontFamily: 'monospace')),
                        Text(main.shaAge.isNotEmpty ? '${main.shaAge} geleden' : 'unknown age',
                            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12)),
                      ],
                    ),
                  ),
                  if (phasesTotal > 0)
                    StatusPill(
                      label: '$phasesOk/$phasesTotal groen',
                      bg: allGreen ? scheme.tertiaryContainer : scheme.errorContainer,
                      fg: allGreen ? scheme.onTertiaryContainer : scheme.onErrorContainer,
                    ),
                ],
              ),
              if (main.phases.isNotEmpty) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: main.phases.map((p) {
                    final s = (p['status'] as String?) ?? '';
                    final ok = s == 'pass';
                    final running = s == 'running';
                    return Chip(
                      visualDensity: VisualDensity.compact,
                      label: Text(
                        '${p['label']}',
                        style: const TextStyle(fontSize: 11),
                      ),
                      avatar: Icon(
                        ok ? Icons.check : (running ? Icons.sync : Icons.error_outline),
                        size: 14,
                        color: ok
                            ? scheme.onTertiaryContainer
                            : (running ? scheme.onSecondaryContainer : scheme.onErrorContainer),
                      ),
                      backgroundColor: ok
                          ? scheme.tertiaryContainer
                          : (running ? scheme.secondaryContainer : scheme.errorContainer),
                    );
                  }).toList(),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

Future<void> _launchUrl(String url) async {
  final uri = Uri.parse(url);
  await launchUrl(uri, mode: LaunchMode.platformDefault);
}
