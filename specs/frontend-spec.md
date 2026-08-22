# Personal News Feed — Frontend Specificatie

> **Doel van dit document:** Een volledige black-box beschrijving van de frontend, zodat een AI-model de app volledig opnieuw kan bouwen zonder de originele broncode te zien. De exacte UI-vormgeving hoeft niet gerepliceerd te worden; alle functionaliteit moet aanwezig zijn.

---

## 1. Overzicht

De frontend is een **Flutter-app** (mobile + web) voor het lezen van een persoonlijke AI-nieuwsfeed. De app communiceert met de backend via REST en WebSocket.

**Platform:** Flutter (Dart SDK **^3.9**), ondersteunt iOS, Android en web.

**API-contract:** Alle endpoints, request/response-structuren en datamodellen staan beschreven in **[`openapi.yaml`](./openapi.yaml)** (OpenAPI 3.1). De frontend gebruikt al deze endpoints.

**Base URL:** Default in code `http://localhost:8080` (`String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080')`), configureerbaar via `--dart-define=API_BASE_URL=<url>`; prod-builds zetten `https://news.vdzonsoftware.nl`.

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

De app heeft twee hoofdstaten: **niet ingelogd** (toont LoginScreen) en **ingelogd** (toont een shell met vier tabs).

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
    └── Tab 3: SettingsScreen
            └── navigeer naar → CategoriesScreen        (SF-754)
            │       └── dialog → EditCategoryDialog
            │       └── dialog → AddCategoryDialog
            └── navigeer naar → RssFeedsScreen          (SF-220)
            └── dialog → CleanupDialog
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
- Het scherm valideert zelf niets: elke afwijzing komt van de backend en wordt als één generieke tekst getoond. `AuthNotifier` vangt iedere `ApiException` af als `"Inloggen mislukt (${statusCode})"` (`auth_provider.dart`), ook bij registreren. Sinds SF-2207 kan `register` ook een `400` geven voor een gebruikersnaam die niet aan `^[A-Za-z0-9._-]{3,64}$` voldoet — die is voor de gebruiker dus niet te onderscheiden van een te kort wachtwoord (`400`) of een bezette naam (`409`). Een specifiekere Nederlandse melding per oorzaak is een bewust openstaande verbetering, geen bug
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
- **Titel:** de Nederlandse `titleNl` van het item (door de AI tegelijk met de samenvatting gegenereerd, ~70 tekens). Voor legacy items zonder `titleNl` valt de UI terug op het originele `title`-veld.
- **Bron, relatieve tijd, categorie, datum** uit de bekende velden. De relatieve tijd toont hoe lang geleden het item bij ons binnenkwam (op basis van `createdAt`): "12 minuten geleden", "3 uur geleden", "2 dagen geleden", of een absolute datum (DD-MM-YYYY) na 3 dagen. Toont niets voor legacy items zonder timestamp. Helper: `lib/util/time_format.dart`.
- **Preview:** de `shortSummary` (2 regels Nederlandse plain-text, ~30-50 woorden, eveneens door de AI gegenereerd). Voor legacy items zonder `shortSummary` valt de kaart terug op een afgekapte versie van de lange `summary`. De preview wordt **als Markdown** gerenderd via `MarkdownBody`, zodat `**vet**`, `*cursief*` en `` `code` `` netjes worden opgemaakt — vooral relevant voor de fallback uit de lange summary die volop markdown bevat. Lengte wordt begrensd door eerst de eerste paragraaf te pakken en die af te kappen rond 240 tekens (op woordgrens, met `…`); headers/lijst-bullets worden via een aangepaste `MarkdownStyleSheet` als gewone tekst gerenderd om kaart-overflow te voorkomen.

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

