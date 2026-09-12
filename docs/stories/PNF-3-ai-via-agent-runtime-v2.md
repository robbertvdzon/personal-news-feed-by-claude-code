# PNF-3 — Alle AI-werk via de Agent Runtime (v2 API)

**Doel:** de backend praat niet meer zelf met OpenAI, ElevenLabs of Tavily.
Elke LLM-, zoek-, transcriptie- en TTS-aanroep wordt een job op de Agent
Runtime (`https://agent-runtime.vdzonsoftware.nl`, `/v2`). **Waar het kan
draait die job via het abonnement** (`SUBSCRIPTION`: Claude/Codex-CLI op de
worker). Alleen wat een abonnement niet kan, gaat via `API`-mode in de runtime.
Aan het eind staan er geen AI-provider-keys meer in de PNF-secret.

## Status implementatie (2026-09-12)

**Gebouwd:**
- **Runtime** (commit `a50dceb` in `agent-runtime`):
  - R1: `SPEECH_SYNTHESIS` voor OpenAI en ElevenLabs;
  - R2: parallelle worker-slots (6/2/1) plus een parallelle API-executor;
  - R4: `LOCAL` whisper.cpp-transcriptie.
  - Alles staat live en is getest met echte jobs.
- **PNF:**
  - al het AI-werk loopt via `AiClient` naar de Agent Runtime;
  - Tavily, de OpenAI-client, Whisper, TtsClient, Mp3Concatenator en AudioTranscoder zijn verwijderd;
  - previews draaien zonder schedulers.

**Afwijking van het ontwerp:** in plaats van een `ai_jobs`-tabel met poller en event-gedreven pipelines wachten aanroepers blokkerend op de job. De idempotency-key is een hash over de invoer. Een herstart, of de volgende run met dezelfde invoer, pakt daardoor dezelfde runtime-job weer op. Er gaat dus geen werk verloren of dubbel betaald, met veel minder refactor. RSS-stappen zijn gebatcht (20 / 1 / 8 per job). Omdat de runtime nu parallel werkt, is de doorlooptijd acceptabel.

**Nog open:**
- R3 (API-opties in `/v2/execution-options`).
- `AR_ELEVENLABS_API_KEY` in de runtime-secrets.
- `PNF_AGENT_RUNTIME_TOKEN` in de PNF-secret.

## Besluiten (2026-09-12)
1. **Uitvoermodus:** zo veel mogelijk via het abonnement. Alle tekst- en agentwerk gaat via `SUBSCRIPTION`.
2. **TTS gaat mee.** De Agent Runtime wordt uitgebreid met `SPEECH_SYNTHESIS`.
3. **Tavily verdwijnt.** Zoeken en ophalen doet de agent zelf met zijn eigen web-tools (zie fase 0: eerst verifiëren).
4. **PR-previews** blijven echte AI gebruiken, maar via de runtime en zonder schedulers (zie "Omgevingen").
5. **Transcriptie draait lokaal** met Whisper op de Mac (nieuwe `LOCAL`-modus in de worker, runtime-uitbreiding R4). Zo kost transcriptie niets.
6. **Modellen:** Claude Sonnet 5 is de default. Claude Opus 5 voor `daily_summary` en `podcast_script`.
7. **De runtime gaat veel meer parallel draaien** (worker en server-side API-executor). Dit is een harde voorwaarde vóór de livegang van PNF (R2).

## Uitgangssituatie

### Personal News Feed

Alle LLM-werk loopt via één interface: `ai/OpenAiChatClient`, met als enige
implementatie `ai/infrastructure/OpenAiChatHttpClient` (`/v1/chat/completions`,
synchroon, gooit geen exceptions en logt kosten via `ExternalCallLogger`).

**Chat-acties**

| Actie | Aanroeper | Output | Trigger |
|---|---|---|---|
| `rss_summarize` | `RssSummarizer` | JSON | uurlijkse refresh, per artikel |
| `feed_score` | `FeedSelector` | JSON-array | refresh / reselect / promote |
| `feed_summarize` | `FeedItemGenerator` | JSON | refresh, per geselecteerd item |
| `daily_summary` | `RssScheduler` | Markdown | cron 06:00 |
| `adhoc_summarize` | `AdhocOrchestrator` (na Tavily search+extract) | tekst | `@Async`, op request |
| `podcast_script` | `PodcastGenerator` | regelformaat | `@Async`, POST /api/podcasts |
| `podcast_topics` | `PodcastGenerator` | JSON-array | idem |
| `podcast_translate` | `PodcastTranslator` | tekst (≤ 9000 woorden) | `@Async` |
| `podcast_episode_summarize` | `PodcastEpisodeSummarizer` | JSON (input tot 80k tekens) | ingest / transcript / backfill |

