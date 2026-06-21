import 'package:flutter/foundation.dart';
import 'deep_link_stub.dart' if (dart.library.js_interop) 'deep_link_web.dart' as impl;

/// Een item-deeplink uit de URL, bv. `/feed/<uuid>` of `/rss/<uuid>`.
class DeepLink {
  final String type; // 'feed' of 'rss'
  final String id;
  const DeepLink(this.type, this.id);
}

/// Gevuld bij app-start als de pagina met een item-pad geopend is. Wordt
/// door [MainShell] één keer geconsumeerd zodra de feed/rss-data geladen is
/// (na een eventuele login). Daarna weer `null`.
DeepLink? pendingDeepLink;

/// Zet op web de path-URL-strategie aan (`/feed/<id>` i.p.v. `/#/feed/<id>`)
/// en leest het start-pad in [pendingDeepLink]. No-op op mobiel — daar
/// openen gebruikers de native app, geen URL.
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

/// Schrijf de adresbalk naar het zichtbare item zodat de gebruiker kan
/// bookmarken wat 'ie leest. `type` is 'feed' of 'rss'. `replace` houdt de
/// browser-history schoon (één entry, geen back-knop per item). No-op op mobiel.
void setItemUrl(String type, String id) {
  if (!kIsWeb) return;
  impl.replaceUrl('/$type/$id');
}

/// Zet de adresbalk terug naar de root bij het sluiten van het detail-scherm.
void clearItemUrl() {
  if (!kIsWeb) return;
  impl.replaceUrl('/');
}
