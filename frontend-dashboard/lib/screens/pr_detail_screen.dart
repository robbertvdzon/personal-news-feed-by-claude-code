import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/models.dart';
import '../widgets/info_table.dart';
import '../widgets/section_header.dart';

/// Detail-view voor closed PRs zonder ai/KEY-N branch (ci bumps, hand-PRs).
/// Sober: alleen wat we hebben uit het closed_prs-blok van de state-API.
/// AI-stories gaan via StoryDetailScreen omdat die agent-runs/briefing/etc.
/// extra velden hebben.
class PrDetailScreen extends StatelessWidget {
  final ClosedPr pr;
  const PrDetailScreen({super.key, required this.pr});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final shortSha = pr.headSha.length >= 7 ? pr.headSha.substring(0, 7) : pr.headSha;
    return Scaffold(
      appBar: AppBar(title: Text('PR #${pr.number}')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(pr.title,
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
          ),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: scheme.secondaryContainer,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              children: [
                Icon(Icons.merge_type, color: scheme.onSecondaryContainer),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    pr.mergedAge.isEmpty
                        ? 'Gemerged'
                        : 'Gemerged · ${pr.mergedAge} geleden',
                    style: TextStyle(
                        color: scheme.onSecondaryContainer,
                        fontSize: 15,
                        fontWeight: FontWeight.w700),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('LINKS',
                      style: TextStyle(
                        color: scheme.onSurfaceVariant,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.4,
                      )),
                  const SizedBox(height: 10),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      OutlinedButton.icon(
                        icon: const Icon(Icons.open_in_new, size: 16),
                        label: Text('PR #${pr.number} op GitHub'),
                        onPressed: () => launchUrl(Uri.parse(pr.htmlUrl)),
                      ),
                      if (pr.headSha.isNotEmpty)
                        OutlinedButton.icon(
                          icon: const Icon(Icons.commit, size: 16),
                          label: Text('Commit $shortSha'),
                          onPressed: () => launchUrl(Uri.parse(
                              'https://github.com/robbertvdzon/personal-news-feed-by-claude-code/commit/${pr.headSha}')),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          InfoTable(
            title: 'OVERZICHT',
            rows: [
              ('PR-nummer', '#${pr.number}'),
              ('Branch', pr.branch.isEmpty ? '—' : pr.branch),
              ('Head SHA', shortSha.isEmpty ? '—' : shortSha),
              ('Gemerged', formatTs(pr.mergedAt)),
              ('Tijd geleden', pr.mergedAge.isEmpty ? '—' : pr.mergedAge),
            ],
          ),
          const SizedBox(height: 40),
        ],
      ),
    );
  }
}