**Overige externe AI- en zoekaanroepen**
- `ai/WhisperClient` doet transcriptie (`gpt-4o-mini-transcribe`), met retry-backoff in `PodcastTranscriptPipeline`.
- `podcast/infrastructure/TtsClient` doet TTS via OpenAI (`tts-1`) en via ElevenLabs.
- `search/TavilyClient` doet search en extract voor ad-hoc verzoeken. Tavily zit er sinds de eerste versie (mei 2026): de backend riep toen kale chat-API's aan zonder web-tools en had dus een losse zoek-API nodig. Alle kosten ervan kwamen van de inmiddels verwijderde events-feature (SF-1746).

**Volume:** ongeveer 350 chat-calls per week plus ongeveer 12 transcripties per week.

### Agent Runtime v2

**Tenant is al geregeld:** `personal-news-feed` bestaat al.
- Token: `AR_PERSONAL_NEWS_FEED_TOKEN`
- Providers: CODEX, CLAUDE, MOCKED
- Modellen: `*`

**Werkwijze**
- Alles is async: `POST /v2/jobs` geeft 202, daarna `GET /v2/jobs/{id}` en ten slotte `/result`.
- Idempotentie loopt via `idempotencyKey`.
- De runtime doet zelf maximaal 3 attempts.
- Het resultaat wordt gevalideerd tegen `resultSchema`.

**Uitvoermodi**
- **`SUBSCRIPTION`** draait `claude -p` of `codex exec` in een Docker-container op de MacBook-worker.
  - Het container-netwerk is niet afgeschermd, dus Claude Code heeft zijn ingebouwde WebSearch en WebFetch (nog verifiëren).
  - Codex krijgt geen `--search`-flag mee.
  - **Worker-concurrency is 1, met één FIFO-queue voor alle tenants.** Software Factory-repositoryjobs kunnen dus lang voor PNF-jobs in de rij staan.
  - Staat de laptop uit, dan wordt er niets verwerkt.
  - Elke job start een nieuwe container en CLI. Dat kost overhead per job.
- **`API`** draait server-side: OpenAI Responses API en OpenAI-transcriptie. Geschikt voor audio.

**Beperkingen die dit plan raken**
- De instructie mag maximaal 65.536 tekens zijn. Grotere input gaat als upload-object; in `SUBSCRIPTION` staat dat als bestand in `/job/input/objects/<naam>/content`, wat prima werkt voor agents.
- `SPEECH_SYNTHESIS` bestaat niet.
- Een abonnement kan geen audio. Transcriptie en TTS gaan daarom via `API`-mode (of later lokaal, zie de open vragen).

## Ontwerpkeuzes

1. **Asynchroon in PNF, niet blokkerend wachten.** Via het abonnement kan een job minuten tot uren in de queue staan, of wachten tot de laptop weer aan gaat.
   - We houden dus geen threads of de per-user-lock in `RssRefreshPipeline` vast tot een job klaar is.
   - PNF slaat ingediende jobs op in een nieuwe tabel `ai_jobs`: `runtime_job_id`, `action`, `subject_ref`, `status`, `idempotency_key`, `created_at`.
   - Een `AiJobPoller` (`@Scheduled` + ShedLock, net als de `AgentRuntimeV2CompletionPoller` van Software Factory) haalt resultaten op en publiceert een domein-event per actie, bijvoorbeeld `RssSummaryReady`.
   - De pipelines worden stapsgewijs event-gedreven. Items krijgen tussenstatussen zoals `SUMMARY_PENDING` en `SCORE_PENDING`, en de recovery-scheduler pakt verweesde jobs op.

2. **Minder, grotere jobs (batchen).** De CLI-overhead per job en de seriële worker maken ~75 losse `rss_summarize`-jobs per week duur in doorlooptijd. Voorstel per refresh-run:
   - één job **"verwerk nieuwe artikelen"**: samenvatten, categoriseren en topics voor alle nieuwe artikelen, met de artikeltekst als upload-object;
   - één job **"selecteer voor feed"**: `feed_score`;
   - één job **"schrijf feed-items"**: `feed_summarize` voor de geselecteerde items.

   Met de agent kan dat later ook in één job ("ranker + schrijver"). We beginnen met drie, omdat die elk los te testen zijn.

