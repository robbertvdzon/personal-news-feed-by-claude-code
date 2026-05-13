import 'dart:js_interop';

/// Web-implementatie van hardReload:
///   1) Deregistreer alle service-workers (anders blijft de SW cache
///      `flutter_bootstrap.js` en index.html voorrijken aan het netwerk).
///   2) Wis Cache Storage (alle SW-buckets én eventuele app-caches).
///   3) Trigger `window.location.reload()`.
///
/// Stappen 1 en 2 zijn best-effort: faalt iets, dan reloaden we sowieso
/// — een mislukte cleanup is geen reden om de gebruiker vast te houden.
Future<void> hardReload() async {
  try {
    await _unregisterServiceWorkers();
  } catch (_) {/* ignore */}
  try {
    await _clearCaches();
  } catch (_) {/* ignore */}
  _reload();
}

@JS('navigator.serviceWorker')
external _ServiceWorkerContainer? get _serviceWorker;

extension type _ServiceWorkerContainer._(JSObject _) implements JSObject {
  external JSPromise<JSArray<_ServiceWorkerRegistration>> getRegistrations();
}

extension type _ServiceWorkerRegistration._(JSObject _) implements JSObject {
  external JSPromise<JSBoolean> unregister();
}

Future<void> _unregisterServiceWorkers() async {
  final sw = _serviceWorker;
  if (sw == null) return;
  final regs = await sw.getRegistrations().toDart;
  for (final r in regs.toDart) {
    await r.unregister().toDart;
  }
}

@JS('caches')
external _CacheStorage? get _caches;

extension type _CacheStorage._(JSObject _) implements JSObject {
  external JSPromise<JSArray<JSString>> keys();
  external JSPromise<JSBoolean> delete(JSString key);
}

Future<void> _clearCaches() async {
  final c = _caches;
  if (c == null) return;
  final keys = await c.keys().toDart;
  for (final k in keys.toDart) {
    await c.delete(k).toDart;
  }
}

@JS('window.location.reload')
external void _reload();
