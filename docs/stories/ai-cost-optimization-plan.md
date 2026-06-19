# KAN-XX — AI-kosten optimaliseren via configureerbare modelkeuze

## Story

**Als** beheerder van de news-feed
**wil ik** per actie in een configuratiebestand kunnen instellen welk AI-model
wordt gebruikt, met een per-call kostenberekening op basis van een
(handmatig bij te werken) prijstabel
**zodat** ik goedkopere modellen kan inzetten waar dat geen kwaliteit kost, en
modellen later eenvoudig kan wisselen zonder code te wijzigen.

## Aanleiding

Het in-app dashboard meldt ~€1/dag, maar de echte facturen (Anthropic ~$12/wk,
OpenAI ~$6/wk) wijzen op ~$2,3/dag. Daarnaast staan modelkeuzes nu hard in de
code verspreid (mix van Sonnet/Haiku). Door de modelkeuze per actie naar config
te halen én goedkopere modellen te kiezen voor niet-kwaliteitsgevoelige taken kan
de kost fors omlaag.

**Uitgangspunten (bewust gekozen):**
- Kosten worden **per call** berekend uit een prijstabel in de code/config. De
  provider-Cost-API's vallen af (geven alleen geaggregeerde dag-totalen, en de
  API-key wordt gedeeld met andere projecten). Een af en toe verouderd bedrag dat
  handmatig wordt bijgewerkt is acceptabel.
- De koppeling **actie → model** komt in een configuratiebestand, zodat wisselen
  later triviaal is.

## Huidige situatie (bevindingen uit code + DB)

Web search loopt altijd via **Tavily** (geen native Anthropic web-search). De
hoofd-RSS-feed zoekt niet (RSS direct ophalen, gratis); Tavily wordt alleen
gebruikt in de events-pipeline. Transcriptie + TTS draaien al op OpenAI; daarom
kan alles naar OpenAI maar niet alles naar Anthropic.

## Kosten & doel-modellen (per week, op basis van 28 dagen DB-data)

Onderstaande "aanbevolen model"-kolom is meteen de **default-config** voor deze story.

| Onderdeel (actie) | Runs/wk | Tokens/wk (in→uit) | DB-berekening | Geschat werkelijk¹ | Aanbevolen model | Kosten nieuw² | Reden |
|---|---|---|---|---|---|---|---|
| `podcast_transcribe` | 12,5 (≈469 min) | — | $2,69 | ~$4,6 | **gpt-4o-mini-transcribe** | $1,41 | Moet transcriberen; goedkoopste capabele model (~½ prijs) |
| `event_video_discovery` | 181,5 | 355k→43k | $1,71 | ~$4,1 | **GPT-5.4-mini** | $0,46 | Puur JSON-extractie; mini volstaat. Grootste Claude-post |
| `feed_summarize` | 71,8 | 128k→63k | $1,34 | ~$3,2 | **GPT-5.4-mini** | $0,38 | Samenvatten/vertalen, lage complexiteit |
| `event_discovery` | 68,8 | 98k→23k | $0,56 | ~$1,3 | **GPT-5.4-mini** | $0,18 | JSON-extractie uit zoekresultaten |
| `daily_summary` | 14 | 48k→22k | $0,47 | ~$1,1 | **GPT-5.4** (niveau behouden) | $0,45 | Moet conclusies trekken → capabel model |
| `feed_score` | 64 | 79k→7k | $0,34 | ~$0,8 | **GPT-5.4-mini** | $0,09 | Scoren/ranken, niet kwaliteitsgevoelig |
| `podcast_episode_summarize` | 24 | 161k→25k | $0,29 | ~$0,7 | **GPT-5.4-mini** | $0,23 | Iets complexer dan RSS → mini |
| `rss_summarize` | 75,5 | 39k→26k | $0,17 | ~$0,4 | **GPT-5.4-mini**³ | $0,15 | Bulk EN→NL samenvatting, lage complexiteit |
| `event_video_summarize` | 0,3 | 2k→1k | $0,02 | ~$0,04 | **GPT-5.4** | $0,02 | Lange NL-samenvatting, kwaliteit; verwaarloosbaar |

**Besparing**

| | DB-berekening | Geschat werkelijk¹ |
|---|---|---|
| Nu | $7,59/wk | ~$16,3/wk (~$2,3/dag) |
| Na migratie | $3,37/wk | ~$7/wk |
| **Besparing** | **−$4,2/wk (−56%)** | **~−$9/wk (~−$39/mnd)** |

¹ DB-schatting × onderschattingsfactor uit factuur-reconciliatie (Anthropic ~2,4×,
OpenAI ~1,7×). Schatting; wordt nauwkeuriger zodra de prijstabel per model klopt.
² Echte tokens × actuele OpenAI-tarieven (GPT-5.4 $2,50/$15, mini $0,75/$4,50,
nano $0,20/$1,25 per 1M). GPT-5.4-tarieven officieel bevestigd; legacy-tarieven
(whisper-1, gpt-4o-mini, tts-1) deels via aggregators → verifiëren.
³ Kan nóg goedkoper met gpt-4o-mini (~$0,02/wk) als NL-kwaliteit acceptabel blijkt.

---

## Subtaken