3. **Het agent-model benutten.** Waar nu handwerk rond de LLM zit, laten we de agent het doen:
   - **Ad-hoc verzoek:** één job "zoek max N recente artikelen (≤ `maxAgeDays`) over `<onderwerp>`, lees ze en vat ze elk in ~400 woorden samen". Resultaat `{items:[{title,url,source,publishedDate,summary}]}`. **Tavily en `TavilyClient` vervallen.** PNF blijft zelf controleren op datum en dubbele URL's (SSRF-validatie is niet nodig; PNF fetcht niets meer).
   - **Artikeltekst ophalen** gebeurt nu in PNF (`FeedItemGenerator` pakt de eerste 8000 tekens). Dat kan blijven. Optioneel laten we de agent de URL zelf lezen met WebFetch.
   - **Podcast-script:** de prompt vraagt nu om "recente nieuwsartikelen" zonder die mee te geven. We geven de recente feed-items mee als object, en de agent mag aanvullend zoeken.

4. **Seam en fakes.**
   - `OpenAiChatClient` wordt vervangen door `AiJobClient`: `submit(action, subjectRef, instruction, objects, schema) → jobId` en `result(jobId)`.
   - De e2e-suite krijgt `FakeAiJobClient`, die direct "klaar" meldt, zodat de event-flow deterministisch testbaar blijft.

5. **Execution per actie configureerbaar:** `app.agent-runtime.actions.<actie>.{vendor-id,model,mode,timeout-seconds}`.
   - Default voor tekstacties: `anthropic` / `claude-sonnet-5` / `SUBSCRIPTION`. Voor `daily_summary` en `podcast_script`: `anthropic` / `claude-opus-5` / `SUBSCRIPTION`.
   - Codex (`openai` / `gpt-5.4-mini`) blijft uitwisselbaar, maar niet voor acties die web search nodig hebben.
   - `podcast_transcribe`: `local` / `whisper-large-v3-turbo` / `LOCAL` (R4). Fallback via config: `openai` / `gpt-4o-mini-transcribe` / `API`.
   - `podcast_tts`: `openai` of `elevenlabs` in `API`-mode (R1).

6. **Structured output:** elke actie krijgt een `resultSchema`. `AiJson`-parsing, `PodcastScriptParser` en de fallbackketens vervallen.

7. **Idempotency-key:** `pnf:{env}:{action}:{subjectRef}:{sha256(execution|schema|instruction)[:16]}`.
   - `env` is `prod` of `pr-<n>`.
   - Bij expliciete reruns komt er `:{rerunId}` achter.
   - Na een verloren response zoeken we de job op via `GET /v2/jobs`.

8. **Kosten:** er komt een provider `agent-runtime` in `ExternalCallLogger`, gevuld uit `usageSummary`.
   - Voor abonnementsjobs is dat `API_EQUIVALENT`/`PARTIAL`, geschat op tekens ÷ 4. Het dashboard toont dat als "API-equivalent", naast de werkelijke `DIRECT`-kosten van audio-jobs.
   - `AiPricingProperties` vervalt.

## Fasering

### Fase 0: Spike en voorbereiding (1 dag)
- [ ] Zet `AR_PERSONAL_NEWS_FEED_TOKEN` in `newsfeed-api-keys` als `PNF_AGENT_RUNTIME_TOKEN` (en in `secrets-cluster.env.example`).
- [ ] Spike via curl tegen productie (`SUBSCRIPTION`, Claude). **Dit zijn go/no-go-checks:**
  - [ ] Werken **WebSearch en WebFetch** in de container? Een ad-hoc-achtige job met schema geeft echte, recente URL's met datum.
  - [ ] Een batch-job "verwerk 15 artikelen" met een upload-object. Meet doorlooptijd, schema-validatie en tokens.
  - [ ] Hoe lang staan PNF-jobs in de queue als Software Factory bezig is? Wat gebeurt er als de laptop slaapt?
  - [ ] Een `TRANSCRIPTION`-job in `API`-mode met een MP3.
- [ ] Meet hoeveel parallelle Claude-jobs het abonnement en de laptop aankunnen (input voor de default-concurrency in R2).

