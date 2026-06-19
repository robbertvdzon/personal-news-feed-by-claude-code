# SF-113 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Ik heb `.task.md`, de worklogs (SF-113, SF-119) en de story-docs gelezen. Hieronder de eindsamenvatting voor de PO.

---

# Eindsamenvatting — SF-113: Andere AI-modellen gebruiken

**Doel:** AI-modelkeuze én kostenberekening per actie volledig configureerbaar maken, alle AI-tekstgeneratie migreren van Anthropic naar OpenAI, en goedkopere modellen inzetten waar dat geen kwaliteit kost (verwachte besparing ~56% op de DB-berekende kost, ~$39/maand reëel).

## Wat is gebouwd

**1. Actie → model in config (SF-114)**
- `AiModelProperties` (`@ConfigurationProperties("app.ai")`) met `Map<actie, modelId>`, keys identiek aan de actie-constanten in `ExternalCall.kt`.
- Alle 9 acties staan in `application.properties` als `app.ai.models.*`, elk via `${PNF_AI_MODEL_*:default}` per env-var overschrijfbaar — **modelwissel zonder code-wijziging**.

**2. Generieke OpenAI-chatclient (SF-114)**
- `OpenAiChatClient` uitgebreid van "alleen vertaling, vast model" naar algemene `complete(model, …)` en `completeJson(model, schema, …)`.
- `completeJson` gebruikt OpenAI Structured Outputs (`response_format=json_schema`, `strict:true`), met system-prompt/schema vooraan voor prompt-caching.

**3. Migratie alle acties → OpenAI (SF-115)**
- Alle 9 domein-callers omgezet van `AnthropicClient` naar `OpenAiChatClient` + config-lookup (RSS, events, video, podcast, adhoc, transcriptie).
- Modelkeuzes conform de defaulttabel: laag-risico acties → `gpt-5.4-mini`, datum-verrijking → `gpt-5.4-nano`, kwaliteitsgevoelig (`daily_summary`, `event_video_summarize`) → `gpt-5.4`, `podcast_transcribe` → `gpt-4o-mini-transcribe`.
- Fix tijdens migratie: `max_completion_tokens` i.p.v. `max_tokens` (vereist door gpt-5.x).

**4. Anthropic verwijderd (SF-116)**
- `AnthropicClient`/`AnthropicHttpClient`, `Pricing.anthropicCost`, `PROVIDER_ANTHROPIC` en `app.anthropic.*` verwijderd; 0 dode referenties in de backend-src.
- Dashboard/kostenuitsplitsing (frontend + `AdminCostsServiceImpl`) vereenvoudigd naar één provider.

**5. Prijzen per model in config (SF-117)**
- `AiPricingProperties` (`app.ai.pricing.*`) met de drie afrekeneenheden: tokens (in/uit per 1M), per-minuut (transcriptie), per-million-chars (TTS), inclusief bron-URL + datum-laatste-update.
- Per-call `cost_usd` wordt uit deze config berekend en per call in `external_calls` gelogd; gelogd model == berekend model. Onbekend model → 0.0 + WARN (geen exception).
- `Pricing.kt` opgeschoond (alleen ElevenLabs/Tavily resteert).

## Belangrijke keuzes
- **`PNF_ANTHROPIC_API_KEY` blijft bewust staan in `deploy/` (sealed secret + secrets-env).** App-side is volledig Anthropic-vrij, maar de factory `claude-runner` (`job-template.yaml`) consumeert deze gedeelde key nog (dual-use). Verwijderen zou de factory-infra breken. Dit is een gedocumenteerde, in twee reviewrondes geaccepteerde afwijking van de letterlijke AC.
- Tarieven worden handmatig onderhouden (bron + datum bij de config); een af en toe verouderd bedrag is geaccepteerd.
- Model-IDs (`gpt-5.4`, `-mini`, `-nano`, `gpt-4o-mini-transcribe`) zijn defaults en triviaal via config aan te passen mochten exacte IDs afwijken.

## Bug gevonden & opgelost tijdens review
- **Transcriptie-kostenbug (SF-117):** `transcriptionCost` gebruikte float-deling `(seconds + 59) / 60.0`, waardoor de "ceil naar hele minuten"-truc niet werkte en élke transcriptie-call te duur werd gelogd. Opgelost via integer-deling; test uitgebreid met 30/60/120/600s zodat de afronding niet door één toevallige waarde gemaskeerd wordt.

## Wat is getest (SF-119)
- `mvn test-compile` → BUILD SUCCESS (volledige module compileert).
- `mvn test -Dtest=AiPricingPropertiesTest` → 4 tests, 0 failures (incl. de herstelde afrondingscases).
- Code-inspectie per acceptatiecriterium: alle 6 AC's geverifieerd als gehaald.
- **Conclusie tester:** geen blockers, geen bugs.

## Bewust niet gedaan
- **Geen live behavioral test van cost-logging:** backend draait tegen gedeelde prod-DB en de preview-API vereist auth (HTTP 403). De feitelijke per-call kostendaling is pas bij echte runs zichtbaar — buiten test-DB-scope, conform de story-aannames.
- **Volledige Cucumber-integratietests niet gedraaid** (vereisen gedeelde prod-DB; bewust vermeden om destructieve acties op shared data te voorkomen).
- **Geen formele A/B-/kwaliteitstest** van NL-tekstkwaliteit GPT vs Claude: beheerder beoordeelt achteraf, eventuele regressie → aparte vervolgstory.
- **`completeJson` (Structured Outputs) nog niet gewired** in de JSON-extractiecallers — die gebruiken nog `complete()` + bestaande `extractJson()`-helper. Gedrag identiek, geen regressie; AC ("client *ondersteunt* Structured Outputs") is gehaald. Wiring is een suggestie voor een vervolg.
- **Buiten scope (conform story):** Tavily web-search → OpenAI `web_search`; integratie met provider Cost-API's.

---

```json
```
