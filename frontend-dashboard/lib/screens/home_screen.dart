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
        title: const Text('Software Factory'),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Uitloggen',
            onPressed: () => ref.read(authProvider.notifier).logout(),
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: (v) {
              if (v == 'apk') {
                _launchUrl('/download/dashboard.apk');
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(
                value: 'apk',
                child: ListTile(
                  leading: Icon(Icons.android),
                  title: Text('Android APK downloaden'),
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
              if (card.tokensInput > 0 || card.costUsd > 0) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
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

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('#${pr.number} — ${pr.title}',
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Text(
                '${pr.author} · ${pr.branch} · updated ${pr.updatedAge}',
                style:
                    TextStyle(color: scheme.onSurfaceVariant, fontSize: 12)),
          ],
        ),
      ),
    );
  }
}

Future<void> _launchUrl(String url) async {
  final uri = Uri.parse(url);
  await launchUrl(uri, mode: LaunchMode.platformDefault);
}
