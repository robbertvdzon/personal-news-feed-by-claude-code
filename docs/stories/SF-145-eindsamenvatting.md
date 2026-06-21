# SF-145 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Alle benodigde informatie is verzameld. Hier is de eindsamenvatting voor de PO:

---

## Eindsamenvatting SF-145 — Toon RSS-afbeeldingen in de feed (thumbnail + detail)

### Wat is gebouwd

De volledige afbeeldingenondersteuning is end-to-end geïmplementeerd, van RSS-extractie tot weergave in de Flutter-app:

**Backend (Kotlin/Spring Boot):**
- `V15__image_url.sql` — databasemigratie die kolom `image_url TEXT` toevoegt aan zowel `rss_items` als `feed_items`.
- `RssItem` en `FeedItem` data classes kregen het veld `imageUrl: String? = null`.
- `RssFetcher.extractImageUrl()` extraheert de eerste bruikbare afbeelding-URL in prioriteitsvolgorde: `<enclosure>` → `<media:thumbnail>`/`<media:content>` → `<img src>` uit de description-HTML. Geen afbeelding gevonden → `null`.
- `RssItemRepository` en `FeedItemRepository` bijgewerkt: `image_url` zit nu in alle SELECT, INSERT en UPSERT-queries.
- `RssRefreshPipeline.generateFeedItem()` kopieert `imageUrl` van RSS-item naar FeedItem.
- `openapi.yaml` bijgewerkt met `imageUrl` in de RssItem- en FeedItem-schema's.

**Frontend (Flutter):**
- `models.dart` — `imageUrl: String?` toegevoegd aan `FeedItem` en `RssItem`, inclusief `fromJson` en `copyWith`.
- `feed_card.dart` — thumbnail van 80×80px rechts van de titel (`ListTile trailing`) via `Image.network` met `errorBuilder` voor veilige fallback.
- `feed_detail_screen.dart` — header-banner van 300px hoog boven de titel via `Image.network` met `errorBuilder`.
- `feed_screen.dart` — `imageUrl` correct doorgegeven aan `ItemCard`.

### Gemaakte keuzes

- **Extractievolgorde**: enclosure heeft prioriteit boven media-namespace-velden, die weer prioriteit hebben boven inline `<img>`-tags in description-HTML. De eerste bruikbare URL wint.
- **Thumbnail positie**: rechts van de tekst als `ListTile trailing`, conform de specificatie (~80×80px).
- **Foutafhandeling**: beide `Image.network`-widgets hebben een `errorBuilder`, zodat kapotte of onbereikbare afbeelding-URLs de app nooit laten crashen.

### Wat is getest

- `RssFetcherImageUrlTest`: 6 unit-tests dekken alle extractiebronnen (enclosure, media:thumbnail, media:content, img-src in HTML), het null-geval (geen afbeelding) en de prioriteitsvolgorde. Alle tests slagen.
- Backend compile en unit-tests succesvol uitgevoerd lokaal.

### Wat bewust niet is gedaan

- Geen URL-validatie vóór `Image.network()` — de `errorBuilder` is afdoende als fallback.
- Niet alle afbeeldingen per item opgeslagen — bewust uitsluitend de eerste bruikbare URL.
- Geen live/browser-test mogelijk (geen `SF_PREVIEW_URL` beschikbaar in de CI-omgeving).

---
