import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/api_client.dart';
import '../api/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';
import 'story_detail_screen.dart';

/// KAN-61: "Claude"-tab. Twee secties:
///   * Factory agents — read-only weergave van actief draaiende
///     claude-runner Jobs (refiner/developer/reviewer/tester).
///   * Interactieve sessies — handmatig gestarte long-running pods met
///     Claude Code CLI in /remote-modus. Start/stop vanaf hier; de
///     sessie verschijnt vervolgens automatisch in de Anthropic
///     Claude-app op de telefoon van de PO.
///
/// Beide lijsten ververst de provider elke 10s; de live-tikkende
/// duur-labels worden lokaal elke seconde herrekend (geen extra
/// netwerk-poll voor de tikker).
class ClaudeTab extends ConsumerWidget {
  const ClaudeTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final factoryAsync = ref.watch(claudeFactoryAgentsProvider);
    final sessionsAsync = ref.watch(claudeSessionsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
          child: Row(
            children: [
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Claude',
                        style:
                            TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                    Padding(
                      padding: EdgeInsets.only(top: 2),
                      child: Text(
                          'Lopende factory-agents + interactieve sessies in de cluster',
                          style: TextStyle(fontSize: 13)),
                    ),
                  ],
                ),
              ),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: () {
                  ref.invalidate(claudeFactoryAgentsProvider);
                  ref.invalidate(claudeSessionsProvider);
                },
              ),
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () async {
              ref.invalidate(claudeFactoryAgentsProvider);
              ref.invalidate(claudeSessionsProvider);
            },
            child: ListView(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 80),
              children: [
                _SectionHeader(
                  title: 'Factory agents',
                  subtitle: factoryAsync.maybeWhen(
                    data: (a) => a.isEmpty
                        ? 'Geen factory-agents actief'
                        : '${a.length} actief',
                    orElse: () => null,
                  ),
                ),
                const SizedBox(height: 8),
                factoryAsync.when(
                  loading: () => const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                  error: (e, _) => _ErrorCard(error: e.toString()),
                  data: (agents) => agents.isEmpty
                      ? const _EmptyCard(
                          icon: Icons.precision_manufacturing_outlined,
                          text: 'Geen factory-agents actief',
                        )
                      : Column(
                          children: [
                            for (final a in agents)
                              Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: _FactoryAgentCard(agent: a),
                              ),
                          ],
                        ),
                ),
                const SizedBox(height: 24),
                _SectionHeader(
                  title: 'Interactieve sessies',
                  subtitle: sessionsAsync.maybeWhen(
                    data: (s) => s.sessions.isEmpty
                        ? 'Geen actieve sessies (max ${s.cap})'
                        : '${s.sessions.length} / ${s.cap} actief',
                    orElse: () => null,
                  ),
                  trailing: sessionsAsync.maybeWhen(
                    data: (s) => FilledButton.icon(
                      onPressed: s.sessions.length >= s.cap
                          ? null
                          : () => _showCreateDialog(context, ref, s.cap),
                      icon: const Icon(Icons.add, size: 18),
                      label: const Text('Nieuwe sessie'),
                    ),
                    orElse: () => null,
                  ),
                ),
                const SizedBox(height: 8),
                sessionsAsync.when(
                  loading: () => const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                  error: (e, _) => _ErrorCard(error: e.toString()),
                  data: (list) => list.sessions.isEmpty
                      ? const _EmptyCard(
                          icon: Icons.terminal_outlined,
                          text:
                              'Geen interactieve sessies. Klik "Nieuwe sessie" om er een te starten.',
                        )
                      : Column(
                          children: [
                            for (final s in list.sessions)
                              Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: _SessionCard(session: s, ref: ref),
                              ),
                          ],
                        ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _showCreateDialog(
      BuildContext context, WidgetRef ref, int cap) async {
    await showDialog<void>(
      context: context,
      // Dialog sluit niet vanzelf bij fout — alleen bij succes of cancel.
      barrierDismissible: false,
      builder: (ctx) => _CreateSessionDialog(cap: cap),
    );
    // Verfris meteen — geen 10s wachten op de poll.
    ref.invalidate(claudeSessionsProvider);
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  final String? subtitle;
  final Widget? trailing;
  const _SectionHeader({required this.title, this.subtitle, this.trailing});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title,
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w700)),
              if (subtitle != null && subtitle!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(subtitle!,
                      style: TextStyle(
                          fontSize: 12, color: scheme.onSurfaceVariant)),
                ),
            ],
          ),
        ),
        if (trailing != null) trailing!,
      ],
    );
  }
}

