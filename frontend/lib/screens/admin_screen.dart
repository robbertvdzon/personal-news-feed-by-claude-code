import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/api_client.dart';
import '../providers/auth_provider.dart';
import '../widgets/app_logo.dart';

/// Eenvoudig domeinmodel — klein en alleen voor het admin-scherm,
/// dus niet in models.dart om de rest van de app niet te belasten.
class AdminUser {
  final String id;
  final String username;
  final String role;
  AdminUser({required this.id, required this.username, required this.role});
  factory AdminUser.fromJson(Map<String, dynamic> j) => AdminUser(
        id: (j['id'] ?? '') as String,
        username: (j['username'] ?? '') as String,
        role: (j['role'] ?? 'user') as String,
      );
}

class AdminUsersNotifier extends AsyncNotifier<List<AdminUser>> {
  ApiClient get _api => ref.read(apiProvider);

  @override
  Future<List<AdminUser>> build() => _load();

  Future<List<AdminUser>> _load() async {
    final list = await _api.get('/api/admin/users') as List<dynamic>;
    return list
        .map((e) => AdminUser.fromJson(e as Map<String, dynamic>))
        .toList()
      ..sort((a, b) => a.username.toLowerCase().compareTo(b.username.toLowerCase()));
  }

  Future<void> reload() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(_load);
  }

  Future<void> setRole(String username, String role) async {
    await _api.put('/api/admin/users/$username/role', {'role': role});
    await reload();
  }

  Future<void> resetPassword(String username, String newPassword) async {
    await _api.put('/api/admin/users/$username/password', {'newPassword': newPassword});
  }

  Future<void> delete(String username) async {
    await _api.delete('/api/admin/users/$username');
    await reload();
  }
}

final adminUsersProvider =
    AsyncNotifierProvider<AdminUsersNotifier, List<AdminUser>>(AdminUsersNotifier.new);

class AdminScreen extends ConsumerWidget {
  const AdminScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final usersAsync = ref.watch(adminUsersProvider);
    final me = ref.watch(authProvider).username;
    return Scaffold(
      appBar: AppBar(
        leading: const AppLogo(),
        title: const Text('Admin'),
        actions: [
          IconButton(
            tooltip: 'Lijst herladen',
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.read(adminUsersProvider.notifier).reload(),
          ),
        ],
      ),
      body: usersAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (users) => users.isEmpty
            ? const Center(child: Text('Geen gebruikers'))
            : ListView.separated(
                itemCount: users.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (ctx, i) {
                  final u = users[i];
                  final isSelf = u.username == me;
                  return ListTile(
                    leading: CircleAvatar(
                      backgroundColor: u.role == 'admin'
                          ? Theme.of(context).colorScheme.primary
                          : Theme.of(context).colorScheme.surfaceContainerHighest,
                      foregroundColor: u.role == 'admin'
                          ? Theme.of(context).colorScheme.onPrimary
                          : Theme.of(context).colorScheme.onSurface,
                      child: Icon(
                        u.role == 'admin' ? Icons.admin_panel_settings : Icons.person,
                      ),
                    ),
                    title: Row(children: [
                      Flexible(child: Text(u.username, overflow: TextOverflow.ellipsis)),
                      if (isSelf) ...[
                        const SizedBox(width: 8),
                        const Chip(
                          label: Text('jij', style: TextStyle(fontSize: 11)),
                          visualDensity: VisualDensity.compact,
                          padding: EdgeInsets.zero,
                        ),
                      ],
                    ]),
                    subtitle: Text('Rol: ${u.role}'),
                    trailing: PopupMenuButton<String>(
                      onSelected: (action) => _handleAction(context, ref, u, action, isSelf),
                      itemBuilder: (ctx) => [
                        const PopupMenuItem(value: 'reset', child: Text('Wachtwoord resetten')),
                        if (u.role == 'user')
                          const PopupMenuItem(value: 'make_admin', child: Text('Maak admin')),
                        if (u.role == 'admin' && !isSelf)
                          const PopupMenuItem(value: 'make_user', child: Text('Maak gewone user')),
                        if (!isSelf)
                          const PopupMenuItem(
                              value: 'delete',
                              child: Text('Verwijderen', style: TextStyle(color: Colors.red))),
                      ],
                    ),
                  );
                },
              ),
      ),
    );
  }

  Future<void> _handleAction(
    BuildContext context,
    WidgetRef ref,
    AdminUser u,
    String action,
    bool isSelf,
  ) async {
    final notifier = ref.read(adminUsersProvider.notifier);
    try {
      switch (action) {
        case 'reset':
          final pw = await _promptPassword(context, u.username);
          if (pw == null || pw.length < 4) return;
          await notifier.resetPassword(u.username, pw);
          if (context.mounted) {
            _snack(context, 'Wachtwoord gereset voor ${u.username}');
          }
          break;
        case 'make_admin':
          await notifier.setRole(u.username, 'admin');
          if (context.mounted) _snack(context, '${u.username} is nu admin');
          break;
        case 'make_user':
          final ok = await _confirm(context,
              'Demote ${u.username} tot gewone user?');
          if (ok != true) return;
          await notifier.setRole(u.username, 'user');
          if (context.mounted) _snack(context, '${u.username} is nu gewone user');
          break;
        case 'delete':
          final ok = await _confirm(context,
              'Verwijder gebruiker ${u.username}? Dit wist ook al hun feed-, RSS- en podcast-data.');
          if (ok != true) return;
          await notifier.delete(u.username);
          if (context.mounted) _snack(context, '${u.username} verwijderd');
          break;
      }
    } on ApiException catch (e) {
      if (context.mounted) _snack(context, 'Fout: ${e.statusCode} ${e.body}');
    } catch (e) {
      if (context.mounted) _snack(context, 'Fout: $e');
    }
  }

  Future<String?> _promptPassword(BuildContext context, String username) async {
    final ctrl = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Nieuw wachtwoord voor $username'),
        content: TextField(
          controller: ctrl,
          obscureText: true,
          decoration: const InputDecoration(labelText: 'Min. 4 tekens'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Annuleren')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, ctrl.text),
            child: const Text('Resetten'),
          ),
        ],
      ),
    );
  }

  Future<bool?> _confirm(BuildContext context, String message) {
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Bevestig'),
        content: Text(message),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Doorgaan')),
        ],
      ),
    );
  }

  void _snack(BuildContext context, String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}
