import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:just_audio/just_audio.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/api_client.dart';
import '../models/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';
import 'rss_podcast_detail_screen.dart';

class PodcastDetailScreen extends ConsumerStatefulWidget {
  final String podcastId;
  const PodcastDetailScreen({super.key, required this.podcastId});

  @override
  ConsumerState<PodcastDetailScreen> createState() => _PodcastDetailScreenState();
}

class _PodcastDetailScreenState extends ConsumerState<PodcastDetailScreen> {
  Podcast? _podcast;
  final _player = AudioPlayer();
  Timer? _saveTimer;
  /// KAN-63: pollt status zolang de podcast nog niet DONE/FAILED is.
  /// De translate-flow heeft eigen statussen (TRANSLATING / TTS_GENERATING)
  /// die op deze detail-pagina ook moeten kunnen updaten.
  Timer? _statusPollTimer;

  static const _inProgressStatuses = {
    'PENDING',
    'DETERMINING_TOPICS',
    'GENERATING_SCRIPT',
    'GENERATING_AUDIO',
    'TRANSLATING',
    'TTS_GENERATING',
  };

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final p = await ref.read(podcastProvider.notifier).getDetail(widget.podcastId);
    setState(() => _podcast = p);
    if (p.status == 'DONE') {
      _statusPollTimer?.cancel();
      _statusPollTimer = null;
      await _initAudio(p);
    } else if (_inProgressStatuses.contains(p.status)) {
      _statusPollTimer ??= Timer.periodic(const Duration(seconds: 4), (_) => _poll());
    }
  }

  Future<void> _poll() async {
    try {
      final p = await ref.read(podcastProvider.notifier).getDetail(widget.podcastId);
      if (!mounted) return;
      setState(() => _podcast = p);
      if (p.status == 'DONE') {
        _statusPollTimer?.cancel();
        _statusPollTimer = null;
        await _initAudio(p);
      } else if (p.status == 'FAILED') {
        _statusPollTimer?.cancel();
        _statusPollTimer = null;
      }
    } catch (_) {
      // ignore, retry on next tick
    }
  }

  Future<void> _initAudio(Podcast p) async {
    final api = ref.read(apiProvider);
    final token = api.token;
    final url = '${ApiClient.baseUrl}/api/podcasts/${p.id}/audio?token=$token&v=${p.durationSeconds ?? 0}';
    try {
      await _player.setUrl(url);
      final prefs = await SharedPreferences.getInstance();
      final pos = prefs.getInt('podcast_pos_${p.id}');
      if (pos != null) await _player.seek(Duration(seconds: pos));
      _saveTimer = Timer.periodic(const Duration(seconds: 5), (_) async {
        final cur = _player.position;
        final dur = _player.duration ?? Duration.zero;
        if (dur.inSeconds == 0) return;
        if ((dur - cur).inSeconds < 10) {
          await prefs.remove('podcast_pos_${p.id}');
        } else {
          await prefs.setInt('podcast_pos_${p.id}', cur.inSeconds);
        }
      });
    } catch (e) {
      debugPrint('Audio load failed: $e');
    }
  }

  @override
  void dispose() {
    _saveTimer?.cancel();
    _statusPollTimer?.cancel();
    _player.dispose();
    super.dispose();
  }

  Future<void> _showScript() async {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => DraggableScrollableSheet(
        expand: false,
        builder: (ctx, controller) => SingleChildScrollView(
          controller: controller,
          padding: const EdgeInsets.all(16),
          child: SelectableText(_podcast?.scriptText ?? 'Script niet beschikbaar.'),
        ),
      ),
    );
  }

  String _fmt(Duration d) =>
      '${d.inMinutes.toString().padLeft(2, '0')}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final p = _podcast;
    // Android system gesture bar (en iOS home indicator) zit onderin het
    // scherm; zonder extra padding verdwijnen Draaiboek/Download-knoppen
    // erachter en zijn ze niet aan te tikken. SafeArea + extra bottom-
    // padding (16 +/- system inset) vangt dat op.
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return Scaffold(
      appBar: AppBar(title: Text(p?.title ?? 'Podcast')),
      body: p == null
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(p.title, style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 8),
                  Wrap(spacing: 8, children: [
                    Chip(label: Text(_statusLabel(p.status))),
                    Chip(label: Text('${p.durationMinutes} min')),
                    Chip(label: Text(p.ttsProvider)),
                  ]),
                  if (p.isTranslation) ...[
                    const SizedBox(height: 12),
                    _TranslatedFromBadge(podcast: p),
                  ],
                  const SizedBox(height: 16),
                  Wrap(spacing: 4, children: p.topics.map((t) => Chip(label: Text(t))).toList()),
                  const SizedBox(height: 16),
                  if (p.status == 'FAILED' && (p.errorMessage ?? '').isNotEmpty) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.errorContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(children: [
                        Icon(Icons.error_outline,
                            color: Theme.of(context).colorScheme.onErrorContainer),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            p.errorMessage!,
                            style: TextStyle(
                                color: Theme.of(context).colorScheme.onErrorContainer),
                          ),
                        ),
                      ]),
                    ),
                    const SizedBox(height: 16),
                  ],
                  if (p.status == 'DONE') ...[
                    StreamBuilder<Duration>(
                      stream: _player.positionStream,
                      builder: (ctx, snap) {
                        final pos = snap.data ?? Duration.zero;
                        final dur = _player.duration ?? Duration.zero;
                        return Column(children: [
                          Slider(
                            value: pos.inMilliseconds.toDouble().clamp(0, dur.inMilliseconds.toDouble()),
                            max: dur.inMilliseconds.toDouble().clamp(1, double.infinity),
                            onChanged: (v) => _player.seek(Duration(milliseconds: v.round())),
                          ),
                          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                            Text(_fmt(pos)),
                            Text(_fmt(dur)),
                          ]),
                        ]);
                      },
                    ),
                    Wrap(spacing: 8, alignment: WrapAlignment.center, children: [
                      _skipBtn(-60),
                      _skipBtn(-30),
                      _skipBtn(-15),
                      StreamBuilder<bool>(
                        stream: _player.playingStream,
                        builder: (ctx, snap) {
                          final playing = snap.data ?? false;
                          return IconButton.filled(
                            iconSize: 48,
                            icon: Icon(playing ? Icons.pause : Icons.play_arrow),
                            onPressed: () => playing ? _player.pause() : _player.play(),
                          );
                        },
                      ),
                      _skipBtn(15),
                      _skipBtn(30),
                      _skipBtn(60),
                    ]),
                    const SizedBox(height: 12),
                    Wrap(spacing: 8, children: [
                      OutlinedButton.icon(
                        icon: const Icon(Icons.article),
                        label: const Text('Draaiboek'),
                        onPressed: _showScript,
                      ),
                      OutlinedButton.icon(
                        icon: const Icon(Icons.download),
                        label: const Text('Download'),
                        onPressed: () {
                          final api = ref.read(apiProvider);
                          // ?download=1 → backend zet Content-Disposition op
                          // attachment, browser triggert dan een echte
                          // download met de podcast-titel als filename
                          // i.p.v. inline-player in een nieuwe tab te openen.
                          final url = '${ApiClient.baseUrl}/api/podcasts/${p.id}/audio'
                              '?token=${api.token}&download=1';
                          launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
                        },
                      ),
                    ]),
                  ] else if (p.status != 'FAILED') ...[
                    Card(child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Row(children: [
                        const SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2)),
                        const SizedBox(width: 12),
                        Expanded(child: Text('Bezig met genereren: ${_statusLabel(p.status)}')),
                      ]),
                    )),
                  ],
                ],
              ),
            ),
    );
  }

  Widget _skipBtn(int seconds) => IconButton(
        icon: Icon(seconds < 0 ? Icons.replay : Icons.forward_30, size: 32),
        tooltip: '${seconds > 0 ? '+' : ''}${seconds}s',
        onPressed: () => _player.seek(_player.position + Duration(seconds: seconds)),
      );
}