class _EmptyCard extends StatelessWidget {
  final IconData icon;
  final String text;
  const _EmptyCard({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Row(
          children: [
            Icon(icon, color: scheme.onSurfaceVariant, size: 22),
            const SizedBox(width: 12),
            Expanded(
                child: Text(text,
                    style: TextStyle(
                        fontSize: 13, color: scheme.onSurfaceVariant))),
          ],
        ),
      ),
    );
  }
}

class _ErrorCard extends StatelessWidget {
  final String error;
  const _ErrorCard({required this.error});
  @override
  Widget build(BuildContext context) {
    return Card(
      color: const Color(0xFFFDECEC),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Text('Fout: $error',
            style: const TextStyle(fontSize: 12, color: Color(0xFF991B1B))),
      ),
    );
  }
}

class _FactoryAgentCard extends StatelessWidget {
  final ClaudeFactoryAgent agent;
  const _FactoryAgentCard({required this.agent});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final canOpen = RegExp(r'^[A-Z][A-Z0-9]+-[0-9]+$').hasMatch(agent.storyKey);
    return Card(
      child: InkWell(
        onTap: canOpen
            ? () => Navigator.of(context).push(MaterialPageRoute(
                  builder: (_) => StoryDetailScreen(storyKey: agent.storyKey),
                ))
            : null,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          child: Row(
            children: [
              _RoleIcon(role: agent.role),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          agent.storyKey.isEmpty ? '(onbekende story)' : agent.storyKey,
                          style: const TextStyle(
                              fontSize: 14, fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(width: 8),
                        _StatePill(state: agent.state),
                      ],
                    ),
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        '${agent.role.isEmpty ? "agent" : agent.role} · ${_friendlyDuration(agent.startedAt)}',
                        style: TextStyle(
                            fontSize: 12, color: scheme.onSurfaceVariant),
                      ),
                    ),
                  ],
                ),
              ),
              if (canOpen)
                Icon(Icons.chevron_right, color: scheme.onSurfaceVariant, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

class _SessionCard extends StatefulWidget {
  final ClaudeSession session;
  final WidgetRef ref;
  const _SessionCard({required this.session, required this.ref});

  @override
  State<_SessionCard> createState() => _SessionCardState();
}

class _SessionCardState extends State<_SessionCard> {
  bool _stopping = false;
  String? _error;

  Future<void> _stop() async {
    setState(() {
      _stopping = true;
      _error = null;
    });
    try {
      final api = widget.ref.read(apiProvider);
      await api.deleteClaudeSession(widget.session.name);
      // Direct verfrissen — niet wachten op de 10s-poll.
      widget.ref.invalidate(claudeSessionsProvider);
    } on ApiException catch (e) {
      setState(() => _error = _humanizeApiError(e));
    } finally {
      if (mounted) setState(() => _stopping = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = widget.session;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(Icons.terminal, color: scheme.primary, size: 22),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text(s.name.isEmpty ? '(zonder naam)' : s.name,
                              style: const TextStyle(
                                  fontSize: 14, fontWeight: FontWeight.w700)),
                          const SizedBox(width: 8),
                          _StatePill(state: s.state),
                        ],
                      ),
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(
                          'gestart ${_friendlyDuration(s.startedAt)}',
                          style: TextStyle(
                              fontSize: 12, color: scheme.onSurfaceVariant),
                        ),
                      ),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: 'Stop sessie',
                  onPressed: _stopping ? null : _stop,
                  icon: _stopping
                      ? const SizedBox(
                          width: 18, height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.stop_circle_outlined,
                          color: Color(0xFF991B1B)),
                ),
              ],
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!,
                    style: const TextStyle(
                        fontSize: 12, color: Color(0xFF991B1B))),
              ),
          ],
        ),
      ),
    );
  }
}

class _RoleIcon extends StatelessWidget {
  final String role;
  const _RoleIcon({required this.role});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    IconData icon;
    switch (role) {
      case 'refiner': icon = Icons.edit_note; break;
      case 'developer': icon = Icons.code; break;
      case 'reviewer': icon = Icons.rate_review_outlined; break;
      case 'tester': icon = Icons.science_outlined; break;
      default: icon = Icons.smart_toy_outlined;
    }
    return Container(
      width: 32, height: 32,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: scheme.secondaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Icon(icon, size: 18, color: scheme.onSecondaryContainer),
    );
  }
}

