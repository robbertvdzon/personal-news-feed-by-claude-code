import 'dart:async';
// ignore_for_file: unused_import
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/api_client.dart';
import '../api/models.dart';
import 'auth_provider.dart';

/// Pollt elke 15s. Wordt door schermen geconsumeerd; herstart automatisch
/// als de auth-state verandert (re-login).

final homeStateProvider = StreamProvider<HomeState>((ref) async* {
  final api = ref.read(apiProvider);
  yield* _poll(() => api.state(), interval: const Duration(seconds: 15),
      ref: ref);
});

final storiesProvider = StreamProvider<List<StoryRow>>((ref) async* {
  final api = ref.read(apiProvider);
  yield* _poll(() => api.stories(), interval: const Duration(seconds: 30),
      ref: ref);
});

final storyDetailProvider = FutureProvider.family<StoryDetail, String>(
  (ref, key) => ref.read(apiProvider).storyDetail(key),
);

final storyHandoverProvider = FutureProvider.family<HandoverData, String>(
  (ref, key) => ref.read(apiProvider).storyHandover(key),
);

final runnerLogProvider = FutureProvider.family<Map<String, dynamic>, String>(
  (ref, jobName) => ref.read(apiProvider).runnerLog(jobName),
);

final activeJobProvider = StreamProvider.family<ActiveAgentJob?, String>(
  (ref, key) async* {
    final api = ref.read(apiProvider);
    yield* _poll(() => api.activeJob(key),
        interval: const Duration(seconds: 10), ref: ref);
  },
);

final poQuestionProvider = FutureProvider.family<PoQuestion?, String>(
  (ref, key) => ref.read(apiProvider).poQuestion(key),
);

Stream<T> _poll<T>(Future<T> Function() fn,
    {required Duration interval, Ref? ref}) async* {
  while (true) {
    try {
      yield await fn();
    } on ApiException catch (e) {
      // 401 = JWT verlopen of secret-rotated (pod-restart). Auto-logout
      // → UI gaat naar LoginScreen. Geen rethrow nodig.
      if (e.statusCode == 401) {
        if (ref != null) {
          // ignore: unawaited_futures
          ref.read(authProvider.notifier).logout();
        }
        return;
      }
      // Andere fouten: emit niet (UI houdt vorige value), wacht en retry.
    } catch (_) {
      // network: idem, vorige value behouden, wacht en retry.
    }
    await Future<void>.delayed(interval);
  }
}
