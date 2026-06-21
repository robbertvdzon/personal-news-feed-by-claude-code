import 'dart:js_interop';
import 'package:flutter_web_plugins/url_strategy.dart';

/// Web: clean paths zonder hash, zodat item-URLs als `/feed/<id>`
/// verschijnen (bookmarkbaar) i.p.v. `/#/feed/<id>`.
void usePathUrls() => usePathUrlStrategy();

@JS('history')
external _History get _history;

extension type _History._(JSObject _) implements JSObject {
  external void replaceState(JSAny? data, JSString unused, JSString url);
}

/// Vervang het pad in de adresbalk zonder een history-entry toe te voegen.
/// We gebruiken de rauwe History-API i.p.v. `SystemNavigator.routeInformationUpdated`,
/// want die laatste wordt in deze app door de interne Router genegeerd
/// (de URL verandert dan niet). `replaceState` moet als method op `history`
/// aangeroepen worden, vandaar de extension type.
void replaceUrl(String path) => _history.replaceState(null, ''.toJS, path.toJS);