### Fase 1: Agent Runtime-uitbreidingen (repo `agent-runtime`)
- [ ] **R1: `SPEECH_SYNTHESIS` in `API`-mode.**
  - [ ] Contract: parameters (`voice`, `speed`, `format`) krijgen een plek, bijvoorbeeld een `synthesis`-blok in `CreateJobRequest`. De tekst gaat via `instruction` of een upload-object (voor lange teksten).
  - [ ] Vendor `openai` (`/audio/speech`) en vendor `elevenlabs` (`/v1/text-to-speech/{voiceId}`, model `eleven_multilingual_v2`); `AR_ELEVENLABS_API_KEY` in de runtime-secrets.
  - [ ] Chunking van lange teksten en het samenvoegen van MP3's gebeuren in de runtime, zodat PNF één job per aflevering doet. Output is een artifact `audio` (`audio/mpeg`).
  - [ ] Usage in tekens, prijzen, OpenAPI, `V2Contracts`, acceptance-mocks, monitor en docs.
- [ ] **R2: veel meer parallel (voorwaarde vóór livegang).**
  - **Huidige stand:** de server kent `maxConcurrency` per worker al en controleert die bij claimen (`V2Workers.kt:83`). De worker zelf is echter één lus die claimt en daarna blokkerend uitvoert (`WorkerMain.kt:99-123`) en registreert `maxConcurrency=1`. De server-side API-executor is `@Synchronized` en pakt één job per 500 ms-tick (`V2OpenAiApiExecutor.kt:32-36`).
  - [ ] Worker: een claim-lus met een pool van N slots (virtual threads). Het aantal slots is configureerbaar per jobKind, bijvoorbeeld `AR_WORKER_MAX_APPLICATION_JOBS=8` en `AR_WORKER_MAX_REPOSITORY_JOBS=2`, zodat korte PNF-jobs nooit achter lange Software Factory-jobs wachten.
  - [ ] Worker: per slot een eigen jobdirectory en container. Credentials-kopie, heartbeat, cancel en lease-recovery moeten per slot werken. Ook de v1-`JobExecutor` in dezelfde lus moet niet blokkeren.
  - [ ] Server: claim-query per jobKind met de limieten die de worker meegeeft; `activeCount` per jobKind.
  - [ ] Server: de API-executor verwerkt meerdere jobs tegelijk met een bounded pool (bijvoorbeeld 8). Atomisch claimen voorkomt dat twee threads dezelfde job pakken.
  - [ ] Monitor: actieve slots per worker tonen.
  - [ ] Tests: concurrency-tests op claimen (geen dubbele claims) en een worker-test met meerdere gelijktijdige jobs plus cancel.
- [ ] **R3:** `/v2/execution-options` toont ook `API`- en `LOCAL`-opties (nu ontbreken API-opties).
- [ ] **R4: lokale transcriptie (`LOCAL`-modus).**
  - [ ] Contract: `ExecutionMode.LOCAL` en vendor `local`, alleen voor `TRANSCRIPTION`. `validateSelection`, de providers-allowlist per tenant (bijvoorbeeld `LOCAL`) en de catalogus uitbreiden.
  - [ ] Worker: draait whisper **op de Mac-host, niet in Docker**, zodat Metal/Apple Silicon gebruikt wordt. Kandidaten zijn whisper.cpp (`whisper-cli`, model `large-v3-turbo`) en mlx-whisper; kies op snelheid en NL-kwaliteit in een korte meting.
  - [ ] De worker adverteert de capability alleen als binary en model aanwezig zijn. De taal is een optionele parameter (standaard auto-detect).
  - [ ] Input: het audio-object. De worker converteert zelf met ffmpeg naar 16 kHz WAV, zodat PNF niet meer hoeft te comprimeren (de 25 MiB-limiet van OpenAI vervalt).
  - [ ] Output: artifact `transcript` (`text/plain`), met usage in audiominuten en kosten `0`/`ALLOCATED`.
  - [ ] Transcriptie is zwaar voor de laptop. Geef het een eigen slotlimiet in R2 (bijvoorbeeld 1 tegelijk).
  - [ ] Installatie van whisper en het model opnemen in de worker-setupdocumentatie en het runbook.

### Fase 2: PNF-fundament: `AiJobClient` + `ai_jobs` + poller (2 dagen)
- [ ] `ai/infrastructure/AgentRuntimeHttpClient` met `RestClient` (referenties: `pvdd/.../AgentRuntimeClient.kt` voor uploads en idempotentie, `softwarefactory/.../runtime/v2/AgentRuntimeV2CompletionPoller.kt` voor pollen). Functies:
  - [ ] create, get, result, cancel, list-by-key;
  - [ ] resumable upload;
  - [ ] artifact-download.
