import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'deep_link_stub.dart' if (dart.library.js_interop) 'deep_link_web.dart' as impl;

/// Een item-deeplink uit de URL, bv. `/feed/<uuid>`. De reader toont alleen
/// feed-items; een `/rss/<id>`-pad wordt herkend maar niet geopend.
class DeepLink {
  final String type; // 'feed' of 'rss'
  final String id;
  const DeepLink(this.type, this.id);
}

/// Gevuld bij app-start als de pagina met een item-pad geopend is. Wordt
/// door [FeedListScreen] één keer geconsumeerd zodra de feed geladen is.
DeepLink? pendingDeepLink;

/// Zet op web de path-URL-strategie aan (`/feed/<id>` i.p.v. `/#/feed/<id>`)
/// en leest het start-pad in [pendingDeepLink]. No-op op mobiel.
void initDeepLinks() {
  if (!kIsWeb) return;
  impl.usePathUrls();
  final segs = Uri.base.pathSegments;
  if (segs.length == 2 &&
      (segs[0] == 'feed' || segs[0] == 'rss') &&
      segs[1].isNotEmpty) {
    pendingDeepLink = DeepLink(segs[0], segs[1]);
  }
}

/// Schrijf de adresbalk naar het zichtbare item zodat je kunt bookmarken
/// wat je leest. `replace` houdt de browser-history schoon. No-op op mobiel.
void setItemUrl(String type, String id) {
  if (!kIsWeb) return;
  SystemNavigator.routeInformationUpdated(
      uri: Uri(path: '/$type/$id'), replace: true);
}

/// Zet de adresbalk terug naar de root bij het sluiten van het detail-scherm.
void clearItemUrl() {
  if (!kIsWeb) return;
  SystemNavigator.routeInformationUpdated(uri: Uri(path: '/'), replace: true);
}
