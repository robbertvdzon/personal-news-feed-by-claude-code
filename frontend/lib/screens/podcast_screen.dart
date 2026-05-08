import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';
import 'podcast_detail_screen.dart';

class PodcastScreen extends ConsumerStatefulWidget {
  const PodcastScreen({super.key});

  @override
  ConsumerState<PodcastScreen> createState() => _PodcastScreenState();
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
      _pollTimer ??= Timer.periodic(const Duration(seconds: 4), (_) => ref.invalidate(podcastProvider));
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
                          leading: const Icon(Icons.podcasts),
                          title: Text(p.title.isEmpty ? 'DevTalk ${p.podcastNumber}' : p.title),
                          subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text('Status: ${p.status}'),
                            Text('Duur: ${p.durationMinutes}min · TTS: ${p.ttsProvider}'),
                            if (p.costUsd > 0) Text('Kosten: \$${p.costUsd.toStringAsFixed(4)}'),
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
    int days = 7;
    int duration = 15;
    String provider = 'OPENAI';
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setS) => AlertDialog(
        title: const Text('Nieuwe podcast'),
        content: SizedBox(width: 400, child: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(
            controller: topicsCtrl,
            maxLines: 3,
            decoration: const InputDecoration(labelText: 'Onderwerpen (één per regel, optioneel)'),
          ),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(child: Slider(value: days.toDouble(), min: 1, max: 30, divisions: 29, label: '$days dagen', onChanged: (v) => setS(() => days = v.round()))),
            Text('$days d'),
          ]),
          Row(children: [
            Expanded(child: Slider(value: duration.toDouble(), min: 5, max: 60, divisions: 11, label: '$duration min', onChanged: (v) => setS(() => duration = v.round()))),
            Text('$duration min'),
          ]),
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
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Maak')),
        ],
      )),
    );
    if (ok != true) return;
    final topics = topicsCtrl.text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
    await ref.read(podcastProvider.notifier).create(
          periodDays: days,
          durationMinutes: duration,
          ttsProvider: provider,
          customTopics: topics,
        );
  }
}
