# SF-115 — Migratie: alle acties op geconfigureerde OpenAI-modellen

Onderdeel van story SF-113 ("Andere AI modellen gebruiken"). Bouwt voort op
SF-114 (config-laag `AiModelProperties` + generieke `OpenAiChatClient`).
Deze subtaak schakelt alle AI-tekstgeneratie en transcriptie daadwerkelijk om
van Anthropic naar OpenAI, met de modelkeuze uit config.

## Stappenplan

- [x] Alle `AnthropicClient`-callers omgezet naar `OpenAiChatClient` + `AiModelProperties`
- [x] `daily_summary` → config-model (default `gpt-5.4`) — `RssScheduler`
- [x] `rss_summarize`, `feed_score`, `feed_summarize` → mini — `RssRefreshPipeline`
- [x] `event_discovery` (seed/similar/categorie) → mini; datum-verrijking → nano — `EventDiscoveryPipeline`
- [x] `event_video_discovery` → mini — `EventVideoDiscoveryPipeline`
- [x] `event_video_summarize` → `gpt-5.4` — `EventVideoSummaryPipeline`
- [x] `podcast_episode_summarize` → mini — `PodcastEpisodeProcessor`
- [x] `podcast_script`, `podcast_topics` → mini — `PodcastGenerator` (nieuwe config-keys)
- [x] `adhoc_summarize` → mini — `AdhocOrchestrator` (nieuwe config-key)
- [x] `podcast_transcribe`: `whisper-1` → `gpt-4o-mini-transcribe` via config — `WhisperClient`
- [x] Extra config-keys toegevoegd in `application.properties` (incl. `event_discovery_date` → nano)
- [ ] Build/tests lokaal — overgeslagen: geen mvn op de factory-runner (zie agent-tip `pnf-runner-no-mvn`); CI valideert

## Wat & waarom

- **Migratie-patroon.** Elke caller injecteerde voorheen `AnthropicClient` en
  riep `complete(operation=…, action=…, model=anthropic.mainModel()/summaryModel(),
  maxTokens=…)`. Dat is vervangen door `OpenAiChatClient` +
  `AiModelProperties`: `openAi.complete(model = aiModels.modelFor(ACTION) ?: <default>,
  action=…, …, maxOutputTokens=…)`. De prompts (system/user) en de bestaande
  JSON-extractielogica blijven ongewijzigd — OpenAI levert tekst terug die door
  dezelfde `extractJson`/parsers loopt. De fallback (`?: …`) houdt een ontbrekende
  config-mapping veilig (geen NPE), maar in de praktijk staat elke actie in
  `application.properties`.
- **`operation`-arg.** De `OpenAiChatClient`-overload kent geen `operation`-param
  (was enkel een Micrometer-tag bij Anthropic); die is bij de migratie weggevallen.
- **Transcriptie.** `WhisperClient` leest het model nu uit
  `app.ai.models.podcast_transcribe` (default `gpt-4o-mini-transcribe`) met de oude
  `app.openai.whisper-model` als fallback. Het endpoint (`/v1/audio/transcriptions`,
  multipart) blijft hetzelfde; alleen de meegestuurde modelnaam verandert.
- **Nieuwe config-keys.** `podcast_script`, `podcast_topics` en `adhoc_summarize`
  stonden nog niet in de tabel maar draaiden óók op Anthropic; ze hebben nu eigen
  config-keys (default mini) zodat geen enkele AI-tekstactie nog op Anthropic loopt.
  De losse datum-verrijking in `event_discovery` gebruikt `event_discovery_date`
  (default nano) maar logt nog onder de `event_discovery`-actie.
- **Anthropic-verwijdering.** Buiten scope van SF-115 — `AnthropicClient`/
  `AnthropicHttpClient` + `app.anthropic.*` blijven (ongebruikt) staan en worden in
  SF-116 verwijderd.
