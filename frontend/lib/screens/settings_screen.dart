import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/api_client.dart';
import '../api/version_client.dart';
import '../models/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';
import '../providers/version_provider.dart';
import '../widgets/app_logo.dart';
import 'api_log_screen.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    final cats = ref.watch(settingsProvider);
    final feeds = ref.watch(rssFeedsProvider);
    final appearance = ref.watch(appearanceProvider);
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return Scaffold(
      appBar: AppBar(
        leading: const AppLogo(),
        title: const Text('Instellingen'),
      ),
      // Bottom-padding zodat de "Artikelen opruimen"-knop onderin niet
      // onder de Android nav-bar / iOS home-indicator verdwijnt.
      body: ListView(padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset), children: [
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
        ListTile(
          leading: const Icon(Icons.lock_outline),
          title: const Text('Wachtwoord wijzigen'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => _changePassword(context, ref),
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
        const Divider(),
        Text('Debug', style: Theme.of(context).textTheme.titleLarge),
        ListTile(
          leading: const Icon(Icons.bug_report_outlined),
          title: const Text('API-log'),
          subtitle: const Text('Laatste calls + status (voor debugging)'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => const ApiLogScreen()),
          ),
        ),
        const Divider(),
        Text('Over deze app', style: Theme.of(context).textTheme.titleLarge),
        const _VersionBlock(),
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
    final daysCtrl = TextEditingController(text: '30');
    bool keepStarred = true;
    bool keepLiked = true;
    bool keepUnread = false;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setS) {
        final days = int.tryParse(daysCtrl.text) ?? -1;
        final wipeAll = days == 0;
        return AlertDialog(
          title: const Text('Artikelen opruimen'),
          content: SizedBox(
            width: 400,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Verwijdert zowel RSS-items als gecureerde feed-items.'),
              const SizedBox(height: 12),
              TextField(
                controller: daysCtrl,
                keyboardType: TextInputType.number,
                onChanged: (_) => setS(() {}),
                decoration: InputDecoration(
                  labelText: 'Ouder dan (dagen)',
                  helperText: wipeAll
                      ? '0 dagen = alles wissen, ook bewaard/geliket/ongelezen'
                      : 'Items ouder dan dit aantal dagen worden gewist',
                  helperStyle: wipeAll
                      ? TextStyle(color: Theme.of(ctx).colorScheme.error, fontWeight: FontWeight.bold)
                      : null,
                  border: const OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 8),
              CheckboxListTile(
                value: wipeAll ? false : keepStarred,
                onChanged: wipeAll ? null : (v) => setS(() => keepStarred = v ?? true),
                title: const Text('Bewaar bewaard (sterren)'),
                dense: true,
              ),
              CheckboxListTile(
                value: wipeAll ? false : keepLiked,
                onChanged: wipeAll ? null : (v) => setS(() => keepLiked = v ?? true),
                title: const Text('Bewaar geliket'),
                dense: true,
              ),
              CheckboxListTile(
                value: wipeAll ? false : keepUnread,
                onChanged: wipeAll ? null : (v) => setS(() => keepUnread = v ?? false),
                title: const Text('Bewaar ongelezen'),
                dense: true,
              ),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
            FilledButton(
              onPressed: days < 0 ? null : () => Navigator.pop(ctx, true),
              style: wipeAll
                  ? FilledButton.styleFrom(backgroundColor: Theme.of(ctx).colorScheme.error)
                  : null,
              child: Text(wipeAll ? 'Alles wissen' : 'Opruimen'),
            ),
          ],
        );
      }),
    );
    if (ok != true) return;
    final days = int.tryParse(daysCtrl.text) ?? -1;
    if (days < 0) return;
    // 0 dagen = alles wissen: forceer alle "keep"-vlaggen op false zodat de
    // backend ook bewaard/geliket/ongelezen items meeneemt.
    final wipeAll = days == 0;
    final ks = wipeAll ? false : keepStarred;
    final kl = wipeAll ? false : keepLiked;
    final ku = wipeAll ? false : keepUnread;
    final api = ref.read(apiProvider);
    final qs = '?olderThanDays=$days&keepStarred=$ks&keepLiked=$kl&keepUnread=$ku';
    await api.delete('/api/rss/cleanup$qs');
    await api.delete('/api/feed/cleanup$qs');
    ref.invalidate(rssProvider);
    ref.invalidate(feedProvider);
  }

  Future<void> _changePassword(BuildContext context, WidgetRef ref) async {
    final currentCtrl = TextEditingController();
    final newCtrl = TextEditingController();
    final confirmCtrl = TextEditingController();
    String? error;
    bool busy = false;

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setS) {
        Future<void> submit() async {
          final cur = currentCtrl.text;
          final nw = newCtrl.text;
          final cf = confirmCtrl.text;
          if (cur.isEmpty || nw.isEmpty) {
            setS(() => error = 'Vul beide velden in');
            return;
          }
          if (nw.length < 4) {
            setS(() => error = 'Nieuw wachtwoord moet minimaal 4 tekens zijn');
            return;
          }
          if (nw != cf) {
            setS(() => error = 'Nieuwe wachtwoorden zijn niet gelijk');
            return;
          }
          setS(() {
            error = null;
            busy = true;
          });
          try {
            await ref.read(apiProvider).put('/api/account/password', {
              'currentPassword': cur,
              'newPassword': nw,
            });
            if (ctx.mounted) Navigator.pop(ctx, true);
          } on ApiException catch (e) {
            // 401 = huidig wachtwoord klopt niet; 400 = validatie van backend
            setS(() {
              busy = false;
              error = e.statusCode == 401
                  ? 'Huidig wachtwoord klopt niet'
                  : 'Fout: ${e.statusCode}';
            });
          } catch (e) {
            setS(() {
              busy = false;
              error = 'Fout: $e';
            });
          }
        }

        return AlertDialog(
          title: const Text('Wachtwoord wijzigen'),
          content: SizedBox(
            width: 400,
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(
                controller: currentCtrl,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Huidig wachtwoord'),
                autofocus: true,
              ),
              const SizedBox(height: 8),
              TextField(
                controller: newCtrl,
                obscureText: true,
                decoration: const InputDecoration(
                  labelText: 'Nieuw wachtwoord',
                  helperText: 'Min. 4 tekens',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: confirmCtrl,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Nieuw wachtwoord bevestigen'),
                onSubmitted: (_) => submit(),
              ),
              if (error != null) ...[
                const SizedBox(height: 12),
                Text(error!, style: TextStyle(color: Theme.of(ctx).colorScheme.error)),
              ],
            ]),
          ),
          actions: [
            TextButton(
              onPressed: busy ? null : () => Navigator.pop(ctx, false),
              child: const Text('Annuleren'),
            ),
            FilledButton(
              onPressed: busy ? null : submit,
              child: busy
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Wijzigen'),
            ),
          ],
        );
      }),
    );
    if (ok == true && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Wachtwoord gewijzigd')),
      );
    }
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

