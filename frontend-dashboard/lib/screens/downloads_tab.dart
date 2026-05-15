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

class DownloadsTab extends ConsumerWidget {
  const DownloadsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(apksProvider);
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
                    Text('Downloads',
                        style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                    Padding(
                      padding: EdgeInsets.only(top: 2),
                      child: Text('Android-APK\'s van de PNF-apps',
                          style: TextStyle(fontSize: 13)),
                    ),
                  ],
                ),
              ),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: () => ref.invalidate(apksProvider),
              ),
            ],
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('Fout: $e')),
            data: (apks) => ListView(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 80),
              children: [
                _DownloadCard(
                  title: 'Personal News Feed',
                  subtitle: 'De nieuws-feed app voor je telefoon',
                  apk: apks.pnf,
                ),
                const SizedBox(height: 12),
                _DownloadCard(
                  title: 'Software Factory Dashboard',
                  subtitle: 'Dit dashboard op je telefoon',
                  apk: apks.dashboard,
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _DownloadCard extends StatelessWidget {
  final String title;
  final String subtitle;
  final ApkEntry apk;
  const _DownloadCard({required this.title, required this.subtitle, required this.apk});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final builtLabel = apk.builtAt == null || apk.builtAt!.isEmpty
        ? ''
        : 'Gebouwd: ${formatTs(apk.builtAt)}';
    final sizeLabel = apk.size > 0
        ? '${(apk.size / 1024 / 1024).toStringAsFixed(1)} MB'
        : '';
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => _openDownload(apk.url),
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
                    if (builtLabel.isNotEmpty || sizeLabel.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(
                          [builtLabel, sizeLabel].where((s) => s.isNotEmpty).join(' · '),
                          style: TextStyle(
                              fontSize: 11,
                              color: scheme.onSurfaceVariant),
                        ),
                      ),
                  ],
                ),
              ),
              FilledButton.tonalIcon(
                icon: const Icon(Icons.download, size: 16),
                label: const Text('APK'),
                onPressed: () => _openDownload(apk.url),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
