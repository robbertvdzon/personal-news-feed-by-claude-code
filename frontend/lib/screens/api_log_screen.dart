import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../api/api_client.dart';
import '../api/api_log.dart';

/// Debug-scherm met een lijst van de laatste API-calls. Bedoeld om
/// vanaf de telefoon te kunnen zien wat de app probeert (handig als
/// requests niet bij de backend aankomen).
///
/// Tap een entry voor de volledige URL, status, en eventuele error-body.
class ApiLogScreen extends StatelessWidget {
  const ApiLogScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('API-log'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy_all),
            tooltip: 'Kopieer alles',
            onPressed: () async {
              final all = ApiLog.instance.entries.map((e) => e.summary).join('\n');
              await Clipboard.setData(ClipboardData(text: all));
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Gekopieerd')),
                );
              }
            },
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Log wissen',
            onPressed: () => ApiLog.instance.clear(),
          ),
        ],
      ),
      body: Column(
        children: [
          // Always-visible header met de gekozen base URL — meteen duidelijk
          // of de APK met de juiste API_BASE_URL is gebouwd.
          Container(
            width: double.infinity,
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('API_BASE_URL', style: Theme.of(context).textTheme.labelSmall),
                SelectableText(
                  ApiClient.baseUrl,
                  style: const TextStyle(fontFamily: 'monospace'),
                ),
              ],
            ),
          ),
          Expanded(
            child: ValueListenableBuilder<int>(
              valueListenable: ApiLog.instance.revision,
              builder: (context, _, __) {
                final entries = ApiLog.instance.entries;
                if (entries.isEmpty) {
                  return const Center(
                    child: Padding(
                      padding: EdgeInsets.all(24),
                      child: Text(
                        'Nog geen calls gelogd. Trigger een actie in de app '
                        'en kom terug.',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  );
                }
                return ListView.separated(
                  itemCount: entries.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (context, i) => _LogTile(entry: entries[i]),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _LogTile extends StatelessWidget {
  final ApiLogEntry entry;
  const _LogTile({required this.entry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final statusText = entry.statusCode?.toString() ?? 'ERR';
    final color = entry.isError ? cs.error : Colors.green.shade700;
    final hh = entry.time.hour.toString().padLeft(2, '0');
    final mm = entry.time.minute.toString().padLeft(2, '0');
    final ss = entry.time.second.toString().padLeft(2, '0');
    return ListTile(
      dense: true,
      leading: Container(
        width: 50,
        alignment: Alignment.center,
        padding: const EdgeInsets.symmetric(vertical: 4),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          statusText,
          style: TextStyle(color: color, fontWeight: FontWeight.bold),
        ),
      ),
      title: Text(
        '${entry.method} ${entry.url}',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 13, fontFamily: 'monospace'),
      ),
      subtitle: Text('$hh:$mm:$ss  ·  ${entry.durationMs}ms'),
      onTap: () => _showDetail(context, entry),
    );
  }

  void _showDetail(BuildContext context, ApiLogEntry e) {
    showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text('${e.method}  ${e.statusCode ?? 'ERR'}'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              SelectableText(e.url, style: const TextStyle(fontFamily: 'monospace')),
              const SizedBox(height: 8),
              Text(
                '${e.time.toIso8601String()}  ·  ${e.durationMs}ms',
                style: Theme.of(context).textTheme.labelSmall,
              ),
              if (e.error != null) ...[
                const SizedBox(height: 12),
                const Text('Error / body:', style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 4),
                SelectableText(
                  e.error!,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(
                text: '${e.method} ${e.statusCode ?? "ERR"} ${e.url}\n${e.error ?? ""}',
              ));
              if (context.mounted) Navigator.pop(context);
            },
            child: const Text('Kopieer'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Sluiten'),
          ),
        ],
      ),
    );
  }
}