class _StatePill extends StatelessWidget {
  final String state;
  const _StatePill({required this.state});

  (Color, Color, String) _colors() {
    switch (state) {
      case 'running':    return (const Color(0xFFE6F7EC), const Color(0xFF1E6B3E), 'running');
      case 'completing': return (const Color(0xFFE5F0FE), const Color(0xFF1E40AF), 'completing');
      case 'failed':     return (const Color(0xFFFDECEC), const Color(0xFF991B1B), 'failed');
      case 'finished':   return (const Color(0xFFF1F3F8), const Color(0xFF374151), 'finished');
      case 'stopped':    return (const Color(0xFFF1F3F8), const Color(0xFF374151), 'stopped');
      default:           return (const Color(0xFFF1F3F8), const Color(0xFF374151), state);
    }
  }

  @override
  Widget build(BuildContext context) {
    final (bg, fg, label) = _colors();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
      child: Text(label,
          style: TextStyle(
              fontSize: 11, fontWeight: FontWeight.w600, color: fg)),
    );
  }
}

class _CreateSessionDialog extends ConsumerStatefulWidget {
  final int cap;
  const _CreateSessionDialog({required this.cap});
  @override
  ConsumerState<_CreateSessionDialog> createState() =>
      _CreateSessionDialogState();
}

class _CreateSessionDialogState extends ConsumerState<_CreateSessionDialog> {
  final _ctrl = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final name = _ctrl.text.trim().toLowerCase();
    if (name.isEmpty) {
      setState(() => _error = 'Naam is verplicht.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiProvider).createClaudeSession(name);
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (e) {
      setState(() => _error = _humanizeApiError(e));
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Nieuwe interactieve sessie'),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
                'Geef de sessie een unieke naam. Je kunt vanaf je telefoon (Claude-app) hierna prompts sturen — de pod heeft admin-RBAC op het cluster.',
                style: TextStyle(fontSize: 12)),
            const SizedBox(height: 12),
            TextField(
              controller: _ctrl,
              autofocus: true,
              enabled: !_busy,
              decoration: const InputDecoration(
                labelText: 'Sessienaam',
                helperText:
                    'Kleine letters, cijfers en streepjes. Start met een letter. Max 22 tekens.',
                border: OutlineInputBorder(),
              ),
              onSubmitted: (_) => _submit(),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!,
                    style: const TextStyle(
                        fontSize: 12, color: Color(0xFF991B1B))),
              ),
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                  'Max ${widget.cap} sessies tegelijk; de cap is systeem-breed.',
                  style: TextStyle(
                      fontSize: 11,
                      color: Theme.of(context).colorScheme.onSurfaceVariant)),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Annuleren'),
        ),
        FilledButton(
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(
                  width: 18, height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Starten'),
        ),
      ],
    );
  }
}

/// Vertaal een ApiException naar een leesbare regel. De backend levert
/// {error: "..."} bij 400/409/502; alleen die tekst willen we tonen,
/// niet de hele response-body met JSON-escapes.
String _humanizeApiError(ApiException e) {
  try {
    final body = e.body;
    final start = body.indexOf('"error"');
    if (start >= 0) {
      // Zeer simpele extract — geen jsonDecode-roundtrip nodig.
      final colon = body.indexOf(':', start);
      final q1 = body.indexOf('"', colon);
      final q2 = body.indexOf('"', q1 + 1);
      if (q1 >= 0 && q2 > q1) {
        return body.substring(q1 + 1, q2);
      }
    }
  } catch (_) {}
  return e.toString();
}

/// "gestart 12s geleden" / "gestart 4m geleden" / "—". Pakt de meest
/// recente verstreken tijd zodat de kaart-tekst leesbaar blijft.
String _friendlyDuration(String? iso) {
  if (iso == null || iso.isEmpty) return 'onbekend';
  DateTime? ts;
  try {
    ts = DateTime.parse(iso).toLocal();
  } catch (_) {
    return iso;
  }
  final delta = DateTime.now().difference(ts);
  if (delta.inSeconds < 60) return '${delta.inSeconds}s geleden';
  if (delta.inMinutes < 60) return '${delta.inMinutes}m geleden';
  if (delta.inHours < 24) return '${delta.inHours}u geleden';
  return '${delta.inDays}d geleden';
}