- [ ] Flyway-migratie `ai_jobs`. De `AiJobService` doet submit en poll, en publiceert `AiJobCompleted(action, subjectRef, result|error)`.
- [ ] Config `app.agent-runtime.*`. Dit vervangt `app.ai.models.*` en `app.openai.*`.
- [ ] Micrometer:
  - [ ] queue-tijd en run-tijd per actie;
  - [ ] eindstatus;
  - [ ] gauge voor openstaande jobs.
- [ ] Tests:
  - [ ] WireMock-tests voor de HTTP-client;
  - [ ] unit-tests voor de poller (klaar, gefaald, verlopen, verloren create);
  - [ ] `FakeAiJobClient` voor e2e;
  - [ ] `ModuleStructureTest` groen.

### Fase 3: RSS-pipeline event-gedreven + gebatcht (3–4 dagen)
- [ ] `RssRefreshPipeline` opdelen in stappen met tussenstatussen per artikel/item:
  - fetch → job `rss_process_batch` → `RssBatchProcessed`
  - → job `feed_select` → `FeedSelectionReady`
  - → job `feed_write_items` → items opgeslagen + WebSocket-notificatie.
- [ ] Per-user-lock alleen rond de korte DB-stappen, niet rond het wachten.
- [ ] `reselect` en `promoteSingleItem` op dezelfde event-flow. Promote wordt "in behandeling" in de UI in plaats van 10 minuten synchroon wachten.
- [ ] `daily_summary`: de 06:00-cron dient de job in, de poller slaat de samenvatting op. Bij een fout niets opslaan (nu wordt een lege samenvatting opgeslagen).
- [ ] Recovery-scheduler: items die langer dan X uur `*_PENDING` zijn, opnieuw indienen.
- [ ] E2e-tests (`RssRefreshE2eTest`, `RssItemsE2eTest`, `FixedRequestsE2eTest`) omzetten.

### Fase 4: Ad-hoc verzoeken via agent met web search, Tavily eruit (1–2 dagen)
- [ ] `AdhocOrchestrator`: één job met `subject`, `maxCount`, `maxAgeDays` en de instructie om zelf te zoeken, te lezen en samen te vatten; schema `{items:[…]}`.
- [ ] Cancel: `service.cancel` roept `POST /v2/jobs/{id}/cancel` aan.
- [ ] Verwijderen:
  - [ ] `search/TavilyClient`, `PNF_TAVILY_API_KEY`, `app.tavily.*`;
  - [ ] `Pricing.tavily*`, Tavily-routes in `FakeContentServer`.
- [ ] De Tavily-kolom in het kostendashboard blijft historisch zichtbaar.
- [ ] `RequestsE2eTest`, `RequestWebSocketE2eTest` aanpassen.

### Fase 5: Podcasts (2–3 dagen)
- [ ] `podcast_episode_summarize`: transcript of show notes als upload-object, job via de poller. `PodcastShowNotesProcessor`, `PodcastTranscriptProcessor` en `PodcastLongSummaryBackfiller` worden event-gedreven.
- [ ] `podcast_script` + `podcast_topics` worden **één** job `{title, topics[], turns:[{speaker,text}]}`, met recente feed-items als context.
- [ ] `podcast_translate`: transcript als object, resultaat als artifact (`text/plain`); de 9000 woorden kunnen de 1 MiB-resultaatlimiet niet raken, maar het is wel netter als artifact.
- [ ] **Transcriptie** (na R4): `WhisperClient` wordt een `TRANSCRIPTION`-job (`LOCAL`, `local`). De ffmpeg-compressie in PNF vervalt, dus `ffmpeg` kan uit de backend-Dockerfile. `TranscribeOutcome` en backoff blijven, gemapt op de job-eindstatus.
- [ ] **TTS** (na R1): `TtsClient` wordt een `SPEECH_SYNTHESIS`-job per aflevering, met het artifact gedownload naar de audio-PVC. `Mp3Concatenator` en de chunking verdwijnen uit PNF.
- [ ] `PodcastGenerationE2eTest`, `PodcastIngestE2eTest`, `PodcastTranscriptPipelineTest` aanpassen.

### Fase 6: Opruimen (1 dag)
- [ ] Verwijderen:
  - [ ] `OpenAiChatClient` + `OpenAiChatHttpClient`, `WhisperClient`, `TtsClient`-HTTP-code;
  - [ ] `AiJson`, `PodcastScriptParser`, `AiPricingProperties`, `app.ai.*`, `app.openai.*`, `app.elevenlabs.*`.
