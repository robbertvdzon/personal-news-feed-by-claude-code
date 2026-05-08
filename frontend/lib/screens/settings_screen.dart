import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../models/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    final cats = ref.watch(settingsProvider);
    final feeds = ref.watch(rssFeedsProvider);
    final appearance = ref.watch(appearanceProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Instellingen')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text('Account', style: Theme.of(context).textTheme.titleLarge),
        ListTile(
          leading: const Icon(Icons.person),
          title: Text(auth.username ?? 'Onbekend'),
          trailing: TextButton.icon(
            icon: const Icon(Icons.logout),
            label: const Text('Uitloggen'),
            onPressed: () async {
              await ref.read(authProvider.notifier).logout();
              ref.invalidate(feedProvider);
              ref.invalidate(rssProvider);
              ref.invalidate(podcastProvider);
              ref.invalidate(requestProvider);
              ref.invalidate(settingsProvider);
              ref.invalidate(rssFeedsProvider);
            },
          ),
        ),
        const Divider(),
        Text('Weergave', style: Theme.of(context).textTheme.titleLarge),
        SwitchListTile(
          title: const Text('Grote tekst'),
          value: appearance.largeFont,
          onChanged: (v) => ref.read(appearanceProvider.notifier).setLarge(v),
        ),
        const Divider(),
        Text('Categorieën', style: Theme.of(context).textTheme.titleLarge),
        cats.when(
          data: (list) => Column(children: [
            for (final c in list)
              SwitchListTile(
                title: Text(c.name),
                subtitle: c.isSystem ? const Text('Systeem') : null,
                value: c.enabled,
                onChanged: (v) {
                  final next = list.map((x) => x.id == c.id ? x.copyWith(enabled: v) : x).toList();
                  ref.read(settingsProvider.notifier).save(next);
                },
                secondary: c.isSystem
                    ? null
                    : IconButton(icon: const Icon(Icons.edit), onPressed: () => _editCategory(context, ref, c, list)),
              ),
            ListTile(
              leading: const Icon(Icons.add),
              title: const Text('Categorie toevoegen'),
              onTap: () => _addCategory(context, ref, list),
            ),
          ]),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Text('Fout: $e'),
        ),
        const Divider(),
        Text('RSS-feeds', style: Theme.of(context).textTheme.titleLarge),
        feeds.when(
          data: (list) => _RssFeedsEditor(feeds: list),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Text('Fout: $e'),
        ),
        const Divider(),
        Text('Opruimen', style: Theme.of(context).textTheme.titleLarge),
        ListTile(
          leading: const Icon(Icons.cleaning_services),
          title: const Text('Artikelen opruimen'),
          onTap: () => _cleanup(context, ref),
        ),
      ]),
    );
  }

  Future<void> _addCategory(BuildContext context, WidgetRef ref, List<CategorySettings> list) async {
    final name = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Nieuwe categorie'),
        content: TextField(controller: name, decoration: const InputDecoration(labelText: 'Naam')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Opslaan')),
        ],
      ),
    );
    if (ok != true || name.text.trim().isEmpty) return;
    final id = name.text.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '_');
    final next = [...list, CategorySettings(id: id, name: name.text.trim())];
    await ref.read(settingsProvider.notifier).save(next);
  }

  Future<void> _editCategory(BuildContext context, WidgetRef ref, CategorySettings c, List<CategorySettings> list) async {
    final name = TextEditingController(text: c.name);
    final extra = TextEditingController(text: c.extraInstructions);
    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Categorie: ${c.name}'),
        content: SizedBox(
          width: 400,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(controller: name, decoration: const InputDecoration(labelText: 'Naam')),
            const SizedBox(height: 8),
            TextField(controller: extra, maxLines: 3, decoration: const InputDecoration(labelText: 'Extra instructies')),
          ]),
        ),
        actions: [
          if (!c.isSystem) TextButton(onPressed: () => Navigator.pop(ctx, 'delete'), child: const Text('Verwijderen', style: TextStyle(color: Colors.red))),
          TextButton(onPressed: () => Navigator.pop(ctx, 'cancel'), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, 'save'), child: const Text('Opslaan')),
        ],
      ),
    );
    if (action == 'save') {
      final next = list.map((x) => x.id == c.id
              ? x.copyWith(name: name.text.trim(), extraInstructions: extra.text)
              : x).toList();
      await ref.read(settingsProvider.notifier).save(next);
    } else if (action == 'delete') {
      final next = list.where((x) => x.id != c.id).toList();
      await ref.read(settingsProvider.notifier).save(next);
    }
  }

  Future<void> _cleanup(BuildContext context, WidgetRef ref) async {
    int days = 30;
    bool keepStarred = true;
    bool keepLiked = true;
    bool keepUnread = false;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setS) => AlertDialog(
        title: const Text('Artikelen opruimen'),
        content: SizedBox(width: 400, child: Column(mainAxisSize: MainAxisSize.min, children: [
          Row(children: [
            const Text('Aantal dagen:'),
            Expanded(child: Slider(value: days.toDouble(), min: 7, max: 90, divisions: 11, label: '$days', onChanged: (v) => setS(() => days = v.round()))),
            Text('$days'),
          ]),
          CheckboxListTile(value: keepStarred, onChanged: (v) => setS(() => keepStarred = v ?? true), title: const Text('Bewaar bewaard')),
          CheckboxListTile(value: keepLiked, onChanged: (v) => setS(() => keepLiked = v ?? true), title: const Text('Bewaar geliket')),
          CheckboxListTile(value: keepUnread, onChanged: (v) => setS(() => keepUnread = v ?? false), title: const Text('Bewaar ongelezen')),
        ])),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Opruimen')),
        ],
      )),
    );
    if (ok != true) return;
    final api = ref.read(apiProvider);
    final qs = '?olderThanDays=$days&keepStarred=$keepStarred&keepLiked=$keepLiked&keepUnread=$keepUnread';
    await api.delete('/api/rss/cleanup$qs');
    await api.delete('/api/feed/cleanup$qs');
    ref.invalidate(rssProvider);
    ref.invalidate(feedProvider);
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
