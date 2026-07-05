import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';

/// SF-762: aparte subpagina voor het beheren van de categorieën. Verplaatst
/// uit `settings_screen.dart` volgens hetzelfde patroon als de RSS-feeds
/// (`RssFeedsScreen`, SF-220/312); de lijst, dialogen en hun gedrag zijn
/// ongewijzigd — alleen de plaatsing verandert.
class CategoriesScreen extends ConsumerWidget {
  const CategoriesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cats = ref.watch(settingsProvider);
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Categorieën'),
      ),
      body: ListView(padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset), children: [
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
}