- [ ] Secret opschonen: `PNF_OPENAI_API_KEY`, `PNF_ELEVENLABS_API_KEY`, `PNF_TAVILY_API_KEY` en de al ongebruikte `PNF_ANTHROPIC_API_KEY`/`CLAUDE_*`/`SF_*`/`TESTER_*` uit `backend-deployment.yaml`, sealed secret en `secrets-cluster.env.example`.
- [ ] Kostendashboard: provider `agent-runtime`, per vendor/model/mode, met werkelijke vs. API-equivalente kosten.
- [ ] Guard-test: faalt als er nog `api.openai.com`, `elevenlabs.io` of `tavily.com` in de main-code staat.
- [ ] Docs bijwerken:
  - [ ] `specs/backend-technical-spec.md` en `backend-functional-spec.md` (async-statussen);
  - [ ] `docs/onboarding-senior-developer.md`;
  - [ ] `runbook.md`: runtime-monitor, openstaande jobs, laptop uit, job cancelen;
  - [ ] `deploy/README.md`.

## Omgevingen

**Nu:**
- Previews (`pnf-pr-<n>`) krijgen via reflector een gespiegelde `newsfeed-api-keys` en daarmee de productie-keys van OpenAI, Tavily en ElevenLabs.
- De `preview-ns-labeller` patcht per preview een eigen Neon-branch-URL in die secret.
- Een preview heeft dus een kopie van de productiedata en draait volledig mee: de uurlijkse refresh, de dagelijkse samenvatting en podcast-ingest, met echte AI-calls op de productie-keys.

**Straks:**
- Previews krijgen via dezelfde secret het runtime-token en gebruiken de productie-runtime (tenant `personal-news-feed`), met `env=pr-<n>` in de idempotency-key.
- **Probleem:** met een seriële abonnements-worker dient elke open PR elk uur een refresh-batch in, en die concurreert met productie.
- **Voorstel:** in de preview-overlay `PNF_SCHEDULERS_ENABLED=false`. Dat geldt voor de hourly refresh, daily summary, podcast recovery en backfill. Handmatige triggers (refresh-knop, ad-hoc verzoek, podcast genereren) blijven werken, zodat tester-agents alles kunnen testen.
- **Lokaal en e2e:** `FakeAiJobClient` of een fake `/v2`-server; nooit de echte runtime.
- **Runtime-acceptance** (`mock/mock/MOCK` met fixtures) gebruikt PNF niet.

## Risico's
| Risico | Mitigatie |
|---|---|
| Laptop uit of slaapt → feed wordt niet ververst | Async-ontwerp met `*_PENDING`-statussen; UI toont "wacht op verwerking"; runbook. Accepteren als trade-off van abonnement |
| Lange SF-jobs blokkeren PNF (FIFO, concurrency 1) | R2: parallelle slots, gescheiden limieten per jobKind |
| Te veel parallelle Claude-jobs → rate limits abonnement of laptop overbelast | Slotlimieten configureerbaar; bij rate-limit-fout retry met backoff (runtime) |
| Lokale Whisper trager of slechter in het Nederlands | Korte meting in R4; per config terug te zetten naar `API`-mode |
| Abonnementslimieten (rate/usage) door extra volume | Batchen (ontwerpkeuze 2); Sonnet als default, Opus alleen waar nodig |
| WebSearch/WebFetch werkt niet in de container | Go/no-go in fase 0; alternatief: runtime geeft Codex `--search` mee, of een `WEB_SEARCH`-tasktype |
| Agent verzint URL's of datums bij ad-hoc | Instructie: alleen gelezen bronnen; PNF valideert URL-formaat/datum; optioneel HEAD-check |
| Grote refactor van synchrone pipelines naar events | Per fase opleveren; e2e-suite als vangnet; `FakeAiJobClient` houdt tests deterministisch |
| Kostenzicht wordt schatting (tekens ÷ 4) | Dashboard labelt "API-equivalent"; werkelijke kosten = abonnement + audio-API |

## Volgorde
1. Fase 0 (spike)
2. Runtime: eerst R2, dan R4 en R1, dan R3
3. PNF: fase 2 → 3 → 4 → 5 → 6

PNF-fase 2 kan parallel aan de runtime-uitbreidingen, want die gebruikt nog de fake.
