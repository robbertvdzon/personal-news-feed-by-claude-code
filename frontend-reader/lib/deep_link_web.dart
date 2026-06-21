import 'package:flutter_web_plugins/url_strategy.dart';

/// Web: clean paths zonder hash, zodat item-URLs als `/feed/<id>`
/// verschijnen (bookmarkbaar) i.p.v. `/#/feed/<id>`.
void usePathUrls() => usePathUrlStrategy();
