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
