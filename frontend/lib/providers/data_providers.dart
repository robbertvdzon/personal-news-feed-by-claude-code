import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../api/api_client.dart';
import '../api/ws_client.dart';
import '../models/models.dart';
import 'auth_provider.dart';

final feedProvider = AsyncNotifierProvider<FeedNotifier, List<FeedItem>>(FeedNotifier.new);

class FeedNotifier extends AsyncNotifier<List<FeedItem>> {
  ApiClient get _api => ref.read(apiProvider);

  @override
  Future<List<FeedItem>> build() async {
    final list = await _api.get('/api/feed') as List<dynamic>;
    return list.map((e) => FeedItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> reload() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() => build());
  }

  Future<void> setRead(String id, bool read) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(isRead: read) : it).toList());
    await _api.put('/api/feed/$id/${read ? "read" : "unread"}');
  }

  Future<void> toggleStar(String id) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(starred: !it.starred) : it).toList());
    await _api.put('/api/feed/$id/star');
  }

  Future<void> setFeedback(String id, bool? liked) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(liked: liked) : it).toList());
    await _api.put('/api/feed/$id/feedback', {'liked': liked});
  }

  Future<void> delete(String id) async {
    state = AsyncData(state.value!.where((it) => it.id != id).toList());
    await _api.delete('/api/feed/$id');
  }
}

final rssProvider = AsyncNotifierProvider<RssNotifier, List<RssItem>>(RssNotifier.new);

class RssNotifier extends AsyncNotifier<List<RssItem>> {
  ApiClient get _api => ref.read(apiProvider);

  @override
  Future<List<RssItem>> build() async {
    final list = await _api.get('/api/rss') as List<dynamic>;
    return list.map((e) => RssItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> reload() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() => build());
  }

  Future<void> refresh() async {
    await _api.post('/api/rss/refresh');
  }

  /// Re-run only the AI selection step against the items already stored.
  /// Cheap (single Claude call, no fetches/summaries), useful when prefs
  /// change or selection failed in a previous run.
  Future<void> reselect() async {
    await _api.post('/api/rss/reselect');
  }

  Future<void> setRead(String id, bool read) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(isRead: read) : it).toList());
    await _api.put('/api/rss/$id/${read ? "read" : "unread"}');
  }

  Future<void> toggleStar(String id) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(starred: !it.starred) : it).toList());
    await _api.put('/api/rss/$id/star');
  }

  Future<void> setFeedback(String id, bool? liked) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(liked: liked) : it).toList());
    await _api.put('/api/rss/$id/feedback', {'liked': liked});
  }

  Future<void> delete(String id) async {
    state = AsyncData(state.value!.where((it) => it.id != id).toList());
    await _api.delete('/api/rss/$id');
  }
}

final settingsProvider = AsyncNotifierProvider<SettingsNotifier, List<CategorySettings>>(SettingsNotifier.new);

class SettingsNotifier extends AsyncNotifier<List<CategorySettings>> {
  ApiClient get _api => ref.read(apiProvider);

