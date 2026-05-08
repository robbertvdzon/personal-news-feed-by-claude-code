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

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final p = await ref.read(podcastProvider.notifier).getDetail(widget.podcastId);
    setState(() => _podcast = p);
    if (p.status == 'DONE') await _initAudio(p);
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
                    Chip(label: Text(p.status)),
                    Chip(label: Text('${p.durationMinutes} min')),
                    Chip(label: Text(p.ttsProvider)),
                    if (p.costUsd > 0) Chip(label: Text('\$${p.costUsd.toStringAsFixed(4)}')),
                  ]),
                  const SizedBox(height: 16),
                  Wrap(spacing: 4, children: p.topics.map((t) => Chip(label: Text(t))).toList()),
                  const SizedBox(height: 16),
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
                          final url = '${ApiClient.baseUrl}/api/podcasts/${p.id}/audio?token=${api.token}';
                          launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
                        },
                      ),
                    ]),
                  ] else
                    Card(child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Row(children: [
                        const SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2)),
                        const SizedBox(width: 12),
                        Expanded(child: Text('Bezig met genereren: ${p.status}')),
                      ]),
                    )),
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