**KAN-60 — show-notes-voorlopige-badge (AC #2):** podcast-kaartjes met `summarySource: 'show_notes'` tonen extra een amberkleurig `📝 voorlopig`-chip met tooltip "Voorlopige samenvatting op basis van de RSS show-notes — het echte transcript wordt op de achtergrond verwerkt." De badge verdwijnt automatisch bij de volgende data-refresh nadat de transcript-fase `summary_source` op `'transcript'` heeft gezet. Voor feeds met `transcribeEnabled=false` blijft de badge permanent staan (de eindgebruiker weet dan dat deze bron altijd op show-notes draait).

**Acties per kaart:** identiek aan Feed (swipe-delete, 👍/👎, ster).

### RssItemDetailScreen
Identiek qua PageView-navigatie en AppBar-acties als FeedItemDetailScreen.

**Feed-status banner:** prominent zichtbaar onder de chips, met groene tint + check-icon als `inFeed: true` ("In persoonlijke feed") of oranje tint + info-icon als `inFeed: false` ("Niet in persoonlijke feed"). Onder de kop staat het volledige `feedReason`-veld met de motivatie van de AI. Als `feedReason` leeg is wordt een fallback-tekst getoond ("Geen reden door AI gegeven (mogelijk nog niet beoordeeld of API-key ontbreekt)") zodat de gebruiker altijd ziet of het item is beoordeeld.

**Topics:** als `topics` niet leeg is worden ze als compacte chips onder de banner getoond.

**Extra acties:**
- **"Meer hierover"-knop:** maakt een nieuw verzoek aan (POST `/api/requests`) met het artikel als bronverwijzing (`sourceItemId`, `sourceItemTitle`). Opent een dialoog om het verzoek te bevestigen.
- Tik op bronnaam: opent `feedUrl` of `url` in externe browser.

### RssPodcastDetailScreen (KAN-62)
Voor RssItems met `mediaType: 'PODCAST'` opent **niet** `RssItemDetailScreen` maar een dedicated podcast-detail-scherm (`rss_podcast_detail_screen.dart`). Deze routing-keuze gebeurt in zowel `rss_screen.dart` (direct op `isPodcast`) als `feed_screen.dart` (FeedItem `isPodcast` → opzoeken van de matchende RssItem via `sourceRssIds` → routeren naar dezelfde screen; fallback op `FeedItemDetailScreen` wanneer de RssItem niet gevonden wordt).

Het scherm gebruikt dezelfde PageView-navigatie en AppBar-acties (👍/👎/⭐/lees), maar de body bevat drie podcast-specifieke secties:

1. **Lange samenvatting** (~400-600 woorden, 3-5 alinea's): gerenderd uit `longSummary`. Bij ontbrekende waarde (nog niet door de uitgebreide AI-prompt verwerkt of niet-gebackfilled): valt terug op `summary` plus een cursieve hint *"Uitgebreide samenvatting wordt op de achtergrond verwerkt"*.
2. **Key takeaways**: bullet-list van `keyTakeaways` (5-10 regels). Sectie wordt verborgen wanneer de lijst leeg is.
3. **Ruw transcript**: `ExpansionTile`, default ingeklapt. Bij eerste uitklap fetcht het scherm `GET /api/rss/{id}/transcript` (lazy — feed-listing transporteert geen 50-90k chars per podcast). Voor `summarySource='show_notes'`-items toont 'ie geen knop maar een placeholder *"Transcript wordt nog verwerkt"* (AC #5).

Onderaan staat een **"🇳🇱 Vertaal & genereer Nederlandse podcast"**-knop (KAN-63) en een **"Origineel afspelen"**-knop die `url` (de MP3) in een externe player opent.

**Translate-knop (KAN-63):** bij openen van het scherm fetcht de body `GET /api/podcast-source/by-rss-item/{rssItemId}` om de bron-aflevering-guid, transcript-lengte en (eventueel) een bestaande vertaling op te halen. De knop heeft drie staten:
- **"🇳🇱 Vertaal & genereer Nederlandse podcast"** (default) — actief zodra het Engelse transcript klaar is (`episodeStatus='DONE'`). Tik opent een dialog met de geschatte kosten (vertaling + TTS in $, 2 decimalen) op basis van de transcript-lengte. Bij "Starten": `POST /api/podcast-source/{episodeGuid}/translate`. HTTP 202 → nieuwe podcast verschijnt bovenaan de Podcast-tab; HTTP 409 → snackbar "Transcript is nog niet klaar voor vertaling".
- **"🇳🇱 Bekijk vertaling"** — zodra er een DONE-vertaling bestaat. Tik navigeert naar `PodcastDetailScreen` van die podcast.
- **"🇳🇱 Bekijk vertaling — in wachtrij… / vertalen… / audio genereren…"** — wanneer de vertaling op de achtergrond loopt. Welke statussen dat zijn staat sinds SF-2123 op één plek: de top-level constante `kPodcastTranslationInProgressStatuses` in `frontend/lib/models/models.dart` (`PENDING` / `TRANSLATING` / `TTS_GENERATING`), gelezen via `EpisodeLookup.translationInProgress` op `translatedPodcastStatus`. Het fase-woord achter het streepje komt uit de `_phaseLabel`-`switch` in `rss_podcast_detail_screen.dart`, die één op één bij die constante hoort (onbekende status → `bezig…`). De detail-pagina pollt zelf elke 4 seconden tot de status `DONE`/`FAILED` is.

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
Verbinding met `ws(s)://{host}/ws/requests?token={jwt}` zodra de verzoeken geladen zijn.

- **Het JWT gaat mee de handshake in** (SF-2165). `requestsWsUrl(token)` in `lib/api/ws_client.dart` bouwt de URL en zet het token als queryparameter (url-gecodeerd) — een browser-WebSocket kan geen `Authorization`-header zetten. Zonder token wordt er níet verbonden: `requestsWsUrl` geeft dan `null` en ook `connect(null)` opent geen socket. De backend weigert een tokenloze of ongeldige handshake met `401`.
- **Elke reconnect gebruikt hetzelfde token** als waarmee de socket is opgezet; `RequestsWebSocket` onthoudt het bij `connect()`.
- **Token-reactief bij in- en uitloggen:** `RequestNotifier.build()` doet `ref.watch(authProvider.select((s) => s.token))`. Verandert het token (login, logout, andere gebruiker), dan bouwt Riverpod de provider opnieuw op: de oude socket sluit via `onDispose` en er wordt met het token van de nu ingelogde gebruiker opnieuw verbonden. `AuthNotifier.logout()` maakt daarvoor alleen de auth-state leeg — een expliciete `ref.invalidate(requestProvider)` kan niet meer, want `requestProvider` hangt nu van `authProvider` af en Riverpod ziet dat als een circulaire afhankelijkheid.
- Inkomende berichten zijn JSON-objecten. De handler onderscheidt twee types:
  - **`{"type": "serverVersion", "sha": "...", "buildTime": "..."}`** — wordt direct na (re)connect verstuurd. De `RequestNotifier` filtert dit bericht eruit en geeft het door aan `versionProvider` (zie sectie 9 — *Versie-check & snackbar*).
  - **`NewsRequest`-objecten** (geen `type`-veld) conform het schema uit `openapi.yaml` (zie ook de berichtspecificatie in `backend-functional-spec.md` sectie 5).
- De verbinding levert sinds SF-2165 **alleen updates van de ingelogde gebruiker**; de server filtert per eigenaar. Het id-protocol hieronder blijft staan als **vangnet**, niet langer als privacymaatregel:
  - Bij **bekend ID** in de lokale lijst: vervang het item in place.
  - Bij **onbekend ID**: doe een stille herlaad van de volledige verzoeklijst via `GET /api/requests`. Een onbekend id is nu geen verzoek van iemand anders meer, maar een eigen verzoek dat nog niet in de lijst staat (aangemaakt tijdens een herlaad of op een ander toestel); de herlaad zorgt dat het alsnog verschijnt. Voeg het item nog steeds níet rechtstreeks toe op basis van het WebSocket-bericht — de JWT-gescoopte `GET /api/requests` blijft de bron van waarheid voor de lijst.
- Bij status DONE of CANCELLED: automatisch RSS-items en feed-items herladen (nieuwe artikelen kunnen zijn binnengekomen)
- Bij verbrekingsfout: automatisch herverbinden na 5 seconden — ook een door de backend geweigerde handshake (`401`) valt in die lus; er komt bewust geen backoff of foutmelding in de UI bij
- Verbinding verbreken bij uitloggen (volgt uit de token-watch hierboven) en opnieuw opzetten bij de volgende login, met het token van de dán ingelogde gebruiker

### Ad-hoc "Meer hierover"-verzoeken
Vanuit de RSS-item-detailpagina kan de gebruiker met **Meer hierover** een ad-hoc verzoek aanmaken (`POST /api/requests` met `sourceItemId`/`sourceItemTitle`). De UI toont alleen een bevestigingstoast — er is geen aparte lijst meer waarin deze verzoeken zichtbaar zijn. De resultaten verschijnen vanzelf in de feed wanneer de backend het verzoek heeft verwerkt.

---

## 8. Podcast-tab (Tab 2)

Toont gegenereerde podcasts: `GET /api/podcasts`.

### PodcastCard (in de lijst)
Toont: podcastnummer, titel, datum, duur, status, TTS-provider.

**Visuele progress-indicatie:** zolang de podcast een bezig-status heeft (de gedeelde `kPodcastInProgressStatuses`, zie [Podcast-polling](#podcast-polling)), vervangt een `CircularProgressIndicator` het podcasts-icoon, en wordt het Nederlandse statuslabel ("In wachtrij…", "Onderwerpen bepalen…", "Script schrijven…", "Audio genereren…", "Vertalen…") in primaire kleur en bold getoond. Bij `FAILED` toont een rood error-icon en label "Mislukt".

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
Toont: titel, periode, duur, TTS-provider, onderwerp-chips, volledig audiospeler-paneel.

**KAN-63 — vertaling-modus:** wanneer `podcast.isTranslation` (d.w.z. `translatedFromEpisodeGuid != null`) staat er onder de status-chips een chip "Vertaald van \<feed-naam\>" met tap-actie die terugnavigeert naar de bron `RssPodcastDetailScreen` (lookup via rssProvider op `translatedFromRssItemId`). Bij status `FAILED` toont het scherm bovenaan een rode foutbox met `errorMessage`. De detail-pagina pollt elke 4 seconden zolang de status in de gedeelde `kPodcastInProgressStatuses` staat (dus ook `TRANSLATING` / `TTS_GENERATING`; zie [Podcast-polling](#podcast-polling)) en switcht automatisch naar de audiospeler zodra `DONE`.

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
Zolang een of meer podcasts een "bezig"-status hebben, wordt elke 4 seconden GET
`/api/podcasts` opnieuw aangeroepen; zodra geen enkele podcast meer bezig is
(alles `DONE`/`FAILED`) wordt de timer gestopt.

De bezig-statussen staan op één plek: de top-level constante
`kPodcastInProgressStatuses` in `frontend/lib/models/models.dart` met
`PENDING`, `DETERMINING_TOPICS`, `GENERATING_SCRIPT`, `GENERATING_AUDIO`,
`TRANSLATING` en `TTS_GENERATING`. Zowel de spinner/het statuslabel in de lijst
als de poll-timer van het overzicht én het detailscherm lezen die set (SF-2066).
Daarvóór had `_maybePoll` een eigen, kortere lijst zonder de vertaalstatussen,
waardoor een podcast in `TRANSLATING` wél een draaiend rondje "Vertalen…" kreeg
maar het scherm zichzelf niet meer ververste — het rondje bleef eindeloos
draaien tot je handmatig verversde.

> `EpisodeLookup.translationInProgress` (`PENDING` / `TRANSLATING` /
> `TTS_GENERATING`, gelezen van `translatedPodcastStatus`) is bewust een
> smallere, eigen lijst voor uitsluitend de vertaalflow van één RSS-aflevering
> — een vertaling doorloopt nooit de generatie-statussen — en hoort níet
> vervangen te worden door de gedeelde set. Sinds SF-2123 staat die lijst als
> tweede top-level constante `kPodcastTranslationInProgressStatuses` naast
> `kPodcastInProgressStatuses`, met een test die vastlegt dat hij een
> deelverzameling van de gedeelde set blijft.

> **Belangrijk:** poll-fetches mogen de provider níet via `invalidate()` resetten — dat zou de `AsyncData` voor 1-2 frames terugzetten naar `loading` en de progress-indicator op de kaart laten flikkeren. Implementatie: een aparte `poll()` notifier-methode die de lijst stilletjes ophaalt en de state vervangt zonder eerst `AsyncLoading` te zetten.

---

## 9. Settings-tab (Tab 3)

Sectievolgorde op het scherm: Weergave → Over deze app → Account → Categorieën → RSS feeds → Achtergrond-taken → Opruimen → Debug → (alleen admins) Beheer. De subsecties hieronder staan functioneel gegroepeerd en volgen niet strikt die schermvolgorde.

### Weergave (bovenste sectie, SF-1768)
Staat als **eerste sectie bovenaan** het instellingen-scherm, boven "Over deze app", zodat de schakelaar direct zichtbaar is zonder scrollen. Tussen SF-809 en SF-1768 stond dit blok juist als laatste sectie onderaan (ná de admin-only Beheer-sectie); daarvóór stond het bovenin, direct onder Account (zie SF-987/SF-1046 voor de sectiekop-tekst). SF-1768 is puur een herschikking van de sectievolgorde — het gedrag van de switch is ongewijzigd, en de onderlinge volgorde van alle overige secties is gelijk gebleven.

- Lettergrootte-instelling: "Normaal" of "Groot" (`SwitchListTile` "Grote tekst")
- "Groot" schaalt alle tekst met factor ~1.38 (via `TextScaler` op `MediaQuery`)
- Waarde komt uit `appearanceProvider`; aanpassen roept `setLarge` aan
- Instelling opgeslagen in `SharedPreferences`

### Account (SF-1046)
Sectiekop boven het account-blok heet sinds SF-1046 weer **"Account"**; tussen SF-987 en SF-1046 was dit tijdelijk "Account Settings" (letterlijk Engels overgenomen, ook al is de rest van de pagina Nederlandstalig).

- Gebruikersnaam weergeven
- **Uitloggen:** wist token, navigeer naar LoginScreen
- **Wachtwoord wijzigen:** `ListTile` opent een dialoog met drie velden ("Huidig wachtwoord", "Nieuw wachtwoord" met helper "Min. 4 tekens", "Nieuw wachtwoord bevestigen"); opslaan stuurt `PUT /api/account/password`. De dialoog valideert eerst zelf (leeg veld, nieuw wachtwoord korter dan 4 tekens, bevestiging ongelijk) en toont de fout als rode regel onder de velden. Komt het verzoek wél bij de backend, dan wordt **401** apart afgevangen en vertaald naar "Huidig wachtwoord klopt niet" — precies de melding die de backend zelf teruggeeft en die sinds SF-2186 ook in `openapi.yaml` staat; elke andere status valt terug op "Fout: {code}". Bij succes sluit de dialoog en verschijnt de snackbar "Wachtwoord gewijzigd"

### Categorieën (navigatie-tile, SF-754)
Eén `ListTile` (`Icons.category`, titel "Categorieën", `Icons.chevron_right`) die via `MaterialPageRoute` naar de **Categorieën-subpagina** (`CategoriesScreen`, zie §9b) navigeert. De volledige categorieënlijst stond vóór SF-754 inline uitgeklapt op deze pagina; ze is nu naar de subpagina verplaatst zodat de Settings-tab korter blijft — hetzelfde patroon als de RSS-feeds-tile (SF-220).

### RSS feeds (navigatie-tile, SF-220, tekst "RSS feeds" sinds SF-1077/SF-1085)
Eén `ListTile` (`Icons.rss_feed`, titel "RSS feeds", subtitle "RSS feeds en podcast-bronnen beheren", `Icons.chevron_right`) die via `MaterialPageRoute` naar de **RSS-feeds-subpagina** (`RssFeedsScreen`, zie §9a) navigeert. De inline-editors voor RSS-feeds en podcast-bronnen stonden vóór SF-220 rechtstreeks op deze pagina; ze zijn nu naar de subpagina verplaatst zodat de Settings-tab korter blijft. Patroon is consistent met de bestaande Beheer-tiles (API-log/Admin). Vóór SF-1077 stond hier de tekst "RSS-feeds" (met koppelteken); alleen op deze Settings-tab gecorrigeerd naar "RSS feeds" — de subpagina (§9a) gebruikt bewust nog de oude schrijfwijze, buiten scope van SF-1077.

### Achtergrond-taken
Twee handmatige triggers voor de scheduled jobs (die zelf gewoon doorlopen op hun schedule — hourly RSS-refresh en de daily summary om 06:00):

- **"RSS feeds nu vernieuwen"** (vóór SF-1077: "RSS-feeds nu vernieuwen") — stuurt `POST /api/requests/{hourly-update-{username}}/rerun`.
- **"Genereer dagelijkse samenvatting nu"** — stuurt `POST /api/requests/{daily-summary-{username}}/rerun`.

Gedrag per rij:

- Status (`PENDING` / `PROCESSING` / `DONE`) wordt afgeleid uit de live `requestProvider` (gevoed door REST + WebSocket).
- Zolang de bijbehorende job `PENDING` of `PROCESSING` is, is de knop **disabled** met tooltip *"Loopt al…"* en toont een kleine `CircularProgressIndicator` in plaats van het play-icoon.
- Wanneer de status overgaat naar `DONE` terwijl Settings open staat, verschijnt een snackbar **"Klaar — N items verwerkt"** (één keer per transitie, op basis van `newItemCount` uit het verzoek).
- De vaste records bestaan altijd zodra `ensureFixedRequests` heeft gedraaid; bij ontbreken (eerste login zonder server-roundtrip) zijn de knoppen disabled.

### Over deze app
Direct onder de Weergave-sectie, bovenin het instellingen-scherm, staat een blok **Over deze app** met twee regels:

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

### Debug
Sectie **"Debug"** met één `ListTile` "API-log" (subtitle "Laatste calls + status (voor debugging)") die via `MaterialPageRoute` naar `ApiLogScreen` navigeert. Dat scherm toont de laatst uitgevoerde API-calls met statuscode en eventuele foutdetails (kopiëren/wissen).

### Beheer (alleen admins)

Onderaan het instellingen-scherm verschijnt een extra sectie **"Beheer"** die alleen zichtbaar is voor gebruikers met de rol `admin`.

De rol komt uit het `role`-veld van de register-/login-response (`AuthResponse`, waarden `user` of `admin`; sinds SF-2130 ook zo in `openapi.yaml` gedocumenteerd). `AuthNotifier` bewaart hem in `SharedPreferences` naast token en username, en `AuthState.isAdmin` (`role == 'admin'`) gate't deze sectie. Ontbreekt het veld in de response — een oudere backend — dan valt de client bewust terug op `'user'`: de Beheer-sectie blijft dan verborgen in plaats van dat de UI crasht. Daarom is `role` in het contract ook géén `required`-veld.

- **Beheer gebruikers-knop:** navigeert via `Navigator.push` naar AdminScreen.
  - AdminScreen toont alle gebruikers met hun rollen.
  - Per gebruiker: wachtwoord resetten, promoveren naar admin, degraderen naar user, verwijderen.
  - Mislukt een beheeractie, dan toont een snackbar de **Nederlandse servermelding** uit het `error`-veld van de foutbody, gelezen via de gedeelde helper `extractDutchMessage` (`lib/api/api_client.dart`) — dus niet de rauwe responsebody en niet de HTTP-statuscode (SF-2242). Bij een lege body verschijnt de fallback "Actie mislukt"; bij een body zonder `error`-veld de body zelf.
    - Anders dan bij de lijst-editors in §9a/§9b is dit een **neutrale** snackbar (`_snack` zet geen `backgroundColor`), en er is geen statuscode-filter: élke `ApiException` wordt geëxtraheerd, omdat een beheeractie op meerdere manieren kan falen (400/401/403/404/500) en álle backend-fouten dezelfde `{"error": …}`-vorm hebben.
    - **Twee losse foutoppervlakken, niet verwarren:** bovenstaande geldt voor de *acties*. Faalt het **laden** van de gebruikerslijst zelf, dan komt dat uit de `error:`-tak van `usersAsync.when` — dat is een `AsyncError` zonder responsebody en die toont onveranderd "Fout: …" in de body van het scherm. Alleen de acties hebben een `ApiException` met een servermelding erin.
    - **Bekende openstaande verbetering:** niet alle admin-meldingen van de backend zijn Nederlands. `AdminServiceImpl` gooit bij een onbekend account `NotFoundException("User not found: <naam>")`, dus die Engelse zin komt nu letterlijk in de snackbar (voorheen als JSON-fragment — dus wél een verbetering). Vernederlandsen is backendwerk en viel buiten SF-2242.

- **Beheer kosten-knop:** navigeert via `Navigator.push` naar AdminCostsScreen.
  - AdminCostsScreen toont kostenoverzichten per dag, per gebruiker en gedetailleerd logboek van externe API-calls.

Er is geen aparte Admin-tab in de bottom navigation bar — alle admin-functionaliteit zit achter de twee Beheer-knoppen in Settings.

---

## 9a. RSS-feeds-subpagina (RssFeedsScreen, SF-220)

Aparte subpagina (`frontend/lib/screens/rss_feeds_screen.dart`) met een eigen `AppBar` (titel "RSS-feeds"), bereikbaar via de navigatie-tile in de Settings-tab (§9). Bundelt het beheer van gewone RSS-feeds én podcast-RSS-bronnen op één scherm; de editors en hun gedrag zijn ongewijzigd t.o.v. de oude inline-secties op de Settings-tab. Puur frontend-herstructurering — geen backend-, API- of providerwijzigingen.

### RSS-feeds (sectie)
Lijst van geconfigureerde RSS-feed URLs uit `GET /api/rss-feeds` (`rssFeedsProvider`), met de gebruikelijke loading-spinner en error-tekst "Fout: …".

- **Tik op URL:** opent URL (monospace-weergave) in externe browser
- **Verwijder-icoon (×):** verwijder feed-URL, PUT `/api/rss-feeds` (`rssFeedsProvider.save`); tijdens het opslaan is de knop uitgeschakeld. Weigert de server, dan blijft de feed in de lijst staan en verschijnt een rode snackbar.
- **Invoerveld + toevoegen-knop:** nieuwe URL toevoegen met synchrone server-side URL-validatie (SSRF-check), PUT `/api/rss-feeds`. Tijdens het opslaan is het invoerveld uitgeschakeld en staat er een spinner op de plek van de **+**-knop. Bij een afwijzing (HTTP 400) verschijnt een **rode snackbar** met de Nederlandse foutmelding uit het `error`-veld van de responsbody, wordt de URL **niet** aan de lijst toegevoegd en behoudt het invoerveld de ingetypte tekst (SF-1552).

### Podcast-bronnen (sectie, KAN-56)
Lijst van podcast-RSS-bronnen uit `GET /api/podcast-feeds` (`podcastFeedsProvider`), eveneens met loading-spinner en "Fout: …"-afhandeling.

- **Tik op URL:** opent de bron in externe browser
- **"Transcriberen aan/uit"-toggle:** per bron schakelbaar; staat de toggle uit, dan valt de backend terug op de show-notes als input voor de AI-samenvatting (zonder Whisper-kosten).
- **Verwijder-icoon:** verwijder bron, PUT `/api/podcast-feeds`
- **Invoerveld + toevoegen-knop:** nieuwe URL toevoegen met synchrone server-side URL-validatie; een ongeldige URL geeft een rode snackbar met de Nederlandse foutmelding uit het `error`-veld van de responsbody (AC #7). Opslaan via `podcastFeedsProvider.save`.

---

## 9b. Categorieën-subpagina (CategoriesScreen, SF-754)

Aparte subpagina (`frontend/lib/screens/categories_screen.dart`) met een eigen `AppBar` (titel "Categorieën"), bereikbaar via de navigatie-tile in de Settings-tab (§9). Bevat de volledige categorieënlijst uit `GET /api/settings` (`settingsProvider`), met de gebruikelijke loading-spinner en error-tekst "Fout: …".

Alle vier de mutaties (schakelaar, toevoegen, bewerken/opslaan, verwijderen) lopen via één gedeelde opslag-route met hetzelfde faalcontract als de RSS-feeds-editor (§9a, SF-1851): de lijst muteert **pas ná een geslaagde** PUT `/api/settings`, tijdens het opslaan is de bediening (schakelaars, bewerk-icoon en de "Categorie toevoegen"-tile) uitgeschakeld, en weigert de server (of is de backend onbereikbaar), dan blijft de lijst — en de lokale cache — ongewijzigd en verschijnt een **rode snackbar** met de Nederlandse foutmelding uit het `error`-veld van de responsbody (bij een andere fout een generieke melding "Fout bij opslaan: …").

**Per categorie** (`SwitchListTile`):
- **Schakelaar (enabled/disabled):** opslaan via `settingsProvider.notifier.save(...)` (PUT `/api/settings` met bijgewerkte lijst); het schakelaartje verspringt pas nadat de PUT geslaagd is.
- **Bewerk-icoon (potlood):** opent EditCategoryDialog
  - Naam wijzigen
  - Extra AI-instructies wijzigen
  - Opslaan via `settingsProvider.notifier.save(...)`; bij een fout blijven de oude naam/instructies in de lijst staan
  - Verwijderen (knop): categorie verwijderd en opgeslagen; bij een fout blijft de categorie in de lijst staan
- **Systeemcategorieën** (`isSystem: true`) tonen de subtitel "Systeem" en hebben geen bewerk-/verwijderoptie.

**Categorie toevoegen:** `ListTile` "Categorie toevoegen" opent AddCategoryDialog
- Naam invoeren
- Opslaan via `settingsProvider.notifier.save(...)` met nieuwe categorie toegevoegd (ID gegenereerd op basis van naam); bij een fout verschijnt de categorie niet in de lijst

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
| `requestProvider` | Verzoeken + WebSocket-updates (gebruikt door Settings → Achtergrond-taken voor knop-state en klaar-toast); watcht het JWT uit `authProvider` en zet bij elke tokenwissel een nieuwe geauthenticeerde WebSocket op (zie §7) |
| `settingsProvider` | Categorie-instellingen (gebruikt door CategoriesScreen, §9b) |
| `rssFeedsProvider` | RSS-feed URLs (gebruikt door RssFeedsScreen, §9a) |
| `podcastFeedsProvider` | Podcast-RSS-bronnen + transcribe-toggle (KAN-56; gebruikt door RssFeedsScreen, §9a) |
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
| Backend URL | `--dart-define=API_BASE_URL=https://...` bij build/run | `http://localhost:8080` (prod-builds: `https://news.vdzonsoftware.nl`) |
| App-icoon | `flutter_launcher_icons` in `pubspec.yaml` (zie hieronder) | — |

### App-icoon
Het icoon is **`assets/app_icon.png`** (1024×1024, indigo achtergrond met witte feed-lijnen + RSS-arc, gegenereerd met `tools/make_icon.py` of vergelijkbaar). De `flutter_launcher_icons`-config in `pubspec.yaml` schaalt 'm naar alle Android-, iOS- en web-formaten:
- Android: alle `mipmap-*` resoluties + adaptive-icon (`mipmap-anydpi-v26`) met `#3F51B5` (indigo) als achtergrond
- iOS: `Assets.xcassets/AppIcon.appiconset` (alpha verwijderd)
- Web: `web/favicon.png` en `web/icons/Icon-{192,512,maskable-192,maskable-512}.png`, met `#3F51B5` als achtergrond voor de maskable-varianten (zelfde safe-zone-conventie als de Android adaptive icon)

Genereren na een wijziging in `assets/app_icon.png`:
```
flutter pub get
flutter pub run flutter_launcher_icons
```

`web/manifest.json` (`theme_color`/`background_color`) volgt dezelfde `#3F51B5`-kleur en wordt niet automatisch door `flutter_launcher_icons` bijgewerkt — handmatig in lijn houden bij een kleurwijziging.

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

### Tests draaien

De app heeft tests onder `frontend/test/`: widget-tests (`widget_test.dart`,
`main_shell_test.dart`, `settings_screen_test.dart`, `rss_feeds_screen_test.dart`,
`categories_screen_test.dart`, `admin_screen_test.dart`) en unittests
(`ws_client_test.dart`, `auth_logout_ws_test.dart`,
`podcast_in_progress_statuses_test.dart`, `models_test.dart`) — samen **49 tests**
(SF-2263 bracht er 9). `ws_client_test.dart` (SF-2166) legt
de vorm van de WebSocket-URL vast: het token komt er url-gecodeerd als
queryparameter `token` op, `http(s)` wordt `ws(s)`, en zonder token wordt er geen
URL gebouwd én geen verbinding geopend. `auth_logout_ws_test.dart` (SF-2166) is
de regressietest voor de gebruikerswissel: op de échte `RequestNotifier`, met een
`container.listen` die de eager rebuild van het instellingenscherm nabootst,
levert login A → logout → login B achtereenvolgens het token van A, `null` en het
token van B op als verbindingstoken. `podcast_in_progress_statuses_test.dart`
(SF-2066) legt vast welke zes
statussen de gedeelde `kPodcastInProgressStatuses` bevat, zodat spinner en
poll-timer niet opnieuw uit elkaar kunnen lopen; sinds SF-2123 legt hij ook
vast dat `kPodcastTranslationInProgressStatuses` exact de drie vertaalstatussen
bevat én een *echte* deelverzameling van de gedeelde set blijft — die laatste
assertie is de vangrail die omvalt zodra de twee lijsten uiteenlopen.

`models_test.dart` (SF-2263) is de eerste test op het **JSON-contract** van
`lib/models/models.dart` en dekt de twee modellen die door de feed- en
RSS-schermen stromen. Vier dingen liggen er nu vast. (1) `FeedItem.fromJson`
met een volledige payload: alle twintig velden landen op het juiste veld. De
veldnamen komen één-op-één uit het DTO dat de backend serialiseert
(`newsfeedbackend/.../feed/api/dto/FeedItemDto.kt`); `summary`, `isRead` en
`isSummary` staan daar met een expliciete `@JsonProperty` — die drie staan
daarom hardgecodeerd in de testdata en niet afgeleid van de Kotlin-veldnaam.
`createdAt` is backend-side een `Instant` en komt als ISO-8601-**string** over
de lijn. (2) De terugvallen bij een lege payload, voor `FeedItem` én `RssItem`:
`category` = `'overig'`, `mediaType` = `'ARTICLE'`, `summarySource` =
`'transcript'`, lege lijsten voor `topics`/`sourceRssIds`/`sourceUrls`/
`keyTakeaways` — en `url`/`liked`/`imageUrl`/`durationSeconds` blijven bewust
`null`, die hebben géén terugval. (3) Beide takken van `listPreview`
(`models.dart:61`): een gevulde `shortSummary` wint, anders valt hij terug op
`summary` met samengevouwen en getrimde witruimte. (4) De drietrapswaarde
`liked` in `copyWith` (`models.dart:87`/`:210`, sentinel op `:102`/`:225`):
`copyWith(isRead: true)` laat `liked` staan, `copyWith(liked: false)` zet
`false`, en `copyWith(liked: null)` zet hem écht op `null` in plaats van hem te
laten staan. Dat laatste is de hele reden dat de `_Sentinel`-constructie
bestaat — `data_providers.dart:92`/`:154` gebruiken het voor de optimistische
UI-update van duim-omhoog/duim-omlaag/geen-mening — en het is de assertie die
bij een "opruimende" refactor van `Object? liked = const _Sentinel()` naar
`bool? liked` stil omslaat. Schrijf `liked: null` daarom altijd **letterlijk in
de aanroep**; via een tussenvariabele duikt de sentinel-default weer op en
bewaakt de test niets.

De resterende vijf modellen (`PodcastFeed`, `CategorySettings`, `NewsRequest`,
`Podcast`, `EpisodeLookup`) zijn nog ongedekt; dat is de logische tweede stap nu
het patroon staat. Openstaand punt dat SF-2263 bewust **niet** repareerde:
`NewsRequest.fromJson` (`models.dart:337`) leest
`j['isHourlyUpdate'] ?? j['isDailyUpdate'] ?? false`, terwijl `isDailyUpdate`
nergens meer in de backend of in `specs/openapi.yaml` voorkomt
(`JacksonConfig.kt:27` documenteert die hernoeming juist als reden om onbekende
velden te negeren). Dode terugvalcode — opruimen of bewust bewaren als
bescherming tegen oude, lokaal gecachete JSON is stof voor een aparte story.

```bash
cd frontend
flutter pub get
flutter test
```

`flutter test` draait op de Dart-VM — geen Docker, JDK of Android-toolchain nodig.
Sinds SF-1952 dwingt CI deze suite af via `.github/workflows/frontend-tests.yml`,
bij elke pull request en elke push naar `main` die `frontend/**` of
`frontend-reader/**` raakt. De workflow heeft twee losse jobs (deze app en de
reader-app), zodat de uitslag per app zichtbaar blijft ook als er één faalt; hij
pint Flutter `3.35.0`, dezelfde versie als de APK-builds. Zie
`docs/factory/development.md` voor de commando's van beide apps naast elkaar.

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
| `podcastFeedsProvider` | `GET /api/podcast-feeds` | `podcast-feeds` |

De meeste schrijfacties (PUT/POST/DELETE) cachen niet expliciet — ze updaten de in-memory state optimistisch en falen stil bij offline. Bij volgende online refresh komt de juiste server-state weer binnen.

Uitzondering zijn de drie lijst-editors: `rssFeedsProvider.save` en `podcastFeedsProvider.save` uit §9a (SF-1552) en `settingsProvider.save` uit §9b (SF-1851). Ze schrijven de nieuwe lijst na een geslaagde PUT ook zelf naar de cache (`rss-feeds`, `podcast-feeds` resp. `settings`) en muteren de state **pas daarna**. Ze zijn dus niet optimistisch en falen niet stil: gaat de PUT mis, dan blijven state én cache ongewijzigd en propageert de `ApiException` naar het scherm, dat er een rode snackbar van maakt.

### Cache-leven
- Wordt gewist bij `AuthNotifier.logout()` via `LocalCache.clearAll()` zodat een volgende user geen residue ziet.
- Uninstall van de app verwijdert SharedPreferences automatisch.
- Geen automatische TTL — de cache wordt altijd vervangen door verse server-data zodra die binnenkomt.
