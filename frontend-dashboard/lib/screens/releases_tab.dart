import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../providers/data_providers.dart';

class ReleasesTab extends ConsumerWidget {
  const ReleasesTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(homeStateProvider);
    final scheme = Theme.of(context).colorScheme;
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
                    Text('Recent gemerged',
                        style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                    Padding(
                      padding: EdgeInsets.only(top: 2),
                      child: Text('Alle PR\'s die naar main zijn gemerged',
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
            data: (s) => ListView(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 80),
              children: [
                Card(
                  child: Column(
                    children: [
                      for (int i = 0; i < s.closedPrs.length; i++) ...[
                        if (i > 0) Divider(height: 1, color: scheme.outlineVariant),
                        ListTile(
                          dense: true,
                          leading: Container(
                            width: 36, height: 36,
                            alignment: Alignment.center,
                            decoration: BoxDecoration(
                              color: scheme.tertiaryContainer,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Icon(Icons.merge_type,
                                color: scheme.onTertiaryContainer, size: 18),
                          ),
                          title: Text(
                            '#${s.closedPrs[i]["number"]} — ${s.closedPrs[i]["title"]}',
                            style: const TextStyle(fontSize: 14),
                          ),
                          subtitle: Text(
                            '${s.closedPrs[i]["merged_age"]} geleden',
                            style: TextStyle(
                                fontSize: 12, color: scheme.onSurfaceVariant),
                          ),
                          trailing: Icon(Icons.open_in_new,
                              size: 16, color: scheme.onSurfaceVariant),
                          onTap: () {
                            final url = s.closedPrs[i]['html_url']?.toString();
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
            ),
          ),
        ),
      ],
    );
  }
}