### 1. Configuratiebestand voor actie → model
- [ ] `@ConfigurationProperties("app.ai.models")` met een `Map<actie, modelId>`,
      gevoed vanuit `application.properties`/yaml. Eén regel per actie, bv.:
      ```
      app.ai.models.rss_summarize=gpt-5.4-mini
      app.ai.models.feed_score=gpt-5.4-mini
      app.ai.models.daily_summary=gpt-5.4
      app.ai.models.podcast_transcribe=gpt-4o-mini-transcribe
      ...
      ```
- [ ] Defaults conform de tabel hierboven; per env (`PNF_AI_MODEL_*`) overschrijfbaar.
- [ ] Alle bestaande hard-gecodeerde modelkeuzes (`mainModel()`/`summaryModel()`,
      `"whisper-1"`, `"tts-1"`, `gpt-4o-mini`) vervangen door een lookup op actie.

### 2. Generieke OpenAI-chat-client
- [ ] `OpenAiChatHttpClient` uitbreiden van "alleen vertaling, vast model" naar een
      algemene `complete(model, ...)` / `completeJson(model, schema, ...)`, analoog
      aan `AnthropicHttpClient`.
- [ ] OpenAI **Structured Outputs** (`json_schema`, `strict:true`) gebruiken voor
      de JSON-extractietaken (event-discovery, video-discovery, rss_summarize).
- [ ] Prompt-caching benutten: statische system-prompt/schema vooraan plaatsen.

### 3. Laag-risico modelwissels doorvoeren (geen kwaliteitsverlies)
- [ ] `podcast_transcribe`: whisper-1 → `gpt-4o-mini-transcribe`
      (`WhisperClient.kt`). Eerst op één echte podcast valideren (kwaliteit +
      werkelijke prijs/minuut).
- [ ] `feed_score`, `event_video_discovery`, `event_discovery`-extractie,
      `rss_summarize`, `podcast_episode_summarize` → **GPT-5.4-mini** via config.
- [ ] Datum-verrijking in `event_discovery` → **GPT-5.4-nano**.

### 4. Kwaliteitsgevoelige modelwissels (gewoon omzetten)
- [ ] `daily_summary` op **GPT-5.4** zetten (capabel; moet conclusies trekken).
- [ ] `feed_summarize` → **GPT-5.4-mini**; `event_video_summarize` → **GPT-5.4**.
- [ ] Prompts hertunen voor OpenAI waar nodig (Claude-prompts werken niet 1-op-1).
- [ ] Geen formele A/B-test: gewoon omzetten, beheerder beoordeelt het resultaat
      achteraf en maakt zo nodig een vervolgstory.

### 5. Anthropic volledig verwijderen
- [ ] Nadat alle acties op OpenAI draaien: `AnthropicHttpClient` + bijbehorende
      config (`app.anthropic.*`) verwijderen.
- [ ] `PNF_ANTHROPIC_API_KEY` uit `deploy/secrets-cluster.env` (en `.env`) halen
      en `seal-secrets.sh` opnieuw draaien.
- [ ] Dashboard/kostenuitsplitsing vereenvoudigen naar nog één LLM-provider.

### 6. Prijzen per model in configuratiebestand (laatste)
- [ ] De tarieven van alle gebruikte modellen uit de code (`Pricing.kt`) halen en
      in een configuratiebestand zetten, per model bij te werken — geen hardcoded
      prijzen meer.
- [ ] Ondersteun de drie afrekeneenheden: tokens (in/out per 1M),
      seconden/minuten (transcriptie), characters (TTS).
- [ ] Per-call kostenberekening leest de tarieven uit deze config; `external_calls`
      blijft per call `cost_usd` loggen.
- [ ] Bron-URL + datum laatste update bij de config, zodat handmatig bijwerken
      makkelijk blijft.
- [ ] Omdat Anthropic in subtaak 5 al verwijderd is, hoeft dit alléén nog de
      OpenAI-modellen te dekken.

## Acceptatiecriteria
- Modelkeuze per actie staat volledig in config en is zonder code-wijziging te
  veranderen.
- Tarieven per model staan in config (niet hardcoded); per call wordt `cost_usd`
  daaruit berekend.
- De goedkope wissels (subtaak 3) leveren een meetbare kostendaling op.
- Anthropic is volledig verwijderd; alle AI-tekst draait op OpenAI.

## Buiten scope (eventueel later, aparte story)
- Web search van Tavily → OpenAI `web_search`-tool.
- Integratie met de provider Cost-API's (bewust afgewezen: alleen dag-aggregaat,
  gedeelde API-key).

## Risico's
- Legacy-tarieven (whisper-1, gpt-4o-mini, tts-1) deels via aggregators → verifiëren.
- Geen objectieve NL-benchmark GPT vs Claude → kwaliteit wordt achteraf beoordeeld
  (subtaak 4), eventueel vervolgstory bij regressie.
- `gpt-4o-mini-transcribe` per-minuutprijs is OpenAI-schatting → op echte podcast valideren.
- Tot subtaak 6 klaar is, kan de kostenschatting voor nieuwe modellen tijdelijk
  onnauwkeurig zijn.
- Prijstabel veroudert → bron-URL + datum bij de config, periodiek bijwerken.
