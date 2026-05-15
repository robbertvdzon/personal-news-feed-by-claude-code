import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/data_providers.dart';

class RunnerLogScreen extends ConsumerWidget {
  final String jobName;
  const RunnerLogScreen({super.key, required this.jobName});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncLog = ref.watch(runnerLogProvider(jobName));
    return Scaffold(
      appBar: AppBar(
        title: Text(jobName, style: const TextStyle(fontSize: 14)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.invalidate(runnerLogProvider(jobName)),
          ),
          asyncLog.maybeWhen(
            data: (m) => IconButton(
              icon: const Icon(Icons.copy),
              tooltip: 'Copy',
              onPressed: () => Clipboard.setData(
                  ClipboardData(text: m['log']?.toString() ?? '')),
            ),
            orElse: () => const SizedBox.shrink(),
          ),
        ],
      ),
      body: asyncLog.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (m) {
          final log = (m['log'] as String?) ?? '';
          return SingleChildScrollView(
            padding: const EdgeInsets.all(12),
            child: SelectableText(
              log,
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                height: 1.4,
              ),
            ),
          );
        },
      ),
    );
  }
}
