import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';

class QueueScreen extends ConsumerWidget {
  const QueueScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reqs = ref.watch(requestProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Queue'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: () => ref.invalidate(requestProvider)),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.add),
        label: const Text('Nieuw verzoek'),
        onPressed: () => _showNew(context, ref),
      ),
      body: reqs.when(
        data: (items) => RefreshIndicator(
          onRefresh: () async => ref.invalidate(requestProvider),
          child: items.isEmpty
              ? const Center(child: Text('Geen verzoeken'))
              : ListView.builder(
                  itemCount: items.length,
                  itemBuilder: (ctx, i) {
                    final r = items[i];
                    final fixed = r.id.startsWith('daily-update-') || r.id.startsWith('daily-summary-');
                    return Dismissible(
                      key: Key('req_${r.id}'),
                      direction: fixed ? DismissDirection.none : DismissDirection.endToStart,
                      onDismissed: (_) => ref.read(requestProvider.notifier).delete(r.id),
                      background: Container(color: Colors.red, alignment: Alignment.centerRight, padding: const EdgeInsets.only(right: 16), child: const Icon(Icons.delete, color: Colors.white)),
                      child: Card(
                        child: ListTile(
                          title: Text(r.subject),
                          subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text('Status: ${r.status}'),
                            if (r.newItemCount > 0) Text('Items: ${r.newItemCount}, kosten: \$${r.costUsd.toStringAsFixed(4)}'),
                            if (r.durationSeconds > 0) Text('Duur: ${r.durationSeconds}s'),
                          ]),
                          onTap: () => _detail(context, r),
                          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                            if (r.status == 'PENDING' || r.status == 'PROCESSING')
                              IconButton(
                                icon: const Icon(Icons.cancel),
                                onPressed: () => ref.read(requestProvider.notifier).cancel(r.id),
                              ),
                            if (r.status == 'FAILED' || r.status == 'CANCELLED' || r.status == 'DONE')
                              IconButton(
                                icon: const Icon(Icons.refresh),
                                onPressed: () => ref.read(requestProvider.notifier).rerun(r.id),
                              ),
                          ]),
                        ),
                      ),
                    );
                  },
                ),
        ),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
      ),
    );
  }

  Future<void> _showNew(BuildContext context, WidgetRef ref) async {
    final subjectCtrl = TextEditingController();
    final extraCtrl = TextEditingController();
    int days = 3;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setS) => AlertDialog(
        title: const Text('Nieuw verzoek'),
        content: SizedBox(
          width: 400,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(controller: subjectCtrl, decoration: const InputDecoration(labelText: 'Onderwerp')),
            const SizedBox(height: 8),
            TextField(controller: extraCtrl, decoration: const InputDecoration(labelText: 'Extra instructies (optioneel)')),
            const SizedBox(height: 8),
            Wrap(spacing: 8, children: [
              for (final d in [(1, 'Vandaag'), (3, '3 dagen'), (7, '1 week'), (30, '1 maand')])
                ChoiceChip(label: Text(d.$2), selected: days == d.$1, onSelected: (_) => setS(() => days = d.$1)),
            ]),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Maak')),
        ],
      )),
    );
    if (ok != true || subjectCtrl.text.trim().isEmpty) return;
    await ref.read(requestProvider.notifier).create(
          subject: subjectCtrl.text.trim(),
          maxAgeDays: days,
        );
  }

  void _detail(BuildContext context, NewsRequest r) {
    showDialog(context: context, builder: (ctx) => AlertDialog(
      title: Text(r.subject),
      content: SingleChildScrollView(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Status: ${r.status}'),
          Text('Items: ${r.newItemCount}'),
          Text('Kosten: \$${r.costUsd.toStringAsFixed(4)}'),
          Text('Duur: ${r.durationSeconds}s'),
          if (r.categoryResults.isNotEmpty) ...[
            const Divider(),
            const Text('Per categorie:'),
            for (final c in r.categoryResults)
              Text('${c['categoryName']}: ${c['articleCount']} items'),
          ]
        ]),
      ),
      actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Sluiten'))],
    ));
  }
}
