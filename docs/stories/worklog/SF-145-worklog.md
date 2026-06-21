# SF-145 - Worklog

Story-context bij eerste pickup:
Voeg imageUrl toe aan backend (DB, Kotlin, pipeline) en Flutter (models, UI)

1. Maak V15__image_url.sql: voeg image_url TEXT toe aan rss_items en feed_items.
2. Voeg imageUrl: String? = null toe aan data class RssItem (RssService.kt) en FeedItem (FeedService.kt).
3. Pas RssFetcher.fetch() aan: extraheer eerste afbeelding-URL via Rome enclosures, dan entry.foreignMarkup (media:thumbnail/media:content), dan regex op description; sla op in RssItem.imageUrl.
4. Update RssItemRepository: voeg image_url toe aan map(), params() en UPSERT_SQL.
5. Update FeedItemRepository: zelfde patroon.
6. Update RssRefreshPipeline.generateFeedItem(): kopieer rss.imageUrl naar FeedItem.imageUrl.
7. Controleer of specs/openapi.yaml FeedItem/RssItem DTOs bevat; zo ja, voeg imageUrl toe.
8. Flutter models.dart: voeg imageUrl: String? toe aan RssItem en FeedItem + fromJson-ondersteuning.
9. feed_card.dart: toon thumbnail (~80×80px, BoxFit.cover, Image.network met errorBuilder) als imageUrl niet null/leeg.
10. feed_detail_screen.dart: toon header-banner (~300px, BoxFit.cover, Image.network met errorBuilder) als imageUrl niet null/leeg.
11. Schrijf unit-tests voor RssFetcher.extractImageUrl() voor alle vier bronnen en null-geval.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- V15__image_url.sql aangemaakt: voegt image_url TEXT toe aan rss_items en feed_items.
- RssItem (RssService.kt) en FeedItem (FeedService.kt) kregen imageUrl: String? = null.
- RssFetcher.extractImageUrl() extraheert afbeeldingen in volgorde: enclosure > media:thumbnail/content > img-src in description HTML.
- RssItemRepository en FeedItemRepository bijgewerkt: map(), params(), UPSERT_SQL inclusief image_url.
- RssRefreshPipeline.generateFeedItem() kopieert rss.imageUrl naar FeedItem.imageUrl.
- openapi.yaml bijgewerkt: imageUrl toegevoegd aan RssItem en FeedItem schema's.
- Flutter models.dart: imageUrl: String? toegevoegd aan FeedItem en RssItem incl. fromJson en copyWith.
- feed_card.dart: thumbnail 80x80px rechts (ListTile trailing) met Image.network + errorBuilder.
- feed_detail_screen.dart: header-banner 300px hoog boven titel met Image.network + errorBuilder.
- feed_screen.dart: imageUrl doorgegeven aan ItemCard.
- Unit-tests RssFetcherImageUrlTest: 6 tests voor alle bronnen + null-geval + prioriteit. Alle slagen.
- Backend compile en unit-tests geslaagd lokaal.
