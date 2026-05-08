# Personal News Feed — Backend Functionele Specificatie

> **Doel van dit document:** Een volledige black-box beschrijving van het gedrag van de backend — wat de app doet, niet hoe het gebouwd is. Voor architectuur, patterns, monitoring en testing: zie [`backend-technical-spec.md`](./backend-technical-spec.md).

---

## 1. Overzicht

De backend is een **persoonlijke nieuwsfeed-service** die:
- RSS-feeds ophaalt en met AI samenvat en categoriseert
- Artikelen selecteert voor een persoonlijke feed op basis van gebruikersinteresses
- Ad-hoc zoekverzoeken verwerkt op basis van een opgegeven onderwerp
- Dagelijks een AI-samenvatting genereert van alle nieuwsitems
- Podcasts genereert (script + audio) op basis van recente nieuwsartikelen
- Multi-user: elke gebruiker heeft volledig eigen data en instellingen

**Stack:** REST API + WebSocket, JSON opslag op schijf (geen database), JWT authenticatie, asynchrone achtergrondverwerking. Voor de technische invulling hiervan: zie [`backend-technical-spec.md`](./backend-technical-spec.md).

---

## 2. Dataopslag

### Persistentie
Alle data wordt opgeslagen als JSON-bestanden op het lokale bestandssysteem. Er is geen externe database. De rootmap is configureerbaar via `app.data-dir` (standaard `./data`).

Structuur:
```
data/
  users.json                          # alle gebruikersaccounts
  users/{username}/
    rss_items.json                    # ruwe RSS-artikelen
    feed_items.json                   # gecureerde feed-items
    news_requests.json                # verzoeken (ad-hoc + dagelijkse updates)
    settings.json                     # categorie-instellingen
    rss_feeds.json                    # geconfigureerde RSS-feed URLs
    podcasts.json                     # podcast metadata
    topic_history.json                # onderwerp-geschiedenis per gebruiker
    audio/
      {podcastId}.mp3                 # gegenereerde podcast audio
```

### Concurrency
- Alle achtergrondtaken zijn asynchroon (`@Async`).
- Per-gebruiker vergrendeling voorkomt dat dezelfde gebruiker meerdere RSS-verwerkingen tegelijk uitvoert.
- Maximaal 3 gelijktijdige Claude API-aanroepen (semaphore).

---

## 3. Authenticatie

**Mechanisme:** JWT Bearer token (HS256), geldig 30 dagen. Alle endpoints vereisen een geldig token in de `Authorization: Bearer {token}` header, behalve `/api/auth/**` en `/ws/**`.

**Uitzondering — audio endpoint:** `GET /api/podcasts/{id}/audio` accepteert het JWT token ook als query-parameter `?token=...`. Dit is nodig omdat browser mediaplayers en Flutter's `AudioPlayer` geen `Authorization` header kunnen meesturen bij het streamen van audio. De JWT-filter moet deze query-parameter herkennen en als geldig authenticatiemiddel beschouwen.

**Wachtwoord:** BCrypt gehasht. Minimale lengte: 4 tekens.

**CORS:** Alle origins toegestaan, methoden: GET, POST, PUT, DELETE, OPTIONS.

**Sessies:** Stateless (geen server-side sessies).

---

## 4. Data Modellen

De volledige datamodellen voor de REST API (velden, types, validaties) staan gedefinieerd in **[`openapi.yaml`](./openapi.yaml)** onder `components/schemas`. Dit zijn: `RssItem`, `FeedItem`, `NewsRequest`, `CategoryResult`, `Podcast`, `CategorySettings`, `RssFeedsSettings` en alle bijbehorende enums.

Hieronder staan alleen de **interne modellen** die niet via de API worden blootgesteld.

---

### User
Wordt nooit via de API teruggegeven; `passwordHash` blijft altijd server-side.

| Veld | Type | Beschrijving |
|---|---|---|
| `id` | String (UUID) | Uniek ID |
| `username` | String | Gebruikersnaam (uniek) |
| `passwordHash` | String | BCrypt hash van wachtwoord |

---

### TopicEntry
Onderwerp-geschiedenis per gebruiker. Wordt intern bijgehouden en als context meegegeven aan Claude; nooit direct via de API beschikbaar.

