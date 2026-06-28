# SF-544 - Worklog

Story-context bij eerste pickup:
Documentatie in lijn brengen met de code

Voer een volledige documentatie-alignment-pass uit over de in-scope docs en werk af waar
docs afwijken, ontbreken of verouderd zijn. Code is leidend; geen broncode-wijzigingen;
docs/stories/ is read-only context.

## Stappenplan
- [x] read issue and target docs
- [x] documentatie tegen actuele broncode controleren (backend, frontend, factory/root)
- [x] geconstateerde afwijkingen in docs rechtzetten (code leidend)
- [x] docs-validatie draaien (openapi tab-check, metric-/endpoint-grep)
- [x] update story-log/worklog met results

## Werkwijze
Drie parallelle audits uitgevoerd (backend, frontend, factory/root) waarbij elke doc-claim
tegen de code is geverifieerd via grep/find. Bevindingen daarna zelf nagecontroleerd tegen
de broncode voordat iets is aangepast.

## Aangepaste documentatie (code leidend)
1. **`specs/backend-technical-spec.md` §6 (Custom Metrics)** — de meters
   `newsfeed.event_videos.summary.count` en `…summary.duration` dragen in de code
   (`EventVideoSummaryPipeline.kt`) de labels `username` én `result`; de tabel vermeldde
   alleen `result`. Labels gecorrigeerd naar `username`, `result`.
2. **`docs/factory/agents/developer.md`** — de instructie "Voer na elke module-wijziging
   `ModuleStructureTest` uit" verwees naar een test die niet bestaat in de repo. Dit was
   in tegenspraak met `docs/factory/development.md` en `specs/backend-technical-spec.md` §7,
   die beide expliciet melden dat zo'n test nog niet aanwezig is. Instructie herschreven
   zodat hij overeenkomt met de gezaghebbende bronnen (test toevoegen + draaien indien
   modulegrenzen wijzigen; anders `mvn test`).

## Geverifieerd al-conform (geen wijziging nodig)
- **API-contract**: alle `@RestController`-endpoints (auth/account/settings/rss/feed/
  requests/podcasts/podcast-source/events/admin/admin-costs/shared/version) komen overeen
  met `specs/openapi.yaml`. Geen ontbrekende of overtollige paths.
- **Geplande taken** (`backend-functional-spec.md` §9): cron/fixedDelay-waarden van
  RssScheduler (`0 0 * * * *` + `0 0 6 * * *`), EventScheduler (`0 0 2 * * SUN`),
  EventVideoScheduler (`0 0 3 * * SUN`) en PodcastTranscriptWorker (fixedDelay ~2 min)
  matchen de code.
- **Persistentie**: docs beschrijven correct PostgreSQL (Neon) + Flyway (V1..V15, V4
  overgeslagen). Geen JSON-op-schijf-claims (geen topic_history.json/rss_items.json).
- **AI-provider**: docs beschrijven OpenAI voor de app; resterende Claude/Anthropic-
  vermeldingen betreffen uitsluitend de software-factory zelf (claude-runner/-tester/
  -interactive) en zijn terecht.
- **Jackson**: docs en code gebruiken beide `com.fasterxml.jackson` (geen `tools.jackson`).
- **OpenAPI-generatie**: geen openapi-generator-plugin in pom.xml; docs claimen geen
  codegeneratie (handgeschreven controllers).
- **Testsuite**: docs beschrijven de minimale suite correct (4 unit-tests:
  RssFetcherImageUrlTest, AiPricingPropertiesTest, ApiRequestDtoContractTest,
  PodcastScriptParserTest); Cucumber/WireMock-deps aanwezig maar zonder feature-files,
  correct gedocumenteerd.
- **Micrometer-metrics**: de overige 12 metrics in §6 bestaan exact zo in de code
  (RssRefreshPipeline, PodcastGenerator/Translator, AdhocOrchestrator, Event*Pipeline);
  AI-kosten staan terecht beschreven als external_calls-tabel i.p.v. metric.
- **Frontend**: alle in `frontend-spec.md` beschreven features zijn via grep aangetoond in
  de Flutter-screens (frontend/ en frontend-reader/). Bevestigd: er is GEEN UI voor
  event-preferences/event-denylist (backend-only) en de spec claimt die ook niet.
  API base-URL-default = `http://localhost:8080` (prod via Makefile
  `https://news.vdzonsoftware.nl`); geen `pnf.vdzon.com`-drift.
- **specs/README.md**: bestandslijst komt overeen met de werkelijke specs/-inhoud
  (incl. branch-commit-convention.md).

## Bevindingen / meldingen (niet zelf hersteld — code-wijziging vereist)
Conform bekende, niet-veilig-in-docs-oplosbare afwijkingen (gerapporteerd, niet gewijzigd):
- `settings/api/SettingsController.kt` heeft geen klasse-`@RequestMapping` (bedient 3
  prefixes); base-path toevoegen zou URLs wijzigen.
- Diverse cross-module interne imports en domeinmodellen-als-HTTP-response zijn bestaande
  Spring Modulith-conventieovertredingen; dit zijn architecturale refactors, geen
  documentatie-fixes.

## Docs-validatie
- `specs/openapi.yaml`: tab-check (geen tabs) — ok. Geen lokale YAML-validator beschikbaar
  (runner zonder pyyaml/pip); CI valideert structureel.
- Endpoint-, scheduler- en metric-claims geverifieerd via grep/find tegen de broncode.

## Uitkomst
Kleine, gerichte documentatie-diff (2 bestanden). De in-scope documentatieset is overwegend
al in lijn met de code dankzij eerdere alignment-passes; alleen twee feitelijke afwijkingen
zijn rechtgezet. Git-diff bevat uitsluitend documentatiebestanden (plus dit worklog).