  @override
  Future<List<CategorySettings>> build() async {
    final list = await _api.get('/api/settings') as List<dynamic>;
    return list.map((e) => CategorySettings.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> save(List<CategorySettings> categories) async {
    state = AsyncData(categories);
    final list = await _api.put('/api/settings', categories.map((c) => c.toJson()).toList()) as List<dynamic>;
    state = AsyncData(list.map((e) => CategorySettings.fromJson(e as Map<String, dynamic>)).toList());
  }
}

final rssFeedsProvider = AsyncNotifierProvider<RssFeedsNotifier, List<String>>(RssFeedsNotifier.new);

class RssFeedsNotifier extends AsyncNotifier<List<String>> {
  ApiClient get _api => ref.read(apiProvider);

  @override
  Future<List<String>> build() async {
    final r = await _api.get('/api/rss-feeds') as Map<String, dynamic>;
    return List<String>.from(r['feeds'] ?? []);
  }

  Future<void> save(List<String> feeds) async {
    state = AsyncData(feeds);
    await _api.put('/api/rss-feeds', {'feeds': feeds});
  }
}

final requestProvider = AsyncNotifierProvider<RequestNotifier, List<NewsRequest>>(RequestNotifier.new);

class RequestNotifier extends AsyncNotifier<List<NewsRequest>> {
  ApiClient get _api => ref.read(apiProvider);
  RequestsWebSocket? _ws;

  @override
  Future<List<NewsRequest>> build() async {
    _ws ??= RequestsWebSocket()..connect().listen((msg) => _apply(msg));
    ref.onDispose(() {
      _ws?.close();
      _ws = null;
    });
    final list = await _api.get('/api/requests') as List<dynamic>;
    return list.map((e) => NewsRequest.fromJson(e as Map<String, dynamic>)).toList();
  }

  void _apply(Map<String, dynamic> msg) {
    final updated = NewsRequest.fromJson(msg);
    final cur = state.value;
    if (cur == null) return;
    // Per spec (frontend-spec §7): the /ws/requests broadcast carries
    // updates for ALL users. Known IDs in our local list are safe to
    // patch in place (the list itself comes from JWT-scoped /api/requests
    // so it only contains our own items). Unknown IDs trigger a quiet
    // reload, which silently filters out other users' requests via the
    // JWT-scoped fetch.
    final idx = cur.indexWhere((r) => r.id == updated.id);
    if (idx >= 0) {
      final list = [...cur];
      list[idx] = updated;
      state = AsyncData(list);
    } else {
      _reloadFromServer();
    }
  }

  Future<void> _reloadFromServer() async {
    try {
      final list = await _api.get('/api/requests') as List<dynamic>;
      state = AsyncData(list
          .map((e) => NewsRequest.fromJson(e as Map<String, dynamic>))
          .toList());
    } catch (_) {
      // ignore — keep current state
    }
  }

  Future<void> create({required String subject, String? sourceItemId, String? sourceItemTitle, int maxAgeDays = 3}) async {
    final r = await _api.post('/api/requests', {
      'subject': subject,
      if (sourceItemId != null) 'sourceItemId': sourceItemId,
      if (sourceItemTitle != null) 'sourceItemTitle': sourceItemTitle,
      'maxAgeDays': maxAgeDays,
    }) as Map<String, dynamic>;
    _apply(r);
  }

  Future<void> delete(String id) async {
    state = AsyncData(state.value!.where((r) => r.id != id).toList());
    await _api.delete('/api/requests/$id');
  }

  Future<void> cancel(String id) async {
    state = AsyncData(state.value!.map((r) => r.id == id ? r.copyWith(status: 'CANCELLED') : r).toList());
    await _api.post('/api/requests/$id/cancel');
  }

  Future<void> rerun(String id) async {
    state = AsyncData(state.value!.map((r) => r.id == id ? r.copyWith(status: 'PENDING') : r).toList());
    await _api.post('/api/requests/$id/rerun');
  }
}

final activeRequestCountProvider = Provider<int>((ref) {
  final r = ref.watch(requestProvider).value ?? const [];
  return r.where((it) => it.status == 'PENDING' || it.status == 'PROCESSING').length;
});

final podcastProvider = AsyncNotifierProvider<PodcastNotifier, List<Podcast>>(PodcastNotifier.new);

class PodcastNotifier extends AsyncNotifier<List<Podcast>> {
  ApiClient get _api => ref.read(apiProvider);

  @override
  Future<List<Podcast>> build() async {
    final list = await _api.get('/api/podcasts') as List<dynamic>;
    return list.map((e) => Podcast.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> reload() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() => build());
  }

  /// Re-fetch the list silently — keeps current data visible during the
  /// in-flight request so polling doesn't flash a loading spinner over
  /// in-progress podcast cards.
  Future<void> poll() async {
    try {
      final list = await _api.get('/api/podcasts') as List<dynamic>;
      state = AsyncData(
          list.map((e) => Podcast.fromJson(e as Map<String, dynamic>)).toList());
    } catch (_) {
      // ignore — keep current state
    }
  }

  Future<Podcast> create({required int periodDays, required int durationMinutes, required String ttsProvider, List<String> customTopics = const []}) async {
    final r = await _api.post('/api/podcasts', {
      'periodDays': periodDays,
      'durationMinutes': durationMinutes,
      'ttsProvider': ttsProvider,
      'customTopics': customTopics,
    }) as Map<String, dynamic>;
    final podcast = Podcast.fromJson(r);
    state = AsyncData([podcast, ...?state.value]);
    return podcast;
  }

  Future<Podcast> getDetail(String id) async {
    final r = await _api.get('/api/podcasts/$id') as Map<String, dynamic>;
    return Podcast.fromJson(r);
  }

  Future<void> delete(String id) async {
    state = AsyncData(state.value!.where((p) => p.id != id).toList());
    await _api.delete('/api/podcasts/$id');
  }
}

class AppearanceState {
  final bool largeFont;
  const AppearanceState({this.largeFont = false});
}

class AppearanceNotifier extends StateNotifier<AppearanceState> {
  AppearanceNotifier() : super(const AppearanceState()) {
    _load();
  }
  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    state = AppearanceState(largeFont: prefs.getBool('largeFont') ?? false);
  }
  Future<void> setLarge(bool v) async {
    state = AppearanceState(largeFont: v);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('largeFont', v);
  }
}

final appearanceProvider = StateNotifierProvider<AppearanceNotifier, AppearanceState>(
  (ref) => AppearanceNotifier(),
);