| Veld | Type | Beschrijving |
|---|---|---|
| `topic` | String | Onderwerp (Nederlands) |
| `firstSeen` | String | Eerste keer gezien (ISO-8601) |
| `lastSeenNews` | String? | Laatste keer in nieuws |
| `lastSeenPodcast` | String? | Laatste keer in podcast |
| `newsCount` | Int | Aantal keer in nieuws gezien |
| `podcastMentionCount` | Int | Aantal keer genoemd in podcast |
| `podcastDeepCount` | Int | Aantal keer diepgaand behandeld in podcast |
| `likedCount` | Int | Aantal keer geliket door gebruiker |
| `starredCount` | Int | Aantal keer bewaard door gebruiker |

---

### Systeemcategorie "overig"
De categorie `overig` is een speciale vangnetcategorie: als Claude een artikel niet in een van de gebruikerscategorieën kan plaatsen, krijgt het automatisch categorie `overig`. Deze categorie heeft `isSystem: true` en kan niet verwijderd worden.

---

## 5. API Specificatie

De volledige REST API is gespecificeerd in **[`openapi.yaml`](./openapi.yaml)** (OpenAPI 3.1).

Daarin staan alle endpoints met paden, methoden, request/response bodies, query parameters, statuscodes en datamodellen.

**WebSocket:** `ws://{host}/ws/requests`
- Geen authenticatie vereist
- Alleen server → client (broadcast; berichten van de client worden genegeerd)
- Kapotte verbindingen worden bij de volgende broadcast verwijderd

**Trigger:** bij elke statuswijziging van een `NewsRequest` stuurt de server één bericht naar alle verbonden clients.

**Berichtformaat:** een enkel JSON-object, identiek aan het `NewsRequest` schema uit `openapi.yaml`. Voorbeeld:
```json
{
  "id": "daily-update-robbert",
  "subject": "Dagelijkse update",
  "status": "DONE",
  "isDailyUpdate": true,
  "isDailySummary": false,
  "newItemCount": 7,
  "costUsd": 0.012,
  "durationSeconds": 43,
  "createdAt": "2025-05-08T06:00:00Z",
  "completedAt": "2025-05-08T06:00:43Z",
  "categoryResults": [
    { "categoryId": "kotlin", "categoryName": "Kotlin", "articleCount": 3, "costUsd": 0.004, "searchResultCount": 12, "filteredCount": 3 }
  ]
}
```

De ontvanger (frontend) matcht het bericht op `id` en vervangt het bestaande item in de lokale lijst.

---

## 6. Gedrag & Achtergrondprocessen

### 6.1 Dagelijkse RSS-verwerking (automatisch, elk uur)

Wordt elk uur automatisch uitgevoerd voor elke gebruiker. Handmatig te triggeren via `POST /api/rss/refresh`.

**Pipeline:**
1. Haal alle RSS-feeds op die de gebruiker geconfigureerd heeft (parallel). Filter artikelen ouder dan 4 dagen.
2. Filter artikelen waarvan de URL al bekend is in de opgeslagen RssItems.
3. Voor elk nieuw artikel: vraag Claude om een Nederlandse samenvatting (150-250 woorden), categorie-toewijzing en 2-3 canonieke onderwerpen.
4. Sla alle nieuwe RssItems op (`inFeed: false`).
5. Vraag Claude in één batch-aanroep om per artikel te bepalen of het in de feed hoort (maximaal ~50 artikelen per aanroep; bij meer artikelen worden ze opgesplitst in batches). Er is geen minimum of maximum percentage — als niets interessant genoeg is selecteert Claude niets, als alles interessant is selecteert Claude alles. Context meegegeven: eerder gelikete/gedislikete items, onderwerp-geschiedenis, bestaande feed-items (exclusief dagelijkse samenvattingen).
6. Update `inFeed` en `feedReason` op de geselecteerde RssItems.
7. Voor elk geselecteerd item: vraag Claude om een uitgebreide Nederlandse FeedItem-samenvatting (400-600 woorden).
8. Sla FeedItems op en koppel `feedItemId` terug op de RssItems.
9. Werk onderwerp-geschiedenis bij op basis van alle nieuwe items met topics.
10. Stuur WebSocket updates bij elke statuswijziging.

