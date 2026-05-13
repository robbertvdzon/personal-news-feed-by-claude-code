import 'package:flutter/foundation.dart';
import 'hard_reload_stub.dart' if (dart.library.js_interop) 'hard_reload_web.dart' as impl;

/// Voer een harde reload uit: deregistreer de service-worker, wis Cache
/// Storage en herlaad de pagina. Alleen relevant op web — op mobiel
/// wordt de native app via APK/Play geüpdatet, niet via een tab-refresh.
Future<void> hardReload() async {
  if (!kIsWeb) return;
  await impl.hardReload();
}
