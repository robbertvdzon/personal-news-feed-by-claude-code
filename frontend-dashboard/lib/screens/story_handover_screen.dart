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
            _AgentSection(
              title: 'Refiner — context en aannames',
              icon: Icons.psychology_outlined,
              run: data.refiner,
            ),
            _AgentSection(
              title: 'Developer — wat is gebouwd',
              icon: Icons.code,
              run: data.developer,
            ),
            _AgentSection(
              title: 'Reviewer — code-review',
              icon: Icons.rate_review_outlined,
              run: data.reviewer,
            ),
            _AgentSection(
              title: 'Tester — test-rapport',
              icon: Icons.science_outlined,
              run: data.tester,
            ),
            const SizedBox(height: 40),
          ],
        ),
      ),
    );
  }
}

class _AgentSection extends StatelessWidget {
  final String title;
  final IconData icon;
  final AgentRun? run;
  const _AgentSection({required this.title, required this.icon, required this.run});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final present = run != null && run!.summaryText.isNotEmpty;
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
                    width: 36,
                    height: 36,
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
                  if (run != null)
                    StatusPill(
                      label: run!.outcome,
                      bg: run!.outcome == 'success'
                          ? scheme.tertiaryContainer
                          : scheme.errorContainer,
                      fg: run!.outcome == 'success'
                          ? scheme.onTertiaryContainer
                          : scheme.onErrorContainer,
                    ),
                ],
              ),
              const SizedBox(height: 12),
              if (!present)
                Text('Nog niet gedraaid.',
                    style: TextStyle(
                        color: scheme.onSurfaceVariant,
                        fontStyle: FontStyle.italic))
              else
                SelectableText(
                  _stripJsonLine(run!.summaryText),
                  style: const TextStyle(fontSize: 14, height: 1.5),
                ),
            ],
          ),
        ),
      ),
    );
  }

  String _stripJsonLine(String s) {
    // Verwijder de slot-JSON (`{"phase": ...}`) onderaan agent-summaries.
    final re = RegExp(r'\n?\s*\{\s*"phase".*?\}\s*$', dotAll: true);
    return s.replaceAll(re, '').trim();
  }
}