**Concurrency:** Per-gebruiker lock voorkomt overlappende runs.

---

### 6.2 Dagelijkse samenvatting (automatisch, 06:00)

Elke dag om 06:00 wordt voor elke gebruiker een dagelijkse samenvatting aangemaakt.

**Pipeline:**
1. Verzamel alle FeedItems van de afgelopen 24 uur + alle RssItems van de afgelopen 7 dagen.
2. Stuur dit naar Claude voor een uitgebreid Nederlandstalig dagelijks nieuwsoverzicht in Markdown-formaat (600-1000 woorden).
3. Sla op als FeedItem met `isSummary: true` en ID `daily-summary-feed-{datum}`. Een eventueel bestaand item met hetzelfde ID wordt eerst verwijderd.

---

### 6.3 Ad-hoc verzoek verwerking

Wordt asynchroon gestart bij `POST /api/requests`.

**Pipeline:**
1. Haal RSS-feeds op die relevant zijn voor het opgegeven onderwerp.
2. Filter op datum (`maxAgeDays`) en dedupliceer tegen bestaande RssItem URLs.
3. Vraag Claude welke kandidaat-artikelen het best passen bij het onderwerp.
4. Haal de volledige tekst op van de geselecteerde artikelen via Tavily `/extract`.
5. Vraag Claude voor elk artikel een Nederlandse samenvatting te genereren.
6. Sla elk artikel direct op als FeedItem zodra het beschikbaar is (streaming aanpak).
7. Werk de status bij na elk item; stuur WebSocket updates.
8. Verzoek ondersteunt annulering: als het verzoek geannuleerd wordt, stopt de verwerking bij het eerstvolgende veilige moment.

---

### 6.4 Podcast generatie

Wordt asynchroon gestart bij `POST /api/podcasts`.

**Statusverloop:** `PENDING` → `DETERMINING_TOPICS` → `GENERATING_SCRIPT` → `GENERATING_AUDIO` → `DONE`

**Pipeline:**
1. Haal RSS-feeds op voor de opgegeven periode (`periodDays`).
2. Laad gebruikersfeedback (gelikete/gedislikete/bewaarde artikeltitels) als context.
3. Als geen `customTopics` opgegeven: vraag Claude om een redactioneel onderwerpenplan op te stellen op basis van de RSS-artikelen (Nederlands, journalistiek format).
4. Vraag Claude een Nederlandstalig interviewscript te genereren in INTERVIEWER/GAST-format, afgestemd op de gewenste duur (~140 woorden per minuut). Met of zonder onderwerpenplan, of met `customTopics`.
5. Vraag Claude om 5-10 onderwerpen te extraheren uit het script.
6. Stel de podcasttitel samen: `"DevTalk {N}, {datum} — {onderwerp1}, {onderwerp2}"`.
7. Werk onderwerp-geschiedenis bij (eerste helft van onderwerpen telt als diepgaand behandeld).
8. Genereer audio via de gekozen TTS-provider, regel voor regel:
   - INTERVIEWER-regels → stem A
   - GAST-regels → stem B
   - Segmenten worden aaneengevoegd tot één MP3-bestand
9. Sla MP3 op als `data/users/{username}/audio/{podcastId}.mp3`.

---

### 6.5 Opstartgedrag

Bij serverstart worden alle verzoeken met status `PENDING` of `PROCESSING` gereset naar `FAILED` (herstel na herstart).

Voor elke bestaande gebruiker worden de vaste verzoekrecords `daily-update-{username}` en `daily-summary-{username}` aangemaakt als ze nog niet bestaan.

---

### 6.6 Onderwerp-geschiedenis

De onderwerp-geschiedenis (`topic_history.json`) wordt bijgehouden per gebruiker en bijgewerkt na:
- Elke RSS-verwerking (topics van nieuwe items)
- Elke podcast-generatie (topics uit script)
- Like/dislike feedback (verhoogt/verlaagt relevantiescore)
- Ster-actie (verhoogt starredCount)

Deze geschiedenis wordt als context meegegeven aan Claude bij:
- Feed-selectie (welke onderwerpen zijn recent genoeg behandeld?)
- Podcast onderwerpenplanning (welke onderwerpen verdienen meer aandacht?)

