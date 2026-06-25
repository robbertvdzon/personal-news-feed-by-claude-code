import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/api_client.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';

/// SF-220: aparte subpagina van Instellingen waarop zowel de gewone
/// RSS-feed-URL's als de podcast-RSS-bronnen beheerd worden. Verhuisd
/// uit `settings_screen.dart` om die pagina korter te houden; gedrag
/// van beide editors is ongewijzigd.
class RssFeedsScreen extends ConsumerWidget {
  const RssFeedsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final feeds = ref.watch(rssFeedsProvider);
    final podcastFeeds = ref.watch(podcastFeedsProvider);
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return Scaffold(
      appBar: AppBar(
        title: const Text('RSS-feeds'),
      ),
      body: ListView(padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset), children: [
        Text('RSS-feeds', style: Theme.of(context).textTheme.titleLarge),
        feeds.when(
          data: (list) => _RssFeedsEditor(feeds: list),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Text('Fout: $e'),
        ),
        const Divider(),
        Text('Podcast-bronnen', style: Theme.of(context).textTheme.titleLarge),
        podcastFeeds.when(
          data: (list) => _PodcastFeedsEditor(feeds: list),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Text('Fout: $e'),
        ),
      ]),
    );
  }
}

class _RssFeedsEditor extends ConsumerStatefulWidget {
  final List<String> feeds;
  const _RssFeedsEditor({required this.feeds});

  @override
  ConsumerState<_RssFeedsEditor> createState() => _RssFeedsEditorState();
}

class _RssFeedsEditorState extends ConsumerState<_RssFeedsEditor> {
  final _controller = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      for (final f in widget.feeds)
        ListTile(
          title: Text(f, style: const TextStyle(fontFamily: 'monospace')),
          onTap: () => launchUrl(Uri.parse(f), mode: LaunchMode.externalApplication),
          trailing: IconButton(
            icon: const Icon(Icons.close),
            onPressed: () {
              final next = widget.feeds.where((x) => x != f).toList();
              ref.read(rssFeedsProvider.notifier).save(next);
            },
          ),
        ),
      Row(children: [
        Expanded(
          child: TextField(
            controller: _controller,
            decoration: const InputDecoration(labelText: 'Nieuwe feed-URL', hintText: 'https://...'),
            onSubmitted: (_) => _add(),
          ),
        ),
        IconButton(icon: const Icon(Icons.add), onPressed: _add),
      ]),
    ]);
  }

  void _add() {
    final url = _controller.text.trim();
    if (url.isEmpty) return;
    final next = [...widget.feeds, url];
    ref.read(rssFeedsProvider.notifier).save(next);
    _controller.clear();
  }
}

/// KAN-56: tegenhanger van [_RssFeedsEditor] voor podcast-RSS-bronnen.
/// Per bron is er een 'Transcriberen aan/uit'-toggle: wanneer uit, valt
/// de backend terug op de show-notes als input voor Claude (zonder
/// Whisper-kosten). 'Toevoegen' valideert de URL synchroon op de
/// server — een ongeldige URL geeft een snackbar binnen ~10s (AC #7).
class _PodcastFeedsEditor extends ConsumerStatefulWidget {
  final List<PodcastFeed> feeds;
  const _PodcastFeedsEditor({required this.feeds});

  @override
  ConsumerState<_PodcastFeedsEditor> createState() => _PodcastFeedsEditorState();
}

class _PodcastFeedsEditorState extends ConsumerState<_PodcastFeedsEditor> {
  final _controller = TextEditingController();
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      for (final f in widget.feeds)
        ListTile(
          title: Text(f.url, style: const TextStyle(fontFamily: 'monospace')),
          subtitle: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.podcasts, size: 14),
              const SizedBox(width: 4),
              Text(
                f.transcribeEnabled ? 'Transcriberen aan' : 'Transcriberen uit',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
          onTap: () => launchUrl(Uri.parse(f.url), mode: LaunchMode.externalApplication),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Switch(
                value: f.transcribeEnabled,
                onChanged: _busy
                    ? null
                    : (v) {
                        final next = widget.feeds
                            .map((x) => x.url == f.url ? x.copyWith(transcribeEnabled: v) : x)
                            .toList();
                        _save(next, validateFailureMessage: null);
                      },
              ),
              IconButton(
                icon: const Icon(Icons.close),
                onPressed: _busy
                    ? null
                    : () {
                        final next = widget.feeds.where((x) => x.url != f.url).toList();
                        _save(next, validateFailureMessage: null);
                      },
              ),
            ],
          ),
        ),
      Row(children: [
        Expanded(
          child: TextField(
            controller: _controller,
            enabled: !_busy,
            decoration: const InputDecoration(
              labelText: 'Nieuwe podcast-RSS-URL',
              hintText: 'https://...',
            ),
            onSubmitted: _busy ? null : (_) => _add(),
          ),
        ),
        if (_busy)
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 12),
            child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
          )
        else
          IconButton(icon: const Icon(Icons.add), onPressed: _add),
      ]),
    ]);
  }

  void _add() {
    final url = _controller.text.trim();
    if (url.isEmpty) return;
    final next = [...widget.feeds, PodcastFeed(url: url, transcribeEnabled: true)];
    _save(next, validateFailureMessage: 'Kon feed niet ophalen');
  }

  Future<void> _save(List<PodcastFeed> next, {String? validateFailureMessage}) async {
    setState(() => _busy = true);
    try {
      await ref.read(podcastFeedsProvider.notifier).save(next);
      if (validateFailureMessage != null) _controller.clear();
    } catch (e) {
      if (!mounted) return;
      // Backend stuurt bij een ongeldige URL HTTP 400 met een
      // Nederlandse foutmelding in de body — die tonen we direct.
      final msg = e is ApiException && e.statusCode == 400
          ? _extractDutchMessage(e.body)
          : (validateFailureMessage ?? 'Fout bij opslaan: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(msg),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Spring's ResponseStatusException serialiseert naar een JSON-blob met
  /// een `message`-veld. We pakken dat eruit; falt back op de raw body.
  String _extractDutchMessage(String body) {
    final raw = body.trim();
    if (raw.startsWith('{')) {
      // Heel simpele extractie — geen JSON-parser nodig.
      final match = RegExp('"message"\\s*:\\s*"([^"]+)"').firstMatch(raw);
      if (match != null) return match.group(1) ?? raw;
    }
    return raw.isEmpty ? 'Kon feed niet ophalen' : raw;
  }
}
