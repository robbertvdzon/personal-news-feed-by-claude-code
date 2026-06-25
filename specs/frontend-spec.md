# Personal News Feed — Frontend Specificatie

> **Doel van dit document:** Een volledige black-box beschrijving van de frontend, zodat een AI-model de app volledig opnieuw kan bouwen zonder de originele broncode te zien. De exacte UI-vormgeving hoeft niet gerepliceerd te worden; alle functionaliteit moet aanwezig zijn.

---

## 1. Overzicht

De frontend is een **Flutter-app** (mobile + web) voor het lezen van een persoonlijke AI-nieuwsfeed. De app communiceert met de backend via REST en WebSocket.

**Platform:** Flutter (Dart SDK **^3.9**), ondersteunt iOS, Android en web.

**API-contract:** Alle endpoints, request/response-structuren en datamodellen staan beschreven in **[`openapi.yaml`](./openapi.yaml)** (OpenAPI 3.1). De frontend gebruikt al deze endpoints.

**Base URL:** Standaard `https://pnf.vdzon.com`, configureerbaar via `--dart-define=API_BASE_URL=<url>`.

**Authenticatie:** JWT Bearer token in `Authorization: Bearer <token>` header bij alle requests, behalve login en registratie. Token wordt opgeslagen in `SharedPreferences` en bij herstart automatisch hersteld.

---

## 2. Tech Stack & Dependencies

**State management:** `flutter_riverpod` (Riverpod 2.x, `AsyncNotifierProvider`, `NotifierProvider`, `StateProvider`)

**Belangrijke packages:**

| Package | Doel |
|---------|------|
| `flutter_riverpod` | State management |
| `http` | REST API-aanroepen |
| `web_socket_channel` | WebSocket verbinding |
| `shared_preferences` | Persistente opslag (token, font-instelling, audio-positie) |
| `just_audio` | Audio afspelen (podcast) |
| `url_launcher` | Links openen in externe browser |
| `flutter_markdown` | Markdown-tekst renderen (dagelijkse samenvattingen, feed-items) |

---

## 3. Navigatiestructuur

De app heeft twee hoofdstaten: **niet ingelogd** (toont LoginScreen) en **ingelogd** (toont een shell met vijf tabs).

```
AuthGate
├── LoginScreen                          (niet ingelogd)
└── MainShell — BottomNavigationBar      (ingelogd)
    ├── Tab 0: FeedScreen
    │       └── navigeer naar → FeedItemDetailScreen
    ├── Tab 1: RssScreen
    │       └── navigeer naar → RssItemDetailScreen
    │               └── navigeer naar → FeedItemDetailScreen (via "open feed-item")
    ├── Tab 2: PodcastScreen
    │       └── dialog → NieuwePodcastDialog
    │       └── navigeer naar → PodcastDetailScreen
    │               └── bottom sheet → ScriptSheet
    ├── Tab 3: EventsScreen                  (KAN-65)
    │       └── navigeer naar → EventDetailScreen
    └── Tab 4: SettingsScreen
            └── dialog → EditCategoryDialog
            └── dialog → AddCategoryDialog
            └── dialog → CleanupDialog
            └── navigeer naar → RssFeedsScreen       (via "RSS-feeds"-list-tile; beheer RSS-feeds + podcast-bronnen)
            └── navigeer naar → AdminScreen          (alleen voor admins, via "Beheer gebruikers"-knop)
            └── navigeer naar → AdminCostsScreen     (alleen voor admins, via "Beheer kosten"-knop)
```

**Belangrijk:** Alle tabs blijven in leven bij tab-wisseling (geen rebuild bij terugkeer).

---

## 4. Authenticatie

### LoginScreen
Toont een formulier met gebruikersnaam en wachtwoord.

**Functionaliteit:**
- Wachtwoordveld heeft een toon/verberg toggle
- Formulier werkt voor zowel inloggen als registreren (schakelbaar via tekstlink)
- Bij indienen: POST `/api/auth/login` of `/api/auth/register`
- Bij succes: token en gebruikersnaam opslaan in `SharedPreferences`, app gaat naar MainShell
- Foutmelding inline weergeven (geen pop-up)
- Formulier indienen via knop of toetsenbord Enter

**Na herstart:** Als er een token in `SharedPreferences` staat, direct naar MainShell zonder LoginScreen te tonen.

**Uitloggen:** Token verwijderen uit `SharedPreferences` en terug naar LoginScreen. Alle providers worden gereset.

---

## 5. Feed-tab (Tab 0)

Toont de gecureerde persoonlijke feed: `GET /api/feed` (gesorteerd op `createdAt` aflopend).

