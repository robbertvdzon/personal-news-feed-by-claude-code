import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import '../widgets/app_logo.dart';
import 'podcast_detail_screen.dart';

class PodcastScreen extends ConsumerStatefulWidget {
  const PodcastScreen({super.key});

  @override
  ConsumerState<PodcastScreen> createState() => _PodcastScreenState();
}

bool _isInProgress(String status) =>
    status == 'PENDING' ||
    status == 'DETERMINING_TOPICS' ||
    status == 'GENERATING_SCRIPT' ||
    status == 'GENERATING_AUDIO';

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
    case 'DONE':
      return 'Klaar';
    case 'FAILED':
      return 'Mislukt';
    default:
      return status;
  }
}

class _PodcastScreenState extends ConsumerState<PodcastScreen> {
  Timer? _pollTimer;

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  void _maybePoll(List<Podcast> podcasts) {
    final pending = podcasts.any((p) =>
        p.status == 'PENDING' ||
        p.status == 'DETERMINING_TOPICS' ||
        p.status == 'GENERATING_SCRIPT' ||
        p.status == 'GENERATING_AUDIO');
    if (pending) {
      _pollTimer ??= Timer.periodic(const Duration(seconds: 4),
          (_) => ref.read(podcastProvider.notifier).poll());
    } else {
      _pollTimer?.cancel();
      _pollTimer = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final podcasts = ref.watch(podcastProvider);
    return Scaffold(
      appBar: AppBar(
        leading: const AppLogo(),
        title: const Text('Podcast'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: () => ref.read(podcastProvider.notifier).reload()),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.add),
        label: const Text('Nieuwe podcast'),
        onPressed: () => _create(),
      ),
      body: podcasts.when(
        data: (items) {
          _maybePoll(items);
          return items.isEmpty
              ? const Center(child: Text('Nog geen podcasts'))
              : ListView.builder(
                  itemCount: items.length,
                  itemBuilder: (ctx, i) {
                    final p = items[i];
                    return Dismissible(
                      key: Key('pod_${p.id}'),
                      direction: DismissDirection.endToStart,
                      onDismissed: (_) => ref.read(podcastProvider.notifier).delete(p.id),
                      background: Container(color: Colors.red, alignment: Alignment.centerRight, padding: const EdgeInsets.only(right: 16), child: const Icon(Icons.delete, color: Colors.white)),
                      child: Card(
                        child: ListTile(
                          leading: _isInProgress(p.status)
                              ? const SizedBox(
                                  width: 32,
                                  height: 32,
                                  child: CircularProgressIndicator(strokeWidth: 2),
                                )
                              : Icon(p.status == 'FAILED' ? Icons.error : Icons.podcasts,
                                  color: p.status == 'FAILED' ? Colors.red : null),
                          title: Text(p.title.isEmpty ? 'DevTalk ${p.podcastNumber}' : p.title),
                          subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text(_statusLabel(p.status), style: TextStyle(
                              color: _isInProgress(p.status) ? Theme.of(context).colorScheme.primary : null,
                              fontWeight: _isInProgress(p.status) ? FontWeight.bold : null,
                            )),
                            Text('Duur: ${p.durationMinutes}min · TTS: ${p.ttsProvider}'),
                          ]),
                          onTap: () => Navigator.of(context).push(
                            MaterialPageRoute(builder: (_) => PodcastDetailScreen(podcastId: p.id)),
                          ),
                        ),
                      ),
                    );
                  },
                );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
      ),
    );
  }

  Future<void> _create() async {
    final topicsCtrl = TextEditingController();
    final daysCtrl = TextEditingController(text: '7');
    final durationCtrl = TextEditingController(text: '15');
    String provider = 'OPENAI';
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setS) {
        final days = int.tryParse(daysCtrl.text) ?? 0;
        final duration = int.tryParse(durationCtrl.text) ?? 0;
        final valid = days >= 1 && duration >= 1;
        return AlertDialog(
          title: const Text('Nieuwe podcast'),
          content: SizedBox(width: 400, child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(
              controller: topicsCtrl,
              maxLines: 3,
              decoration: const InputDecoration(labelText: 'Onderwerpen (één per regel, optioneel)'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: daysCtrl,
              keyboardType: TextInputType.number,
              onChanged: (_) => setS(() {}),
              decoration: const InputDecoration(
                labelText: 'Periode (dagen)',
                helperText: 'Aantal dagen aan nieuws dat wordt meegenomen',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: durationCtrl,
              keyboardType: TextInputType.number,
              onChanged: (_) => setS(() {}),
              decoration: const InputDecoration(
                labelText: 'Duur (minuten)',
                helperText: 'Gewenste lengte van de podcast in minuten',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            DropdownButton<String>(
              value: provider,
              items: const [
                DropdownMenuItem(value: 'OPENAI', child: Text('OpenAI TTS')),
                DropdownMenuItem(value: 'ELEVENLABS', child: Text('ElevenLabs')),
              ],
              onChanged: (v) => setS(() => provider = v ?? 'OPENAI'),
            ),
          ])),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
            FilledButton(
              onPressed: valid ? () => Navigator.pop(ctx, true) : null,
              child: const Text('Maak'),
            ),
          ],
        );
      }),
    );
    if (ok != true) return;
    final days = int.tryParse(daysCtrl.text) ?? 0;
    final duration = int.tryParse(durationCtrl.text) ?? 0;
    if (days < 1 || duration < 1) return;
    final topics = topicsCtrl.text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
    await ref.read(podcastProvider.notifier).create(
          periodDays: days,
          durationMinutes: duration,
          ttsProvider: provider,
          customTopics: topics,
        );
  }
}
