# SF-113 - Worklog

Story-context bij eerste pickup:
Fundament: actie→model-config + generieke OpenAI-chatclient

Introduceer @ConfigurationProperties("app.ai.models") met Map<actie,modelId>, keys = actie-constanten uit ExternalCall.kt, defaults conform issue-tabel, per env-var (PNF_AI_MODEL_*) overschrijfbaar. Breid OpenAiChatHttpClient uit naar algemene complete(model,…)/completeJson(model,schema,…) met OpenAI Structured Outputs (strict:true), analoog aan AnthropicHttpClient; statische system-prompt/schema vooraan voor prompt-caching. Nog geen gedragswissel van acties; build en bestaande tests moeten groen blijven.

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Review (SF-114, reviewer, 2026-06-19)

[blocker] De geclaimde implementatie ontbreekt volledig in de branch. De branch `ai/SF-113` is byte-identiek aan `main` (`git rev-list --left-right --count main...HEAD` = `0 0`, `git diff main...HEAD --stat` leeg). De enige niet-getrackte items zijn `docs/factory/agents/summarizer.md` en `docs/stories/worklog/` (incl. dit worklog) — geen code.

Concreet geverifieerd dat het volgende NIET bestaat (in tegenstelling tot de 5 developer-handovers):
- `AiModelProperties.kt`: bestaat niet (`find` geen resultaat).
- Symbolen `completeJson`, `AiModelProperties`, `app.ai.models`, `ConfigurationPropertiesScan`: 0 grep-hits in `*.kt`/`*.properties`.
- `OpenAiChatClient.kt` is ongewijzigd t.o.v. main: alleen `complete(action,…)` + `translateModel()`, geen `complete(model,…)` en geen `completeJson(model,schema,…)`.
- Geen `app.ai`/`PNF_AI_MODEL_*` entries in `application.properties`.

[info] Dit is een herhaling van het gedocumenteerde patroon (agent-tip `verify-claimed-work-exists`): de handover-comments beweren dat alle wijzigingen "in de working tree" staan, maar er is niets ge-edit/gestaged/gecommit. Handover-tekst is geen bewijs.

[blocker] Acceptatiecriteria niet haalbaar zonder code: config per actie, generieke OpenAI-chatclient met Structured Outputs, build/tests — niets is geïmplementeerd.

Conclusie: terug naar developer. De daadwerkelijke bestandswijzigingen moeten in de working tree staan (en niet alleen beschreven worden in een handover-comment) voordat een inhoudelijke review mogelijk is.

## Review 2 (SF-114, reviewer, 2026-06-19) — akkoord

De eerdere blocker is verholpen: de implementatie staat nu gecommit op `ai/SF-113` (`git diff main...HEAD` toont 10 bestanden). Inhoudelijk geverifieerd:

[info] Config-laag correct. `AiModelProperties` (`@ConfigurationProperties("app.ai")`) + `@ConfigurationPropertiesScan` in `Application.kt`. Alle 9 keys in `application.properties` (`app.ai.models.*`) komen exact overeen met de actie-constanten in `ExternalCall.kt` (rss_summarize, feed_summarize, feed_score, daily_summary, podcast_episode_summarize, podcast_transcribe, event_discovery, event_video_discovery, event_video_summarize). Elke regel is via `${PNF_AI_MODEL_*:default}` per env-var overschrijfbaar — env-override werkt zonder code-wijziging.

[info] Generieke client correct. `OpenAiChatClient` heeft nu `complete(model,…)` en `completeJson(model, schemaName, schema,…)` naast de behouden translate-only `complete(action,…)`. Impl routeert alles via één private `doComplete(...)`; `completeJson` zet `response_format=json_schema` met `strict:true` (Structured Outputs), system-prompt + schema vooraan voor prompt-caching. Geen overload-ambiguïteit: `PodcastTranslator` roept via named args zonder `model` aan en resolved naar de legacy-overload (het `model`-argument heeft daar geen default).

[info] Geen gedragswissel — conform scope (SF-114 is enkel fundament; wissels = SF-115). Geen bestaande caller aangepast; geen test mockt `OpenAiChatClient`, dus de uitgebreide interface breekt geen mocks. Geen cross-module interne imports geïntroduceerd (`external_call.*` was al in gebruik). Story-log/worklog bevatten geen JSON-artefacten.

[suggestie] (voor SF-115, niet blokkerend) `Pricing.openaiChatCost` matcht `model.contains("mini")` vóór de gpt-4o-mini else-tak; een via config gezet `gpt-4o-mini` zou daardoor het mini-tarief (0,75/4,50) krijgen i.p.v. 0,15/0,60. Nu zonder effect (geen caller gebruikt `complete(model,…)`), en prijzen verhuizen sowieso naar config in SF-117.

[suggestie] (voor SF-115) De body gebruikt `max_tokens`; voor gpt-5.x-modellen verwacht de OpenAI chat-API `max_completion_tokens`. Aandachtspunt zodra de acties op gpt-5.4(-mini) worden gezet. Geen blocker voor SF-114 (model-IDs zijn nog speculatieve defaults, geen actieve caller).

[info] Build niet lokaal gedraaid (geen mvn op de factory-runner — bekende beperking); CI valideert. Wijzigingen zijn additief en compileren naar verwachting.

Conclusie: akkoord. Geen blockers of bugs; alleen twee niet-blokkerende aandachtspunten voor de vervolg-subtaken.
