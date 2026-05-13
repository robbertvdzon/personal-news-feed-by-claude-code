import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../api/api_client.dart';
import '../api/local_cache.dart';
import '../api/ws_client.dart';
import '../models/models.dart';
import 'auth_provider.dart';
import 'version_provider.dart';

/// Helper: probeer een list-API-call. Bij succes → cache + return. Bij
/// netwerk- of HTTP-fout → val terug op de eerder gecachete waarde
/// (per gebruiker), of gooi de fout als er nog niets gecached is.
Future<List<dynamic>> _fetchListWithCache({
  required ApiClient api,
  required String path,
  required String? username,
  required String cacheName,
}) async {
  try {
    final list = await api.get(path) as List<dynamic>;
    await LocalCache.saveList(username, cacheName, list);
    return list;
  } catch (_) {
    final cached = await LocalCache.loadList(username, cacheName);
    if (cached != null) return cached;
    rethrow;
  }
}

Future<Map<String, dynamic>> _fetchObjectWithCache({
  required ApiClient api,
  required String path,
  required String? username,
  required String cacheName,
}) async {
  try {
    final r = await api.get(path) as Map<String, dynamic>;
    await LocalCache.saveObject(username, cacheName, r);
    return r;
  } catch (_) {
    final cached = await LocalCache.loadObject(username, cacheName);
    if (cached != null) return cached;
    rethrow;
  }
}

final feedProvider = AsyncNotifierProvider<FeedNotifier, List<FeedItem>>(FeedNotifier.new);

class FeedNotifier extends AsyncNotifier<List<FeedItem>> {
  ApiClient get _api => ref.read(apiProvider);
  String? get _user => ref.read(authProvider).username;

  @override
  Future<List<FeedItem>> build() async {
    final list = await _fetchListWithCache(
      api: _api, path: '/api/feed', username: _user, cacheName: 'feed');
    return list.map((e) => FeedItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> reload() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() => build());
  }

  Future<void> setRead(String id, bool read) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(isRead: read) : it).toList());
    try { await _api.put('/api/feed/$id/${read ? "read" : "unread"}'); } catch (_) {}
  }

  /// Markeer alle feed-items als gelezen. Eerst optimistisch in de
  /// lokale state, daarna één API-call. Returnt het aantal items dat
  /// de server heeft bijgewerkt, of `null` als de call faalde (oude
  /// backend zonder dit endpoint, of offline). De caller kan daarop
  /// een snackbar tonen.
  Future<int?> markAllRead() async {
    state = AsyncData(state.value!.map((it) => it.copyWith(isRead: true)).toList());
    try {
      final r = await _api.post('/api/feed/markAllRead') as Map<String, dynamic>?;
      return (r?['updated'] as int?) ?? 0;
    } catch (_) {
      return null;
    }
  }

  Future<void> toggleStar(String id) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(starred: !it.starred) : it).toList());
    try { await _api.put('/api/feed/$id/star'); } catch (_) {}
  }

  Future<void> setFeedback(String id, bool? liked) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(liked: liked) : it).toList());
    try { await _api.put('/api/feed/$id/feedback', {'liked': liked}); } catch (_) {}
  }

  Future<void> delete(String id) async {
    state = AsyncData(state.value!.where((it) => it.id != id).toList());
    try { await _api.delete('/api/feed/$id'); } catch (_) {}
  }
}

final rssProvider = AsyncNotifierProvider<RssNotifier, List<RssItem>>(RssNotifier.new);

class RssNotifier extends AsyncNotifier<List<RssItem>> {
  ApiClient get _api => ref.read(apiProvider);
  String? get _user => ref.read(authProvider).username;

  @override
  Future<List<RssItem>> build() async {
    final list = await _fetchListWithCache(
      api: _api, path: '/api/rss', username: _user, cacheName: 'rss');
    return list.map((e) => RssItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> reload() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() => build());
  }

  Future<void> refresh() async {
    await _api.post('/api/rss/refresh');
  }

  /// Re-run alleen de AI-selectie-stap op de items die al zijn opgeslagen.
  /// Goedkoop (1 Claude-call, geen fetch/summary opnieuw).
  Future<void> reselect() async {
    await _api.post('/api/rss/reselect');
  }

  Future<void> setRead(String id, bool read) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(isRead: read) : it).toList());
    try { await _api.put('/api/rss/$id/${read ? "read" : "unread"}'); } catch (_) {}
  }

  /// Markeer alle RSS-items als gelezen. Returnt het aantal items dat
  /// de server heeft bijgewerkt, of `null` bij een fout (oude backend
  /// zonder endpoint, of offline).
  Future<int?> markAllRead() async {
    state = AsyncData(state.value!.map((it) => it.copyWith(isRead: true)).toList());
    try {
      final r = await _api.post('/api/rss/markAllRead') as Map<String, dynamic>?;
      return (r?['updated'] as int?) ?? 0;
    } catch (_) {
      return null;
    }
  }

  Future<void> toggleStar(String id) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(starred: !it.starred) : it).toList());
    try { await _api.put('/api/rss/$id/star'); } catch (_) {}
  }

  Future<void> setFeedback(String id, bool? liked) async {
    state = AsyncData(state.value!.map((it) => it.id == id ? it.copyWith(liked: liked) : it).toList());
    try { await _api.put('/api/rss/$id/feedback', {'liked': liked}); } catch (_) {}
  }

  Future<void> delete(String id) async {
    state = AsyncData(state.value!.where((it) => it.id != id).toList());
    try { await _api.delete('/api/rss/$id'); } catch (_) {}
  }
}

