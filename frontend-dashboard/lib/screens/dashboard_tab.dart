import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/models.dart';
import '../providers/data_providers.dart';
import '../widgets/section_header.dart';

class DashboardTab extends ConsumerWidget {
  const DashboardTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(homeStateProvider);
    return _TabFrame(
      title: 'Dashboard',
      subtitle: 'Overzicht van builds en productie',
      onRefresh: () async => ref.invalidate(homeStateProvider),
      child: state.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (s) => ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 80),
          children: [
            const SectionHeader(title: 'Production', subtitle: 'main-branch'),
            _ProductionCard(main: s.main),
          ],
        ),
      ),
    );
  }
}

class _ProductionCard extends StatelessWidget {
  final MainBuild main;
  const _ProductionCard({required this.main});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final phasesOk = main.phases.where((p) => p['status'] == 'pass').length;
    final phasesTotal = main.phases.length;
    final allGreen = phasesTotal > 0 && phasesOk == phasesTotal;
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: main.previewUrl.isNotEmpty ? () => launchUrl(Uri.parse(main.previewUrl)) : null,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  StatusPill(
                    label: allGreen ? '● Healthy' : '$phasesOk / $phasesTotal groen',
                    bg: allGreen ? const Color(0xFFE6F7EC) : const Color(0xFFFFF4E5),
                    fg: allGreen ? const Color(0xFF1E6B3E) : const Color(0xFF8A5A0B),
                  ),
                  const Spacer(),
                  Icon(Icons.chevron_right, color: scheme.onSurfaceVariant),
                ],
              ),
              const SizedBox(height: 12),
              Text(main.sha.isNotEmpty ? main.sha : '—',
                  style: const TextStyle(
                      fontFamily: 'monospace', fontSize: 13)),
              Text(main.shaAge.isNotEmpty ? '${main.shaAge} geleden' : 'unknown age',
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12)),
              if (main.phases.isNotEmpty) ...[
                const SizedBox(height: 12),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: main.phases.map((p) {
                    final s = (p['status'] as String?) ?? '';
                    final ok = s == 'pass';
                    final running = s == 'running';
                    final fail = s == 'fail';
                    final color = ok
                        ? const Color(0xFFE6F7EC)
                        : (running ? const Color(0xFFE5F0FE) : (fail ? const Color(0xFFFDECEC) : const Color(0xFFF1F3F8)));
                    final iconColor = ok
                        ? const Color(0xFF1E6B3E)
                        : (running ? const Color(0xFF1E40AF) : (fail ? const Color(0xFF991B1B) : const Color(0xFF6B7280)));
                    return Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                          color: color, borderRadius: BorderRadius.circular(8)),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            ok ? Icons.check_circle_outline
                              : (running ? Icons.sync : (fail ? Icons.error_outline : Icons.radio_button_unchecked)),
                            size: 13, color: iconColor,
                          ),
                          const SizedBox(width: 6),
                          Text('${p['label']}', style: TextStyle(fontSize: 12, color: iconColor, fontWeight: FontWeight.w600)),
                        ],
                      ),
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
