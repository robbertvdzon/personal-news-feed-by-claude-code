import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/api_client.dart';
import '../models/models.dart';
import '../providers/data_providers.dart';

/// SF-762: aparte subpagina voor het beheren van de categorieën. Verplaatst
/// uit `settings_screen.dart` volgens hetzelfde patroon als de RSS-feeds
/// (`RssFeedsScreen`, SF-220/312).
///
/// SF-1851: het faalcontract is gelijkgetrokken met `_RssFeedsEditor` — elke
/// mutatie loopt via [_CategoriesScreenState._save], de lijst muteert pas ná
/// een geslaagde PUT en bij een fout verschijnt een rode snackbar.
class CategoriesScreen extends ConsumerStatefulWidget {
  const CategoriesScreen({super.key});

  @override
  ConsumerState<CategoriesScreen> createState() => _CategoriesScreenState();
}

class _CategoriesScreenState extends ConsumerState<CategoriesScreen> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
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
                onChanged: _busy
                    ? null
                    : (v) {
                        final next = list.map((x) => x.id == c.id ? x.copyWith(enabled: v) : x).toList();
                        _save(next);
                      },
                secondary: c.isSystem
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.edit),
                        onPressed: _busy ? null : () => _editCategory(c, list),
                      ),
              ),
            ListTile(
              leading: const Icon(Icons.add),
              title: const Text('Categorie toevoegen'),
              onTap: _busy ? null : () => _addCategory(list),
            ),
          ]),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Text('Fout: $e'),
        ),
      ]),
    );
  }

  /// SF-1851: identiek faalcontract als [_RssFeedsEditorState._save] — de
  /// lijst muteert pas nadat de PUT geslaagd is en tijdens het opslaan is de
  /// bediening uitgeschakeld. Er is geen invoerveld dat geleegd moet worden,
  /// dus ook geen `validateFailureMessage`-parameter.
  Future<void> _save(List<CategorySettings> next) async {
    setState(() => _busy = true);
    try {
      await ref.read(settingsProvider.notifier).save(next);
    } catch (e) {
      if (!mounted) return;
      // Backend stuurt bij een afwijzing HTTP 400 met een Nederlandse
      // foutmelding in de body — die tonen we direct.
      final msg = e is ApiException && e.statusCode == 400
          ? extractDutchMessage(e.body, emptyFallback: 'Kon categorieën niet opslaan')
          : 'Fout bij opslaan: $e';
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

  Future<void> _addCategory(List<CategorySettings> list) async {
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
    await _save(next);
  }

  Future<void> _editCategory(CategorySettings c, List<CategorySettings> list) async {
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
      await _save(next);
    } else if (action == 'delete') {
      final next = list.where((x) => x.id != c.id).toList();
      await _save(next);
    }
  }
}