/// KAN-63: status-label in NL. Bestaat zowel hier als in [podcast_screen.dart];
/// twee plekken houden is goedkoper dan een gedeelde util-file aanmaken.
String _statusLabel(String status) {
  switch (status) {
    case 'PENDING':
      return 'In wachtrij…';
    case 'DETERMINING_TOPICS':
      return 'Onderwerpen bepalen…';
    case 'GENERATING_SCRIPT':
      return 'Script schrijven…';
    case 'GENERATING_AUDIO':
      return 'Audio genereren…';
    case 'TRANSLATING':
      return 'Vertalen…';
    case 'TTS_GENERATING':
      return 'Audio genereren…';
    case 'DONE':
      return 'Klaar';
    case 'FAILED':
      return 'Mislukt';
    default:
      return status;
  }
}

/// KAN-63: "vertaald van <feed-naam>" badge met tap-actie die de
/// gebruiker terugbrengt naar het RSS-podcast-detail-scherm van de
/// bron-aflevering. Lookup gebeurt op `translatedFromRssItemId` in de
/// rss-provider; als de bron uit de RSS-tab verdwenen is (cleanup of
/// nooit aanwezig) is de chip non-interactive.
class _TranslatedFromBadge extends ConsumerWidget {
  final Podcast podcast;
  const _TranslatedFromBadge({required this.podcast});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final feedName = podcast.translatedFromFeedName ?? 'RSS podcast';
    final rssItemId = podcast.translatedFromRssItemId;
    final rssItems = ref.watch(rssProvider).value ?? const <RssItem>[];
    RssItem? source;
    if (rssItemId != null) {
      for (final it in rssItems) {
        if (it.id == rssItemId) { source = it; break; }
      }
    }
    final theme = Theme.of(context);
    final chip = Chip(
      avatar: const Icon(Icons.translate, size: 16),
      label: Text('Vertaald van $feedName'),
      backgroundColor: theme.colorScheme.secondaryContainer,
    );
    final src = source;
    if (src == null) return chip;
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: () {
        // We tonen alleen het bron-item zelf in de pageview (initialIndex=0)
        // — geen swipe-context want we komen via een directe sprong vanuit
        // de podcast-detail, niet vanuit een lijst.
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => RssPodcastDetailScreen(
            items: [src],
            initialIndex: 0,
          ),
        ));
      },
      child: chip,
    );
  }
}
