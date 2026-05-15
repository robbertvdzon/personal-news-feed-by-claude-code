import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

class DownloadsTab extends StatelessWidget {
  const DownloadsTab({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Padding(
          padding: EdgeInsets.fromLTRB(24, 20, 24, 12),
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
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 80),
            children: const [
              _DownloadCard(
                title: 'Personal News Feed',
                subtitle: 'De nieuws-feed app voor je telefoon',
                url: 'https://github.com/robbertvdzon/personal-news-feed-by-claude-code/releases/latest/download/app-release.apk',
              ),
              SizedBox(height: 12),
              _DownloadCard(
                title: 'Software Factory Dashboard',
                subtitle: 'Dit dashboard op je telefoon',
                url: '/download/dashboard.apk',
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _DownloadCard extends StatelessWidget {
  final String title;
  final String subtitle;
  final String url;
  const _DownloadCard({required this.title, required this.subtitle, required this.url});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => launchUrl(Uri.parse(url)),
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
                  ],
                ),
              ),
              FilledButton.tonalIcon(
                icon: const Icon(Icons.download, size: 16),
                label: const Text('APK'),
                onPressed: () => launchUrl(Uri.parse(url)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