/// Toont de actieve frontend- en backend-versie zoals beschreven in
/// frontend-spec §9: buildnummer (git short SHA) en build-timestamp in
/// lokale tijd. Frontend-info komt uit de bundel, backend-info uit het
/// `versionProvider` (gevuld door `/api/version` of het WS-bericht
/// `serverVersion`). Bij ontbrekende backend-info tonen we `onbekend`.
class _VersionBlock extends ConsumerWidget {
  const _VersionBlock();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final v = ref.watch(versionProvider);
    return Column(children: [
      _VersionTile(label: 'Frontend', info: v.frontend),
      _VersionTile(label: 'Backend', info: v.backend),
    ]);
  }
}

class _VersionTile extends StatelessWidget {
  final String label;
  final VersionInfo? info;
  const _VersionTile({required this.label, required this.info});

  @override
  Widget build(BuildContext context) {
    final i = info;
    final sub = (i == null) ? 'onbekend' : _formatVersion(i);
    return ListTile(
      dense: true,
      leading: const Icon(Icons.info_outline),
      title: Text(label),
      subtitle: Text(sub, style: const TextStyle(fontFamily: 'monospace')),
    );
  }

  static String _formatVersion(VersionInfo v) {
    final sha = v.sha;
    final t = _formatBuildTime(v.buildTime);
    return '$sha · $t';
  }

  static const _months = [
    'januari', 'februari', 'maart', 'april', 'mei', 'juni',
    'juli', 'augustus', 'september', 'oktober', 'november', 'december',
  ];

  static String _formatBuildTime(String iso) {
    if (iso.isEmpty || iso == 'unknown') return 'onbekend';
    final parsed = DateTime.tryParse(iso);
    if (parsed == null) return iso;
    // ISO is UTC; toon in lokale tijd zoals de spec voorschrijft.
    final local = parsed.toLocal();
    final hh = local.hour.toString().padLeft(2, '0');
    final mm = local.minute.toString().padLeft(2, '0');
    return '${local.day} ${_months[local.month - 1]} ${local.year} $hh:$mm';
  }
}