---

## 7. Externe Systemen

### 7.1 Anthropic Claude (AI backbone)

**API:** `https://api.anthropic.com/v1/messages`

**Configuratie:**
- Hoofd-model (podcastscripts, complexe selectie): configureerbaar, bijv. `claude-sonnet-4-5`
- Samenvattingsmodel (per-artikel summaries): configureerbaar, bijv. `claude-haiku-4-5-20251001`
- API-sleutel: omgevingsvariabele `ANTHROPIC_API_KEY`

**Betrouwbaarheid:**
- Maximaal 3 gelijktijdige aanroepen
- Automatische retry met exponentieel backoff (tot 4 pogingen, startend bij 15 seconden) bij HTTP 429 of verbindingsfouten

**Aanroepen en hun doel:**

| Aanroep | Doel | Input | Output |
|---|---|---|---|
| `summarizeRssItem` | Samenvatting + categorie + topics van één RSS-artikel | Titel, snippet, beschikbare categorieën | Samenvatting (150-250 w), categorie-ID, 2-3 topics |
| `selectFeedItems` | Batch-selectie van artikelen voor feed | Lijst van nieuwe artikelen, liked/disliked context, bestaande feed, topic-geschiedenis | Per artikel: wel/niet in feed + reden (geen minimum of maximum) |
| `generateFeedItemSummary` | Uitgebreide samenvatting voor feed | Artikel-URL, titel, ruwe tekst | Samenvatting (400-600 woorden, Nederlands) |
| `generateDailySummaryFromRss` | Dagelijks nieuwsoverzicht | Alle FeedItems (24h) + RssItems (7d) | Markdown-briefing (600-1000 woorden) |
| `selectArticles` | Selecteer beste artikelen voor ad-hoc verzoek | Onderwerp, kandidaatartikelen (titel+snippet) | 0-gebaseerde indices van beste keuzes |
| `summarizeArticle` | Samenvatting ad-hoc artikel na full-text fetch | Volledige artikeltekst | 400-woord Nederlandse samenvatting |
| `extractNewsTopics` | Topics uit één artikel | Artikeltekst | 2-3 canonieke topics (Nederlands) |
| `determinePodcastTopics` | Redactioneel onderwerpenplan voor podcast | RSS-artikelen, topic-geschiedenis, feedback | Nederlandstalig editorial plan |
| `generatePodcastScript` | Volledig podcastscript | Onderwerpenplan of custom topics, artikelen, gewenste duur | INTERVIEWER/GAST-format script (Nederlands) |
| `extractPodcastTopics` | Topics uit podcastscript | Scripttext | 5-10 topics |

---

### 7.2 Tavily (webzoekopdrachten & extractie)

**API:** `https://api.tavily.com`

**API-sleutel:** omgevingsvariabele `TAVILY_API_KEY`

**Gebruikte endpoints:**

| Endpoint | Doel | Input | Output |
|---|---|---|---|
| `POST /search` | Zoek artikelen op onderwerp | Zoekopdracht (Engels, 4-8 woorden, afgeleid van het `subject` veld via Claude of directe vertaling), max_results, days, optioneel domeinfilter | Lijst van {title, url, snippet, publishedDate} |
| `POST /extract` | Haal volledige artikeltekst op | Lijst van URLs | Map van {url → volledige tekst, max 8000 tekens} |

Tavily wordt **alleen** gebruikt voor ad-hoc verzoeken (`POST /api/requests`), niet voor de reguliere RSS-pipeline.

---

### 7.3 OpenAI TTS (tekst-naar-spraak voor podcasts)

**API:** `https://api.openai.com/v1/audio/speech`

**API-sleutel:** omgevingsvariabele `OPENAI_API_KEY`

**Gebruik:**
- Model: `tts-1`
- INTERVIEWER-regels → stem `onyx`
- GAST-regels → stem `alloy`
- Afspeelsnelheid: 1.2x
- Outputformaat: MP3
- Segmenten worden direct aaneengevoegd

**Kosteninschatting:** ~$15 per 1 miljoen tekens

---

### 7.4 ElevenLabs TTS (alternatieve tekst-naar-spraak)

**API:** `https://api.elevenlabs.io/v1/text-to-speech/{voiceId}`