### Filteropties (altijd zichtbaar bovenaan)
- **"Verberg gelezen"-switch** (altijd-zichtbare `SwitchListTile` boven de tab-rij — niet vermengd met categorieën, want filter ≠ weergave-optie). Default `true`. Toggelt of items met `isRead: true` in de lijst verschijnen.
- **Media-filter-rij** (KAN-60, AC #8): drie `ChoiceChip`s — **Alles | RSS | Podcasts**. AND-gecombineerd met de categorie-tab en de verberg-gelezen-switch. Sessie-state in de widget (`StatefulWidget._mediaFilter`); geen persistentie over restarts. Chips zijn altijd zichtbaar — bij `Podcasts`-selectie zonder podcast-items toont de lijst gewoon 0 items. Filtert op `FeedItem.mediaType` (`'PODCAST'` vs. `'ARTICLE'`); legacy items zonder veld vallen via de json-default terug op `'ARTICLE'`.
- **Tab-rij** (horizontaal-scrollend): één tab kan actief zijn (geen multi-select). De volgorde is **"Alles" → "Bewaard" → "Samenvatting" → categorieën uit Settings**. "Bewaard" toont alleen items met `starred: true`; "Samenvatting" toont alleen items met `isSummary: true`. Elke tab toont een **bolletje met het aantal items** dat ná verberg-gelezen + media-filter in die tab valt — leeg ⇒ geen badge. De geselecteerde tab krijgt een onderstreping en kleurt naar `colorScheme.primary`.

### FeedItem-kaart (in de lijst)
Toont per item:
- **Titel:** de Nederlandse `titleNl` van het item (door Claude tegelijk met de samenvatting gegenereerd, ~70 tekens). Voor legacy items zonder `titleNl` valt de UI terug op het originele `title`-veld.
- **Bron, relatieve tijd, categorie, datum** uit de bekende velden. De relatieve tijd toont hoe lang geleden het item bij ons binnenkwam (op basis van `createdAt`): "12 minuten geleden", "3 uur geleden", "2 dagen geleden", of een absolute datum (DD-MM-YYYY) na 3 dagen. Toont niets voor legacy items zonder timestamp. Helper: `lib/util/time_format.dart`.
- **Preview:** de `shortSummary` (2 regels Nederlandse plain-text, ~30-50 woorden, eveneens door Claude gegenereerd). Voor legacy items zonder `shortSummary` valt de kaart terug op een afgekapte versie van de lange `summary`. De preview wordt **als Markdown** gerenderd via `MarkdownBody`, zodat `**vet**`, `*cursief*` en `` `code` `` netjes worden opgemaakt — vooral relevant voor de fallback uit de lange summary die volop markdown bevat. Lengte wordt begrensd door eerst de eerste paragraaf te pakken en die af te kappen rond 240 tekens (op woordgrens, met `…`); headers/lijst-bullets worden via een aangepaste `MarkdownStyleSheet` als gewone tekst gerenderd om kaart-overflow te voorkomen.

**Acties per kaart:**
- **Tik:** open FeedItemDetailScreen
- **Swipe links:** item verwijderen (DELETE `/api/feed/{id}`) — met optimistische update (direct verwijderen uit lijst)
- **👍-knop:** toggle like-feedback (PUT `/api/feed/{id}/feedback` met `liked: true`; opnieuw tikken verwijdert feedback)
- **👎-knop:** toggle dislike-feedback (`liked: false`)
- **Ster-icoon:** toggle ster (PUT `/api/feed/{id}/star`)

### Toolbar-acties
- **Markeer alles als gelezen** (`done_all`-icoon): bevestigingsdialog → `POST /api/feed/markAllRead`. Optimistische update vooraf zodat de UI direct alle items als gelezen toont.
- **Vernieuwen** (`refresh`-icoon): herlaad feed van backend.
- **Pull-to-refresh:** zelfde als vernieuwen.

### FeedItemDetailScreen
PageView waarmee je door alle (gefilterde) items heen kunt bladeren.

**Toont:**
- **Headline** = `titleNl` (fallback op `title` voor legacy items).
- Als `titleNl` aanwezig is **én** afwijkt van `title`, wordt het originele Engels eronder klein, cursief en in hint-kleur getoond — zo blijft de bron-titel herkenbaar voor wie het origineel zoekt.
- Bron, categorie, datum als chips.
- **Volledige samenvatting** = `summary` (uitgebreide Nederlandse 400-600 woord versie, 600-1000 voor daily summaries). Altijd via `MarkdownBody` + `selectable: true` zodat headers, vet/cursief, lijsten en paragrafen netjes worden gerenderd én de tekst gekopieerd kan worden met cmd/ctrl+c.
- Bronlink(s) onderaan: tik om te openen in externe browser; lang indrukken om URL te kopiëren.

**AppBar-acties** (per huidig item, status reflecteert live de provider-state — dus toggles updaten de iconen direct):
- 👍 **Vind ik leuk** (`thumb_up_outlined` / `thumb_up` in groen) — `setFeedback(id, true)`; opnieuw tikken zet hem op `null`.
- 👎 **Niet relevant** (`thumb_down_outlined` / `thumb_down` in rood) — `setFeedback(id, false)`.
- ⭐ **Bewaar** (`star_outline` / `star` in amber) — `toggleStar(id)`.
- ✉️ **Markeer als (on)gelezen** (`mark_email_read_outlined` ↔ `mark_email_unread_outlined`) — `setRead(id, !isRead)`. Handig om een artikel terug op ongelezen te zetten als je 'm later wilt herlezen.

**Navigatie:** swipe horizontaal of via PageController om door items te bladeren.

**Auto-markeer-gelezen:** het item dat getoond wordt bij openen én elk item waarnaar geswiped wordt, wordt automatisch als gelezen gemarkeerd.

---

## 6. RSS-tab (Tab 1)

Toont ruwe RSS-artikelen na AI-verwerking: `GET /api/rss` (gesorteerd op `timestamp` aflopend).

### Filteropties
Identiek aan Feed-tab in opbouw: een aparte **"Verberg gelezen"-switch** boven de tab-rij (default `_hideRead = true`), gevolgd door dezelfde **media-filter-rij 'Alles | RSS | Podcasts'** (KAN-60, AC #7 — sessie-state, AND-gecombineerd met de categorie-tab) en daarna de categorie-tab-rij. Volgorde van de tab-rij: **"Alles" → "Bewaard" → categorieën → "Overig"**. "Bewaard" filtert op `starred: true`. "Overig" verzamelt items met categorie `overig` of zonder. (Feed heeft een extra "Samenvatting"-tab die op `isSummary: true` filtert; RssItem heeft dat veld niet, dus deze tab ontbreekt op de RSS-tab.) Elke tab toont een bolletje met het aantal items dat ná verberg-gelezen + media-filter in die tab valt.

### RssItem-kaart
Toont: titel, bron, **relatieve tijd** ("12 minuten geleden" / "3 uur geleden" / "2 dagen geleden" / DD-MM-YYYY na 3 dagen, op basis van `timestamp`), categorie, datum en een **preview-tekst van max 2 regels**. De preview toont bij voorkeur de Nederlandse AI-samenvatting (`summary`) — die geeft de gebruiker direct context in zijn eigen taal. Als `summary` leeg is (item nog niet door AI verwerkt) valt de kaart terug op de ruwe RSS-`snippet`. Een badge geeft aan of het item **in de feed** staat (`inFeed: true`) of niet, inclusief een tooltip met de `feedReason`.

**KAN-60 — show-notes-voorlopige-badge (AC #2):** podcast-kaartjes met `summarySource: 'show_notes'` tonen extra een amberkleurig `📝 voorlopig`-chip met tooltip "Voorlopige samenvatting op basis van de RSS show-notes — het echte transcript wordt op de achtergrond verwerkt." De badge verdwijnt automatisch bij de volgende data-refresh nadat de transcript-worker `summary_source` op `'transcript'` heeft gezet. Voor feeds met `transcribeEnabled=false` blijft de badge permanent staan (de eindgebruiker weet dan dat deze bron altijd op show-notes draait).

**Acties per kaart:** identiek aan Feed (swipe-delete, 👍/👎, ster).

### RssItemDetailScreen
Identiek qua PageView-navigatie en AppBar-acties als FeedItemDetailScreen.

**Feed-status banner:** prominent zichtbaar onder de chips, met groene tint + check-icon als `inFeed: true` ("In persoonlijke feed") of oranje tint + info-icon als `inFeed: false` ("Niet in persoonlijke feed"). Onder de kop staat het volledige `feedReason`-veld met de motivatie van Claude. Als `feedReason` leeg is wordt een fallback-tekst getoond ("Geen reden door AI gegeven (mogelijk nog niet beoordeeld of API-key ontbreekt)") zodat de gebruiker altijd ziet of het item is beoordeeld.

**Topics:** als `topics` niet leeg is worden ze als compacte chips onder de banner getoond.

**Extra acties:**
- **"Meer hierover"-knop:** maakt een nieuw verzoek aan (POST `/api/requests`) met het artikel als bronverwijzing (`sourceItemId`, `sourceItemTitle`). Opent een dialoog om het verzoek te bevestigen.
- **"Open feed-item"-knop:** alleen zichtbaar als `feedItemId` ingevuld is. Navigeert naar FeedItemDetailScreen voor het gekoppelde feed-item.
- Tik op bronnaam: opent `feedUrl` of `url` in externe browser.

### RssPodcastDetailScreen (KAN-62)
Voor RssItems met `mediaType: 'PODCAST'` opent **niet** `RssItemDetailScreen` maar een dedicated podcast-detail-scherm (`rss_podcast_detail_screen.dart`). Deze routing-keuze gebeurt in zowel `rss_screen.dart` (direct op `isPodcast`) als `feed_screen.dart` (FeedItem `isPodcast` → opzoeken van de matchende RssItem via `sourceRssIds` → routeren naar dezelfde screen; fallback op `FeedItemDetailScreen` wanneer de RssItem niet gevonden wordt).

Het scherm gebruikt dezelfde PageView-navigatie en AppBar-acties (👍/👎/⭐/lees), maar de body bevat drie podcast-specifieke secties:

1. **Lange samenvatting** (~400-600 woorden, 3-5 alinea's): gerenderd uit `longSummary`. Bij ontbrekende waarde (nog niet door uitgebreide Claude-prompt of niet-gebackfilled): valt terug op `summary` plus een cursieve hint *"Uitgebreide samenvatting wordt op de achtergrond verwerkt"*.
2. **Key takeaways**: bullet-list van `keyTakeaways` (5-10 regels). Sectie wordt verborgen wanneer de lijst leeg is.
3. **Ruw transcript**: `ExpansionTile`, default ingeklapt. Bij eerste uitklap fetcht het scherm `GET /api/rss/{id}/transcript` (lazy — feed-listing transporteert geen 50-90k chars per podcast). Voor `summarySource='show_notes'`-items toont 'ie geen knop maar een placeholder *"Transcript wordt nog verwerkt"* (AC #5).

Onderaan staat een **"🇳🇱 Vertaal & genereer Nederlandse podcast"**-knop (KAN-63) en een **"Origineel afspelen"**-knop die `url` (de MP3) in een externe player opent.

**Translate-knop (KAN-63):** bij openen van het scherm fetcht de body `GET /api/podcast-source/by-rss-item/{rssItemId}` om de bron-aflevering-guid, transcript-lengte en (eventueel) een bestaande vertaling op te halen. De knop heeft drie staten:
- **"🇳🇱 Vertaal & genereer Nederlandse podcast"** (default) — actief zodra het Engelse transcript klaar is (`episodeStatus='DONE'`). Tik opent een dialog met de geschatte kosten (vertaling + TTS in $, 2 decimalen) op basis van de transcript-lengte. Bij "Starten": `POST /api/podcast-source/{episodeGuid}/translate`. HTTP 202 → nieuwe podcast verschijnt bovenaan de Podcast-tab; HTTP 409 → snackbar "Transcript is nog niet klaar voor vertaling".
- **"🇳🇱 Bekijk vertaling"** — zodra er een DONE-vertaling bestaat. Tik navigeert naar `PodcastDetailScreen` van die podcast.
- **"🇳🇱 Bekijk vertaling — vertalen… / audio genereren…"** — wanneer de vertaling op de achtergrond loopt (status `PENDING` / `TRANSLATING` / `TTS_GENERATING`). De detail-pagina pollt zelf elke 4 seconden tot de status `DONE`/`FAILED` is.

**Client-side cost-schatting** (geen apart API-endpoint, refiner-keuze): `tokens ≈ transcriptChars / 4`; `translateCost = (tokens/1000) × (0.0005 + 0.002)`; `ttsCost = transcriptChars / 1_000_000 × 15`. Dit is een vuistregel — de echte kosten worden achteraf via `external_calls` per call gelogd.

### Toolbar-acties
- **Vernieuwen (van bron) (`cloud_download`):** roept POST `/api/rss/refresh` aan, daarna periodiek (elke 4 seconden) opnieuw GET `/api/rss` tot verversing klaar is.
- **AI-selectie opnieuw (`auto_awesome`):** roept POST `/api/rss/reselect` aan om alleen de AI-selectie-stap te draaien op de al-opgeslagen items, zonder fetch/summary opnieuw. Toont snackbar "AI-selectie opnieuw gestart — check backend log".
- **Markeer alles als gelezen (`done_all`):** bevestigingsdialog → `POST /api/rss/markAllRead`. Optimistische update vooraf zodat de UI direct alle items als gelezen toont.
- **Lijst herladen (`refresh`):** alleen de lokale lijst opnieuw ophalen (GET `/api/rss`).

---

## 7. Verzoeken (achtergrond — geen eigen tab)

De app heeft géén Queue-tab meer. De twee scheduled jobs lopen automatisch door:

- **Uurlijkse RSS-update** — vast verzoek-record met ID `hourly-update-{username}` (flag `isHourlyUpdate: true`).
- **Dagelijkse samenvatting** — vast verzoek-record met ID `daily-summary-{username}` (flag `isDailySummary: true`).

De gebruiker kan ze beide handmatig starten via de sectie *Achtergrond-taken* op de instellingen-tab (zie §9). De `requestProvider` blijft op de achtergrond actief — hij is nodig om de knop-state (idle / "Loopt al…") en de "Klaar"-toast af te kunnen leiden uit de live status.

### WebSocket-integratie
Verbinding met `ws(s)://{host}/ws/requests` zodra de verzoeken geladen zijn.

- Inkomende berichten zijn JSON-objecten. De handler onderscheidt twee types:
  - **`{"type": "serverVersion", "sha": "...", "buildTime": "..."}`** — wordt direct na (re)connect verstuurd. De `RequestNotifier` filtert dit bericht eruit en geeft het door aan `versionProvider` (zie sectie 9 — *Versie-check & snackbar*).
  - **`NewsRequest`-objecten** (geen `type`-veld) conform het schema uit `openapi.yaml` (zie ook de berichtspecificatie in `backend-functional-spec.md` sectie 5).
- **Belangrijk:** de WebSocket-broadcast bevat updates van **alle** gebruikers (server filtert niet per user). De frontend moet zelf filteren:
  - Bij **bekend ID** in de lokale lijst: vervang het item — dit is veilig omdat de lokale lijst is geladen via `GET /api/requests` (JWT-gescoped, dus alleen eigen items).
  - Bij **onbekend ID**: doe een stille herlaad van de volledige verzoeklijst via `GET /api/requests`. De backend filtert daar wel op JWT, dus updates van andere gebruikers verdwijnen automatisch en eigen nieuwe items komen binnen. Voeg het item níet rechtstreeks toe op basis van het WebSocket-bericht — dan zouden andere gebruikers' verzoeken zichtbaar worden.
- Bij status DONE of CANCELLED: automatisch RSS-items en feed-items herladen (nieuwe artikelen kunnen zijn binnengekomen)
- Bij verbrekingsfout: automatisch herverbinden na 5 seconden
- Verbinding verbreken bij uitloggen

### Ad-hoc "Meer hierover"-verzoeken
Vanuit de RSS-item-detailpagina kan de gebruiker met **Meer hierover** een ad-hoc verzoek aanmaken (`POST /api/requests` met `sourceItemId`/`sourceItemTitle`). De UI toont alleen een bevestigingstoast — er is geen aparte lijst meer waarin deze verzoeken zichtbaar zijn. De resultaten verschijnen vanzelf in de feed wanneer de backend het verzoek heeft verwerkt.

---

## 8. Podcast-tab (Tab 2)

Toont gegenereerde podcasts: `GET /api/podcasts`.

### PodcastCard (in de lijst)
Toont: podcastnummer, titel, datum, duur, status, kosten, TTS-provider.

**Visuele progress-indicatie:** zolang de podcast nog niet `DONE` of `FAILED` is, vervangt een `CircularProgressIndicator` het podcasts-icoon, en wordt het Nederlandse statuslabel ("In wachtrij…", "Onderwerpen bepalen…", "Script schrijven…", "Audio genereren…", "Vertalen…") in primaire kleur en bold getoond. Bij `FAILED` toont een rood error-icon en label "Mislukt".

**KAN-63 — vertaal-badge:** voor podcasts met `translatedFromEpisodeGuid != null` toont de subtitle in plaats van "Duur: Xmin · TTS: Y" de chip-tekst *"Vertaald van \<feed-naam\>"* (een `Icons.translate` + 1-regel waarde uit `translatedFromFeedName`). Op de detail-pagina komt deze info terug als een aparte `Chip` die navigeert naar het bron-RSS-podcast-detail-scherm (lookup op `translatedFromRssItemId` in de rssProvider; niet-tappable als de bron-aflevering inmiddels uit de RSS-tab is opgeruimd).

**Acties:**
- **Play/pause-icoon op kaart:** laadt audio en speelt af / pauzeert (zie audiospeler)
- **Swipe links:** podcast verwijderen (DELETE `/api/podcasts/{id}`)
- **Tik op kaart:** navigeer naar PodcastDetailScreen

### NieuwePodcastDialog
- Optioneel: onderwerpen (vrije tekst, één per regel)
- **Periode (dagen):** numeriek tekstveld (standaard `7`; geen slider — gebruiker tikt zelf het getal in, zodat ook waarden als `1` of `13` mogelijk zijn).
- **Duur (minuten):** numeriek tekstveld (standaard `15`; geen slider — vrije keuze, bv. `1` of `13` minuten).
- "Maak"-knop is disabled tot beide velden een geheel getal ≥ 1 bevatten.
- Keuze TTS-provider: ElevenLabs of OpenAI
- Indienen: POST `/api/podcasts`

### PodcastDetailScreen
Toont: titel, periode, duur, kosten, TTS-provider, onderwerp-chips, volledig audiospeler-paneel.

**KAN-63 — vertaling-modus:** wanneer `podcast.isTranslation` (d.w.z. `translatedFromEpisodeGuid != null`) staat er onder de status-chips een chip "Vertaald van \<feed-naam\>" met tap-actie die terugnavigeert naar de bron `RssPodcastDetailScreen` (lookup via rssProvider op `translatedFromRssItemId`). Bij status `FAILED` toont het scherm bovenaan een rode foutbox met `errorMessage`. De detail-pagina pollt elke 4 seconden zolang de status nog `PENDING` / `TRANSLATING` / `TTS_GENERATING` is en switcht automatisch naar de audiospeler zodra `DONE`.

**Audiospeler:**
- Play/pause-knop
- Seekbar met huidige positie en totale duur
- Skip-knoppen: −60s, −30s, −15s, +15s, +30s, +60s
- **Positiepersistentie:** positie elke 5 seconden opgeslagen in `SharedPreferences` (`podcast_pos_{id}`); hersteld bij herlaad; niet opgeslagen als binnen 10 seconden van het einde (zodat volgende keer vanaf begin begint)
- **Download-knop:** opent audio-URL in externe app/browser

**Draaiboek-knop:** opent een bottom sheet met het volledige podcastscript. Hiervoor wordt GET `/api/podcasts/{id}` aangeroepen (bevat `scriptText`).

### Mini-player
Zolang audio actief is (ook na navigeren naar andere schermen binnen de Podcast-tab), verschijnt onderin een mini-player met play/pause, seekbar en dezelfde skip-knoppen.

### Audio-URL
`GET /api/podcasts/{id}/audio?token=<jwt>&v=<durationSeconds>`

- `token`: JWT als query-parameter (nodig omdat audiospelers geen `Authorization`-header kunnen sturen)
- `v`: cache-buster (gebruik `durationSeconds` van de podcast)

### Podcast-polling
Zolang een of meer podcasts de status `PENDING`, `DETERMINING_TOPICS`, `GENERATING_SCRIPT` of `GENERATING_AUDIO` hebben, wordt elke 4 seconden GET `/api/podcasts` opnieuw aangeroepen totdat alle podcasts `DONE` of `FAILED` zijn.

> **Belangrijk:** poll-fetches mogen de provider níet via `invalidate()` resetten — dat zou de `AsyncData` voor 1-2 frames terugzetten naar `loading` en de progress-indicator op de kaart laten flikkeren. Implementatie: een aparte `poll()` notifier-methode die de lijst stilletjes ophaalt en de state vervangt zonder eerst `AsyncLoading` te zetten.

---

## 8b. Events-tab (Tab 3) — KAN-65

Toont de per-gebruiker AI-ontdekte tech-events uit `eventsProvider` (`GET /api/events`).

- **Lijst**: gesplitst in twee secties, **"Aankomend"** (begindatum vandaag of later, oplopend gesorteerd) en **"Geweest"** (aflopend gesorteerd). Events zonder begindatum tellen als aankomend. Elke kaart toont naam, datum-range (Nederlands geformatteerd), locatie, organisatie en een categorie-chip.
- **Toolbar**: een zoekknop (`Icons.travel_explore`) die `POST /api/events/discover` triggert (mirror van de RSS-refresh; toont een toast), en een herlaad-knop.
- **EventDetailScreen**: naam, datum/locatie/organisatie als chips, de Nederlandse beschrijving (onderwerpen) als selecteerbare tekst, en de bronlinks als aantikbare tegels (openen extern via `url_launcher`). Een verwijder-knop verwijdert het event en keert terug naar de lijst.
- **Video's** (KAN-66): boven de bronlinks staat een lijst kaarten met de per event ontdekte video's. Elke kaart heeft een trailing "open in browser"-icoon dat de externe video opent via `url_launcher`.
- **Video-samenvatting** (KAN-67): elke video-kaart toont onderaan een knop "Maak samenvatting" wanneer er nog geen Nederlandse samenvatting voor die video bestaat. Indrukken stuurt `POST /api/events/{id}/videos/summarize` met de `videoUrl`; tijdens de call laat de kaart een mini-spinner zien met de tekst "Samenvatting wordt gemaakt…" en is de knop disabled. Bij succes verschijnt de samenvatting in een gehighlight tekstblok en verdwijnt de knop. Bij HTTP 502 (geen transcript verkrijgbaar) toont een snackbar de melding "Samenvatting kon niet worden gemaakt — probeer het later opnieuw."; de knop blijft staan zodat de gebruiker het opnieuw kan proberen.
- De handmatige zoek-trigger zit óók in Settings onder *Achtergrond-taken* (zie §9).

---

## 9. Settings-tab (Tab 4)

### Account
- Gebruikersnaam weergeven
- **Uitloggen:** wist token, navigeer naar LoginScreen

### Weergave
- Lettergrootte-instelling: "Normaal" of "Groot"
- "Groot" schaalt alle tekst met factor ~1.38 (via `TextScaler` op `MediaQuery`)
- Instelling opgeslagen in `SharedPreferences`

### Categorieën
Lijst van alle categorieën uit `GET /api/settings`.

**Per categorie:**
- **Schakelaar (enabled/disabled):** PUT `/api/settings` met bijgewerkte lijst
- **Tik:** opent EditCategoryDialog
  - Naam wijzigen
  - Extra AI-instructies wijzigen
  - Opslaan: PUT `/api/settings`
  - Verwijderen (knop): PUT `/api/settings` met categorie verwijderd; systeemcategorieën (`isSystem: true`) hebben geen verwijderknop

**Categorie toevoegen:** knop opent AddCategoryDialog
- Naam invoeren
- Opslaan: PUT `/api/settings` met nieuwe categorie toegevoegd (ID gegenereerd op basis van naam)

### RSS-feeds (navigatie naar subpagina — SF-220)
Op de settings-pagina staat op deze plek **geen inline-editor meer**, maar één navigatie-`ListTile` (icoon `Icons.rss_feed`, titel "RSS-feeds", subtitel "Beheer RSS-feeds en podcast-bronnen", trailing chevron) die via `MaterialPageRoute` naar **`RssFeedsScreen`** (`lib/screens/rss_feeds_screen.dart`) pusht. Consistent met de Beheer-tiles. Dit houdt de settings-pagina korter; het feed-beheer zelf is ongewijzigd verhuisd naar de subpagina.

#### RssFeedsScreen (subpagina)
Eigen `Scaffold` + `AppBar` (titel "RSS-feeds"). Bevat twee secties met dezelfde sectiekoppen, loading-spinner en error-tekst ("Fout: …") als voorheen:

**RSS-feeds** — lijst van geconfigureerde RSS-feed URLs uit `rssFeedsProvider` (`GET /api/rss-feeds`):
- **Tik op URL:** opent URL in externe browser
- **Verwijder-icoon (×):** verwijder feed-URL, PUT `/api/rss-feeds`
- **Invoerveld + toevoegen-knop:** nieuwe URL toevoegen, PUT `/api/rss-feeds`

**Podcast-bronnen** (KAN-56) — lijst van podcast-RSS-bronnen uit `podcastFeedsProvider`:
- **Tik op URL:** opent URL in externe browser; URL in monospace.
- **"Transcriberen aan/uit"-toggle** per bron: bij uit valt de backend terug op de RSS show-notes als input voor Claude (geen Whisper-kosten).
- **Verwijder-icoon (×):** verwijder bron via `save`.
- **Invoerveld + toevoegen-knop:** nieuwe podcast-RSS-URL toevoegen. De `save` valideert de URL synchroon op de server; bij een ongeldige URL (HTTP 400) toont het scherm een snackbar met de Nederlandse foutmelding uit de response-body. Tijdens opslaan is het veld disabled en draait een kleine spinner.

### Achtergrond-taken
Twee handmatige triggers voor de scheduled jobs (die zelf gewoon doorlopen op hun schedule — hourly RSS-refresh en de daily summary om 06:00):

- **"RSS-feeds nu vernieuwen"** — stuurt `POST /api/requests/{hourly-update-{username}}/rerun`.
- **"Genereer dagelijkse samenvatting nu"** — stuurt `POST /api/requests/{daily-summary-{username}}/rerun`.

Gedrag per rij:

- Status (`PENDING` / `PROCESSING` / `DONE`) wordt afgeleid uit de live `requestProvider` (gevoed door REST + WebSocket).
- Zolang de bijbehorende job `PENDING` of `PROCESSING` is, is de knop **disabled** met tooltip *"Loopt al…"* en toont een kleine `CircularProgressIndicator` in plaats van het play-icoon.
- Wanneer de status overgaat naar `DONE` terwijl Settings open staat, verschijnt een snackbar **"Klaar — N items verwerkt"** (één keer per transitie, op basis van `newItemCount` uit het verzoek).
- De vaste records bestaan altijd zodra `ensureFixedRequests` heeft gedraaid; bij ontbreken (eerste login zonder server-roundtrip) zijn de knoppen disabled.

### Over deze app
Onderaan het instellingen-scherm staat een blok **Over deze app** met twee regels:

- **Frontend:** `<short-git-sha>` · `<build-timestamp in lokale tijd>` — beide compile-time geïnjecteerd via `--dart-define=BUILD_SHA=...` en `--dart-define=BUILD_TIME=...` en uitgelezen met `String.fromEnvironment`. Altijd beschikbaar uit de bundel zelf.
- **Backend:** `<short-git-sha>` · `<build-timestamp in lokale tijd>` — komt uit het `versionProvider` (gevuld door `GET /api/version` of het WebSocket `serverVersion`-bericht). Bij een fout (`/api/version` offline of 5xx) toont de regel **`onbekend`** tot de volgende geslaagde check.

Buildnummer = de korte git-SHA (zoals `da6ca15`), zelfde waarde als op de container-images. De build-timestamp komt binnen als ISO-8601 UTC (`2026-05-13T14:32:00Z`) en wordt weergegeven in lokale tijd (`13 mei 2026 14:32`).

### Versie-check & snackbar "Er is een nieuwe versie beschikbaar"
Naast het Settings-blok detecteert de app actief of er een nieuwe versie live staat zodat een mobiel-Chrome-gebruiker niet eindeloos in een gecachete bundel blijft hangen.

- **Trigger-momenten** (geen periodieke polling):
  - Bij app-start, direct na het ophalen van de auth-state.
  - Bij `AppLifecycleState.resumed` — op web triggert dat bij window-focus, op mobiel bij terugkeer uit de achtergrond.
  - Bij elke (re)connect van `/ws/requests` — de backend stuurt direct een `serverVersion`-bericht.
- **Vergelijking:** de eerste binnenkomende backend-SHA wordt vastgelegd als "de versie waarmee deze tab geladen is". Een latere check die een afwijkende `sha` rapporteert, zet `updateAvailable=true`.
- **Snackbar:** zolang `updateAvailable=true` toont een permanente banner onderin (binnen `MaterialApp.builder` als Stack-overlay, dus zichtbaar op elke tab) met tekst *"Er is een nieuwe versie beschikbaar."* en knop **Nu vernieuwen**. De banner heeft **geen timeout** — hij blijft staan tot de gebruiker tikt of de pagina handmatig vernieuwt. Bij gelijke versies is er geen UI-indicatie.
- **"Nu vernieuwen" doet een harde reload** (alleen op web, `kIsWeb`):
  1. Deregistreer alle service-workers (`navigator.serviceWorker.getRegistrations().unregister()`).
  2. Wis Cache Storage (`caches.keys()` + `caches.delete(key)`).
  3. `window.location.reload()`.
  
  Beide cleanup-stappen zijn best-effort; faalt iets, dan reloaden we sowieso. Op mobiel (Android/iOS native) is dit een no-op — daar gaat updaten via Play/App Store of nieuwe APK.
- **Backend-only deploy:** als alleen de backend wordt geredeployd en de frontend-bundel onveranderd blijft, ziet de gebruiker geen snackbar — alleen de Backend-regel in Settings verandert (live bij WS-reconnect).

### Opruimen (cleanup)
Knop "Artikelen opruimen" opent CleanupDialog:
- **Aantal dagen** numeriek tekstveld (standaard 30; geen slider — gebruiker tikt zelf het getal in)
- Opties: "bewaar bewaard", "bewaar geliket", "bewaar ongelezen" (checkboxes)
- **Speciaal: 0 dagen = alles wissen.** Wanneer het veld op 0 staat:
  - de drie "bewaar"-checkboxes worden visueel uitgeschakeld en op `false` gezet
  - het help-tekstje toont in foutkleur "0 dagen = alles wissen, ook bewaard/geliket/ongelezen"
  - de "Opruimen"-knop wijzigt naar "Alles wissen" en krijgt een rode achtergrond
  - bij bevestigen worden de keep-flags geforceerd op `false` in de query-string, ongeacht eerdere checkbox-stand
- **Bevestigen:** roept zowel `DELETE /api/rss/cleanup?...` als `DELETE /api/feed/cleanup?...` aan met dezelfde parameters — beide verzamelingen worden altijd opgeruimd, RSS-items en gecureerde feed-items.
- **Annuleren:** sluit dialog zonder actie

### Beheer (alleen admins)

Onderaan het instellingen-scherm verschijnt een extra sectie **"Beheer"** die alleen zichtbaar is voor gebruikers met de rol `admin`.

- **Beheer gebruikers-knop:** navigeert via `Navigator.push` naar AdminScreen.
  - AdminScreen toont alle gebruikers met hun rollen.
  - Per gebruiker: wachtwoord resetten, promoveren naar admin, degraderen naar user, verwijderen.

- **Beheer kosten-knop:** navigeert via `Navigator.push` naar AdminCostsScreen.
  - AdminCostsScreen toont kostenoverzichten per dag, per gebruiker en gedetailleerd logboek van externe API-calls.

Er is geen aparte Admin-tab in de bottom navigation bar — alle admin-functionaliteit zit achter de twee Beheer-knoppen in Settings.

---

## 10. State Management Details

De app gebruikt Riverpod. Providers zijn globaal beschikbaar via `ProviderScope` aan de root.

### Sleutelprincipes
- **Optimistische updates:** acties als verwijderen, like/unlike, ster-toggle en verzoek-annuleren worden direct in de lokale state doorgevoerd zonder te wachten op serverbevestiging. Falende API-calls (bv. offline) worden stil gelogd zodat de UI bruikbaar blijft.
- **Gedeelde read/ster/feedback-state:** lees-, ster- en feedbackstatus worden bij het laden vanuit de server-response geïnitialiseerd en lokaal bijgehouden; API-calls synchroniseren de backend
- **Provider-reset bij uitloggen:** alle data-providers worden gereset zodat geen data van de vorige gebruiker zichtbaar is na uitloggen
- **Offline cache (zie sectie 14):** elke list-fetch (feed, rss, requests, podcasts, settings, rss-feeds) wordt na succes per gebruiker in `SharedPreferences` opgeslagen; bij netwerkfouten valt de notifier terug op die laatst-bekende waarde zodat de Android-app gewoon door blijft werken zonder verbinding.

### Providers (overzicht)

| Provider | Verantwoordelijkheid |
|----------|----------------------|
| `authProvider` | Login-state, token persistentie, uitloggen |
| `feedProvider` | Feed-items (`/api/feed`) |
| `filteredFeedProvider` | Afgeleide gefilterde feedlijst op basis van categorie, gelezen, ster, samenvatting |
| `rssItemsProvider` | RSS-items (`/api/rss`) |
| `requestProvider` | Verzoeken + WebSocket-updates (gebruikt door Settings → Achtergrond-taken voor knop-state en klaar-toast) |
| `settingsProvider` | Categorie-instellingen |
| `rssFeedsProvider` | RSS-feed URLs (beheerd op `RssFeedsScreen`) |
| `podcastFeedsProvider` | Podcast-RSS-bronnen + transcribe-toggle, KAN-56 (beheerd op `RssFeedsScreen`) |
| `podcastProvider` | Podcasts + polling tijdens generatie |
| `audioPlayerProvider` | Audiospelerstatus (`just_audio`) |
| `appearanceProvider` | Lettergrootte-instelling (persistentie) |
| `versionProvider` | Frontend- en backend-versie + mismatch-detectie (snackbar trigger) |

---

## 11. Overige Gedragsdetails

### Markdown-rendering (FeedItemDetailScreen)
De samenvatting van feed-items en dagelijkse samenvattingen wordt als Markdown weergegeven via het `flutter_markdown` package. Minimaal ondersteunde opmaak: koppen (`#`, `##`, `###`), ongeordende lijsten, vet, cursief, vet+cursief.

### Lettergrootte
De lettergrootte-instelling geldt app-breed via een `MediaQuery`-wrapper in de root. Detailschermen passen dit ook toe.

### Foutafhandeling
- API-fouten worden inline getoond (geen aparte foutpagina)
- Bij netwerkverlies toont de app de laatste bekende data
- Laadstatus wordt per scherm weergegeven (laadindicator)

### System inset / SafeArea
Detail-schermen zonder bottom navigation bar (FeedItemDetailScreen, RssItemDetailScreen, PodcastDetailScreen) en de Settings-ListView passen `MediaQuery.of(context).padding.bottom` als extra bottom-padding op de scrollende inhoud toe. Zonder dat verdwijnen de onderste knoppen ("Open bron", "Meer hierover", "Draaiboek", "Download", "Artikelen opruimen") op Android achter de gesture nav-bar en op iOS achter de home-indicator. De Material 3 `NavigationBar` van de MainShell is zelf safe-area-aware en heeft die fix niet nodig.

---

## 12. Configuratie

| Waarde | Hoe configureren | Standaard |
|--------|-----------------|-----------|
| Backend URL | `--dart-define=API_BASE_URL=https://...` bij build/run | `https://pnf.vdzon.com` |
| App-icoon | `flutter_launcher_icons` in `pubspec.yaml` (zie hieronder) | — |

### App-icoon
Het icoon is **`assets/app_icon.png`** (1024×1024, indigo achtergrond met witte feed-lijnen + RSS-arc, gegenereerd met `tools/make_icon.py` of vergelijkbaar). De `flutter_launcher_icons`-config in `pubspec.yaml` schaalt 'm naar alle Android- en iOS-formaten:
- Android: alle `mipmap-*` resoluties + adaptive-icon (`mipmap-anydpi-v26`) met `#3F51B5` (indigo) als achtergrond
- iOS: `Assets.xcassets/AppIcon.appiconset` (alpha verwijderd)

Genereren na een wijziging in `assets/app_icon.png`:
```
flutter pub get
flutter pub run flutter_launcher_icons
```

In de app zelf wordt hetzelfde icoon klein (32px, afgerond) als `leading` van elke `AppBar` getoond (Feed, RSS, Podcast, Settings) via de `AppLogo`-helper in `lib/widgets/app_logo.dart`.

---

## 13. Development & Build

De frontend wordt aangestuurd via een `Makefile` in de `frontend/` map.

### Lokaal draaien (web)

```bash
make serve-ext   # of: make run
```

- Start de Flutter web-app op **poort 3000**, alleen op `localhost`
- Gebruikt backend op **`http://localhost:8080`**
- Geschikt voor lokale ontwikkeling terwijl de backend lokaal draait

Intern voert dit zoiets uit als:
```bash
flutter run -d web-server --web-port 3000 \
  --dart-define=API_BASE_URL=http://localhost:8080
```

### Web extern beschikbaar maken (LAN / port-forward)

Flutter's `web-server` device luistert **standaard alleen op localhost**. Voor toegang vanaf een ander apparaat (LAN-test) of via een port-forward (extern), is `--web-hostname 0.0.0.0` nodig:

```bash
make serve-public PUBLIC_API=http://217.120.100.76:19286
```

Twee dingen om te weten:

1. **Flutter listen-adres.** Met `--web-hostname 0.0.0.0` luistert de dev-server op alle interfaces zodat browsers op andere machines hem kunnen bereiken op de LAN-ip of het externe IP.
2. **API-base moet ook van buiten bereikbaar zijn.** De externe browser doet zelf `fetch(API_BASE_URL)` — die URL moet dus een adres zijn dat *hun* browser kan resolven, niet `localhost` (= hun eigen pc). Zet in `PUBLIC_API` het publieke adres+poort waar de Spring Boot backend bereikbaar is.

In de praktijk betekent dit twee port-forwards op de router:

| Externe poort | → | Mac-poort | Doel |
|---|---|---|---|
| 19285 | → | 3000 | Flutter web-server |
| 19286 | → | 8080 | Spring Boot backend |

(Of gebruik een reverse-proxy zoals nginx/Caddy om alles op één externe poort aan te bieden — zie repo-README voor een voorbeeld.)

### APK bouwen (Android, productie)

```bash
make build-apk-ext
```

- Bouwt een release-APK
- Gebruikt backend op **`http://217.120.100.76:19283`**

**Android cleartext-HTTP**: omdat de backend self-hosted is zonder TLS, moeten twee dingen geregeld zijn in de Android-manifest (anders krijgt de release-APK `Operation not permitted, errno = 1` op elke API-call):

1. `<uses-permission android:name="android.permission.INTERNET"/>` in `frontend/android/app/src/main/AndroidManifest.xml`. Het Flutter-template zet die default alleen in de debug-manifest, niet in main.
2. `android:usesCleartextTraffic="true"` op de `<application>`, plus een `network_security_config.xml` (in `res/xml/`) die cleartext-HTTP whitelist voor specifieke domains/IPs:
   ```xml
   <domain-config cleartextTrafficPermitted="true">
       <domain includeSubdomains="false">217.120.100.76</domain>
       <domain includeSubdomains="false">localhost</domain>
       <domain includeSubdomains="false">10.0.2.2</domain>
   </domain-config>
   ```
   Andere domains blijven default HTTPS-only. `10.0.2.2` is het host-loopback van de Android emulator.

Intern zoiets als:
```bash
flutter build apk --release \
  --dart-define=API_BASE_URL=http://217.120.100.76:19283
```

---

## 14. Offline cache

De Android-app moet bruikbaar blijven als internet wegvalt of de backend (tijdelijk) niet bereikbaar is — de gebruiker wil de feed die hij eerder had opgehaald nog steeds kunnen lezen.

### Mechanisme
- Elke geslaagde list-fetch wordt direct naar `SharedPreferences` geserialiseerd onder een key `cache_v1_<username>_<name>`. De cache is **per gebruiker** zodat uitloggen + inloggen als andere user geen vorige cache pakt.
- Bij elke nieuwe `build()` van een notifier (en bij `poll()` waar van toepassing) probeert de app eerst de live API. Faalt dat (DNS-fout, timeout, 5xx, geen verbinding), dan valt hij terug op de gecachete waarde.
- Is er nog niets gecached, dan propageert de fout naar de UI (`AsyncValue.error`) zoals voorheen — de gebruiker ziet dan een foutmelding op het scherm.
- Implementatie: `lib/api/local_cache.dart` met `LocalCache.saveList/loadList/saveObject/loadObject/clearAll`. Notifiers gebruiken de helpers `_fetchListWithCache` en `_fetchObjectWithCache` uit `data_providers.dart` zodat de cache-logica niet in elke notifier herhaald wordt.

### Welke endpoints worden gecached
| Notifier | Endpoint | Cache-naam |
|---|---|---|
| `feedProvider` | `GET /api/feed` | `feed` |
| `rssProvider` | `GET /api/rss` | `rss` |
| `requestProvider` | `GET /api/requests` | `requests` |
| `podcastProvider` | `GET /api/podcasts` | `podcasts` |
| `settingsProvider` | `GET /api/settings` | `settings` |
| `rssFeedsProvider` | `GET /api/rss-feeds` | `rss-feeds` |

Schrijfacties (PUT/POST/DELETE) cachen niet expliciet — ze updaten de in-memory state optimistisch en falen stil bij offline. Bij volgende online refresh komt de juiste server-state weer binnen.

### Cache-leven
- Wordt gewist bij `AuthNotifier.logout()` via `LocalCache.clearAll()` zodat een volgende user geen residue ziet.
- Uninstall van de app verwijdert SharedPreferences automatisch.
- Geen automatische TTL — de cache wordt altijd vervangen door verse server-data zodra die binnenkomt.