final settingsProvider = AsyncNotifierProvider<SettingsNotifier, List<CategorySettings>>(SettingsNotifier.new);

class SettingsNotifier extends AsyncNotifier<List<CategorySettings>> {
  ApiClient get _api => ref.read(apiProvider);
  String? get _user => ref.read(authProvider).username;

  @override
  Future<List<CategorySettings>> build() async {
    final list = await _fetchListWithCache(
      api: _api, path: '/api/settings', username: _user, cacheName: 'settings');
    return list.map((e) => CategorySettings.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> save(List<CategorySettings> categories) async {
    state = AsyncData(categories);
    final list = await _api.put('/api/settings', categories.map((c) => c.toJson()).toList()) as List<dynamic>;
    await LocalCache.saveList(_user, 'settings', list);
    state = AsyncData(list.map((e) => CategorySettings.fromJson(e as Map<String, dynamic>)).toList());
  }
}

final rssFeedsProvider = AsyncNotifierProvider<RssFeedsNotifier, List<String>>(RssFeedsNotifier.new);

class RssFeedsNotifier extends AsyncNotifier<List<String>> {
  ApiClient get _api => ref.read(apiProvider);
  String? get _user => ref.read(authProvider).username;

  @override
  Future<List<String>> build() async {
    final r = await _fetchObjectWithCache(
      api: _api, path: '/api/rss-feeds', username: _user, cacheName: 'rss-feeds');
    return List<String>.from(r['feeds'] ?? []);
  }

  Future<void> save(List<String> feeds) async {
    state = AsyncData(feeds);
    final body = {'feeds': feeds};
    await _api.put('/api/rss-feeds', body);
    await LocalCache.saveObject(_user, 'rss-feeds', body);
  }
}

final requestProvider = AsyncNotifierProvider<RequestNotifier, List<NewsRequest>>(RequestNotifier.new);

class RequestNotifier extends AsyncNotifier<List<NewsRequest>> {
  ApiClient get _api => ref.read(apiProvider);
  String? get _user => ref.read(authProvider).username;
  RequestsWebSocket? _ws;

  @override
  Future<List<NewsRequest>> build() async {
    _ws ??= RequestsWebSocket()..connect().listen((msg) => _apply(msg));
    ref.onDispose(() {
      _ws?.close();
      _ws = null;
    });
    final list = await _fetchListWithCache(
      api: _api, path: '/api/requests', username: _user, cacheName: 'requests');
    return list.map((e) => NewsRequest.fromJson(e as Map<String, dynamic>)).toList();
  }

  void _apply(Map<String, dynamic> msg) {
    // Specials: de backend stuurt na (re)connect een `serverVersion`-bericht
    // zodat lange WS-sessies een nieuwe deploy oppikken zonder polling.
    if (msg['type'] == 'serverVersion') {
      ref.read(versionProvider.notifier).applyServerVersion(msg);
      return;
    }
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
      await LocalCache.saveList(_user, 'requests', list);
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
    try { await _api.delete('/api/requests/$id'); } catch (_) {}
  }

  Future<void> cancel(String id) async {
    state = AsyncData(state.value!.map((r) => r.id == id ? r.copyWith(status: 'CANCELLED') : r).toList());
    try { await _api.post('/api/requests/$id/cancel'); } catch (_) {}
  }

  Future<void> rerun(String id) async {
    state = AsyncData(state.value!.map((r) => r.id == id ? r.copyWith(status: 'PENDING') : r).toList());
    try { await _api.post('/api/requests/$id/rerun'); } catch (_) {}
  }
}

final podcastProvider = AsyncNotifierProvider<PodcastNotifier, List<Podcast>>(PodcastNotifier.new);

class PodcastNotifier extends AsyncNotifier<List<Podcast>> {
  ApiClient get _api => ref.read(apiProvider);
  String? get _user => ref.read(authProvider).username;

  @override
  Future<List<Podcast>> build() async {
    final list = await _fetchListWithCache(
      api: _api, path: '/api/podcasts', username: _user, cacheName: 'podcasts');
    return list.map((e) => Podcast.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> reload() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() => build());
  }

  /// Re-fetch zonder eerst AsyncLoading te zetten — gebruikt door de
  /// poll-timer zodat in-progress podcastkaarten niet flikkeren.
  Future<void> poll() async {
    try {
      final list = await _api.get('/api/podcasts') as List<dynamic>;
      await LocalCache.saveList(_user, 'podcasts', list);
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
    try { await _api.delete('/api/podcasts/$id'); } catch (_) {}
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