**API-sleutel:** omgevingsvariabele `ELEVENLABS_API_KEY` (optioneel)

**Gebruik:**
- Model: `eleven_multilingual_v2`
- INTERVIEWER-stem: configureerbaar via `app.elevenlabs.voice-interviewer` (standaard: `Jn7U4vF8ZkmjZIZRn4Uk`)
- GAST-stem: configureerbaar via `app.elevenlabs.voice-guest` (standaard: `h6uBOiAjLKklte8hdYio`)
- Stemstabiliteit: 0.5, similarity_boost: 0.75
- ID3v2/v1-tags en Xing/Info VBR-headers worden uit elk segment gestript vóór aaneenvoeging (voorkomt dat mediaspelers stoppen na het eerste segment)

**Kosteninschatting:** ~$0.30 per 1000 tekens

---

### 7.5 RSS-feeds (nieuwsbronnen)

Gewone HTTP GET-requests naar door de gebruiker geconfigureerde RSS-feed URLs.

**Ondersteunde formaten:** RSS 2.0 en Atom

**Verwerking:**
- Parallelle fetch van alle geconfigureerde feeds
- HTML wordt gestript uit snippets (max 1000 tekens)
- Publicatiedatums worden geparsed in diverse formaten
- Artikelen ouder dan 4 dagen worden gefilterd

---

## 8. Configuratie

Alle configuratie via `application.properties` of omgevingsvariabelen.

| Property | Omgevingsvariabele | Standaard | Beschrijving |
|---|---|---|---|
| `server.port` | — | `8080` | Serverpoort |
| `app.data-dir` | — | `./data` | Root voor JSON-opslag en audio |
| `app.jwt.secret` | — | (hardcoded default) | JWT-signeringssleutel (wijzigen in productie!) |
| `app.anthropic.api-key` | `ANTHROPIC_API_KEY` | — | Verplicht |
| `app.anthropic.model` | — | `claude-sonnet-4-5` | Hoofd Claude-model |
| `app.anthropic.summary-model` | — | `claude-haiku-4-5-20251001` | Model voor samenvattingen |
| `app.anthropic.base-url` | — | `https://api.anthropic.com` | — |
| `app.tavily.api-key` | `TAVILY_API_KEY` | — | Verplicht voor ad-hoc verzoeken |
| `app.openai.api-key` | `OPENAI_API_KEY` | — | Verplicht voor OpenAI TTS |
| `app.openai.base-url` | — | `https://api.openai.com` | — |
| `app.elevenlabs.api-key` | `ELEVENLABS_API_KEY` | — | Optioneel (alleen bij ElevenLabs TTS) |
| `app.elevenlabs.base-url` | — | `https://api.elevenlabs.io` | — |
| `app.elevenlabs.voice-interviewer` | — | `Jn7U4vF8ZkmjZIZRn4Uk` | ElevenLabs stem voor interviewer |
| `app.elevenlabs.voice-guest` | — | `h6uBOiAjLKklte8hdYio` | ElevenLabs stem voor gast |

---

## 9. Geplande taken

| Tijd | Taak |
|---|---|
| Elk uur (`0 0 * * * *`) | RSS ophalen en verwerken voor alle gebruikers |
| Dagelijks 06:00 (`0 0 6 * * *`) | Dagelijkse AI-samenvatting genereren voor alle gebruikers |

---

## 10. Foutafhandeling & Grenzen

- **RSS-verwerking:** Als Claude-aanroep mislukt voor één artikel, wordt dat artikel overgeslagen; verwerking gaat door.
- **Podcast:** Bij een fout in een van de stappen wordt de podcast gemarkeerd als `FAILED`.
- **Ad-hoc verzoek:** Bij een fatale fout wordt het verzoek gemarkeerd als `FAILED`.
- **Annulering:** Verzoeken kunnen geannuleerd worden; de verwerking stopt bij het eerstvolgende controlepunt.
- **Restart-herstel:** Bij serverherstart worden openstaande PENDING/PROCESSING verzoeken gereset naar FAILED.
- **Claude rate limiting:** Bij HTTP 429 wordt automatisch gewacht en opnieuw geprobeerd (exponentieel backoff, max 4 pogingen).
