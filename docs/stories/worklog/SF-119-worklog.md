# SF-119 — Story-brede test (SF-113: Andere AI-modellen gebruiken)

Tester, 2026-06-19, effort medium. Branch `ai/SF-113`, diff t.o.v. `main`
(SF-114 t/m SF-117 samen, 34 files).

## Aanpak
- Backend draait niet lokaal (shared prod-DB; tester-instructie waarschuwt tegen
  destructieve acties op de gedeelde DB). Preview-backend-API vereist auth
  (`/api/...` → HTTP 403), conform agent-tip `pnf-preview-auth-required`. Live
  behavioral verificatie van cost-logging dus niet mogelijk → code-inspectie +
  build + unit-test als primaire methode.
- `mvn` is op deze runner wél beschikbaar (online deps resolven werkt).

## Verificatie per acceptatiecriterium

1. **Model per actie in config, env-override** — ✅ `AiModelProperties`
   (`@ConfigurationProperties("app.ai")`) + `@ConfigurationPropertiesScan` in
   `Application.kt`. Alle keys in `application.properties` (`app.ai.models.*`)
   matchen de actie-constanten uit `ExternalCall.kt`; elke regel via
   `${PNF_AI_MODEL_*:default}` per env-var overschrijfbaar.

2. **Tarieven in config, per-call cost_usd, 3 afrekeneenheden** — ✅
   `AiPricingProperties` (`app.ai.pricing.*`) met tokens (in/out per 1M),
   per-minute (transcriptie), per-million-chars (TTS) + `source`/`updated`.
   Gewired in `OpenAiChatHttpClient.tokenCost`, `WhisperClient.transcriptionCost`,
   `TtsClient.characterCost`; gelogd model == gerekend model. Onbekend model →
   0.0 + WARN (geen exception).

3. **Goedkope modelwissels** — ✅ Defaults: laag-risico acties → `gpt-5.4-mini`,
   datum-verrijking → `gpt-5.4-nano`, kwaliteitsgevoelig (`daily_summary`,
   `event_video_summarize`) → `gpt-5.4`, `podcast_transcribe` →
   `gpt-4o-mini-transcribe` (0.003 vs whisper-1 0.006 per min). Meetbaar lager
   tarief in config; feitelijke per-call daling pas zichtbaar bij live runs
   (buiten test-DB-scope, conform story-aannames).

4. **Anthropic verwijderd** — ✅ `AnthropicClient`/`AnthropicHttpClient` verwijderd,
   `PROVIDER_ANTHROPIC`/`anthropicCost` weg (0 refs in `src/`), `app.anthropic.*`
   uit properties, dashboard (frontend `admin_costs_screen.dart` +
   `AdminCostsServiceImpl`) opgeschoond. Resterende "anthropic"-treffers zijn
   legitiem: brand-name in `PodcastTranslator`-prompt + 1 historische comment.
   - NB: `PNF_ANTHROPIC_API_KEY` blijft bewust in `deploy/` (sealed secret +
     `secrets-cluster.env.example`) wegens dual-use door de factory `claude-runner`
     (`job-template.yaml`). App-side is volledig schoon. Documenteerde, in twee
     reviewrondes geaccepteerde afwijking van de letterlijke AC — geen app-regressie.

5. **Structured Outputs** — ✅ `OpenAiChatClient.completeJson(model, schemaName,
   schema, …)` zet `response_format=json_schema, strict:true`, schema/system
   vooraan voor prompt-caching. Interface ondersteunt het; AC = "client
   *ondersteunt*", gehaald.

6. **Build + tests** — ✅ `mvn test-compile` → exit 0 (volledige module compileert).
   `mvn test -Dtest=AiPricingPropertiesTest` → 4 tests, 0 failures. De eerder
   afgekeurde ceil-minuten-bug (SF-117) is opgelost: `transcriptionCost` gebruikt
   integer-deling `(seconds + 59) / 60`; test dekt nu 30/60/120/600s.
   - Volledige `mvn test` (Cucumber-integratietests) niet gedraaid: vereist
     gedeelde prod-DB-verbinding; bewust niet uitgevoerd om destructieve acties op
     shared data te vermijden.

## Conclusie
Geen blockers, geen bugs gevonden. Implementatie is consistent met de story-scope
en alle acceptatiecriteria. De enige afwijking (Anthropic-key in deploy) is
gedocumenteerd, intentioneel en reeds gereviewd. → tested.
