# SF-114 — Fundament: actie→model-config + generieke OpenAI-chatclient

Onderdeel van story SF-113 ("Andere AI modellen gebruiken"). Deze subtaak levert
alleen het fundament: de configuratie-laag en de uitgebreide OpenAI-client.
Nog géén gedragswissel bij de callers (dat is SF-115).

## Stappenplan

- [x] `@ConfigurationProperties("app.ai")` met `Map<actie, modelId>` (`AiModelProperties`)
- [x] `@ConfigurationPropertiesScan` op `Application`
- [x] Defaults conform issue-tabel in `application.properties`, per env-var (`PNF_AI_MODEL_*`) overschrijfbaar
- [x] `OpenAiChatClient`: `complete(model, …)` + `completeJson(model, schema, …)` toegevoegd
- [x] `OpenAiChatHttpClient`: alles routeert via `doComplete(...)`, optioneel `response_format=json_schema` (`strict:true`)
- [x] `Pricing.openaiChatCost(model, in, out)` als tijdelijke per-model tabel (verhuist in SF-117 naar config)
- [ ] Build/tests lokaal — overgeslagen: geen mvn op de factory-runner (zie agent-tip `pnf-runner-no-mvn`); CI valideert

## Wat & waarom

- **Config-laag.** `AiModelProperties` mapt de actie-constanten uit
  `ExternalCall.kt` op een model-id. Keys staan in `application.properties`
  onder `app.ai.models.<actie>` met issue-defaults, elk via `PNF_AI_MODEL_*`
  overschrijfbaar zodat een model wisselen geen code-wijziging vraagt.
  `modelFor(action)` geeft `null` terug bij ontbrekende mapping, zodat callers
  een eigen fallback kunnen kiezen.
- **Generieke client.** `OpenAiChatClient` heeft naast de bestaande
  vertaal-`complete(action, …)` nu `complete(model, …)` en
  `completeJson(model, schemaName, schema, …)`. De impl routeert alles via één
  private `doComplete(...)`; `completeJson` zet `response_format` op
  `json_schema` met `strict:true` (OpenAI Structured Outputs). System-prompt en
  schema staan vooraan in de body t.b.v. prompt-caching.
- **Bron-compat.** De originele `complete(action, …)` (vertaling, vast model +
  `openaiGpt4oMiniCost`) blijft ongewijzigd; callers zonder `model`-arg resolven
  via named args naar deze overload. Geen bestaande caller is aangepast.
