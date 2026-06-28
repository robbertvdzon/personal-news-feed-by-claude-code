# SF-477 - Worklog

Story-context bij eerste pickup:
Documentatie-alignment-pass uitvoeren (docs i.l.m. code)

Controleer de volledige documentatieset tegen de huidige broncode en trek geconstateerde drift recht met de code als leidende bron. WIJZIG GEEN broncode, build-, deploy- of testbestanden - alleen documentatie. Stappen: (1) Inventariseer de werkelijke functionaliteit in newsfeedbackend/ (controllers/endpoints, persistentie = PostgreSQL/Neon + Flyway-migraties, AI-provider/modelconfiguratie, nightly/scheduled jobs) en in de frontends frontend/ en frontend-reader/ (echte Flutter-screens). (2) Loop de docs-set door: README.md, runbook.md, specs/* (README.md, backend-functional-spec.md, backend-technical-spec.md, frontend-spec.md, e2e.md, openapi.yaml, branch-commit-convention.md) en docs/factory/* incl. agents/*. (3) Verifieer expliciet de bekende aandachtspunten: persistentie (niet 'JSON-bestanden op schijf'), AI-provider/modelconfig, openapi.yaml-endpoints t.o.v. de echte controllers, spec-bestandsnamen in specs/README.md, en frontend-claims tegen de daadwerkelijke screens (endpoint alleen is onvoldoende bewijs). (4) Werk drift bij in de docs; documenteer niet-veilig-zelf-te-herstellen architectuurafwijkingen als bevinding i.p.v. ze in de code te wijzigen. (5) Review je eigen diff: bevestig dat git diff uitsluitend documentatiebestanden (en evt. worklog) raakt. docs/stories/ is read-only input. Een lege/kleine diff is een geldige uitkomst en wordt dan als bevinding gerapporteerd.

Stappenplan:
[x]: read issue and target docs
[x]: inventariseer werkelijke functionaliteit (backend controllers/config/jobs + beide Flutter-apps)
[x]: loop de volledige docs-set door en vergelijk met de code
[x]: werk geconstateerde drift bij (alleen documentatie)
[x]: review eigen diff — uitsluitend documentatiebestanden
[x]: update story-log met resultaten

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Aanpak

Broncode als leidende bron geïnventariseerd:
- **Backend**: alle 13 `@RestController`-klassen (paden + auth/rollen), `application.properties`
  (AI-provider = OpenAI, `app.ai.models.*`-defaults, `app.ai.pricing`, env-vars `PNF_AI_MODEL_*`),
  persistentie (PostgreSQL/Neon + Flyway `V1..V15`, audio als BYTEA — geen JSON-op-schijf),
  en alle `@Scheduled`-jobs.
- **Frontends**: de werkelijke Flutter-screens van `frontend/` (5 tabs, settings-secties,
  admin-schermen, events) en `frontend-reader/` (read-only single-screen reader).

## Geverifieerde aandachtspunten (geen drift — reeds correct)

- **Persistentie**: alle docs beschrijven PostgreSQL/Neon + Flyway correct; nergens nog
  "JSON-bestanden op schijf" (alleen `external_calls.jsonl` als audit-log, juist beschreven).
- **AI-provider/modelconfig**: OpenAI overal correct; defaults `gpt-5.4-mini`/`gpt-5.4`/
  `gpt-5.4-nano`/`gpt-4o-mini-transcribe` matchen `application.properties`. Geen Anthropic-restanten
  in de app-docs.
- **openapi.yaml vs. controllers**: alle controller-paden komen voor in `specs/openapi.yaml`
  (incl. `/api/account/*`, `/api/shared/*`, `/api/admin/*`, `/api/admin/costs/*`,
  `/api/settings/event-preferences|event-denylist`). Volledig in lijn.
- **Frontend-claims**: `frontend-spec.md` en `docs/factory/functional-spec.md` melden expliciet
  dat er **geen** Settings-UI voor event-preferences/denylist is (alleen backend-endpoints; denylist
  gevuld via event verwijderen). Dit klopt met de code (geen treffers in `frontend/lib`).
- **TTS-config**: §7.3 (`onyx`/`alloy`, speed 1.2x) matcht `TtsClient.kt`.
- **Jackson / OpenAPI-generator / Modulith-verify-test / testsuite (3 unit-tests)**: docs zijn
  reeds aligned met de code (com.fasterxml.jackson, geen generator-plugin, geen verify-test,
  beperkte unit-test-suite). Geen wijziging nodig.

## Bijgewerkte drift (code is leidend)

1. `specs/backend-functional-spec.md` §7.2 — Tavily werd beschreven als "alleen voor ad-hoc
   verzoeken"; de code gebruikt Tavily óók voor event- en event-video-discovery (§6.8/§6.9).
   Tekst gecorrigeerd.
2. `specs/backend-functional-spec.md` §9 "Geplande taken" — tabel miste twee in de code aanwezige
   jobs: de wekelijkse event-video-discovery (`0 0 3 * * SUN`, `EventVideoScheduler`) en de
   podcast-transcript-worker (`@Scheduled fixedDelay`, `PodcastTranscriptWorker`). Toegevoegd.
3. `specs/README.md` — `branch-commit-convention.md` bestaat in `specs/` maar ontbrak in de
   bestandstabel en in de repostructuur-boom (waar ook `e2e.md` ontbrak). Aangevuld.

## Niet-veilig-zelf-te-herstellen architectuurafwijkingen (gemeld, niet gewijzigd)

Deze zijn architecturale keuzes/code-state, geen mechanische doc-fix — code blijft leidend en
mag in deze docs-only story niet aangepast worden:
- `SettingsController` heeft bewust geen klasse-`@RequestMapping` (bedient meerdere prefixes);
  reeds als zodanig in de spec/tips vastgelegd.
- Backend gebruikt `com.fasterxml.jackson` i.p.v. een eventueel "tools.jackson"; docs zijn hier al
  op aangepast. Migratie zou een risicovolle dependency-change zijn.
- Spring Modulith `verify()`-test ontbreekt; docs beschrijven dit correct als "optioneel/nog niet
  aanwezig".

## Tests / build

Geen broncode gewijzigd — uitsluitend Markdown-documentatie. Backend `mvn test` en Flutter
`analyze`/`test` zijn daarom niet relevant en niet gedraaid; CI valideert overige checks. `git diff`
raakt alleen `specs/README.md` + `specs/backend-functional-spec.md` (+ dit worklog).

## Test (SF-479) — verificatie tester

Docs-only PR → geen browser/preview-test nodig (conform agent-tip `docs-only-pr-test-approach`).
Testwerk = (1) diff-scope-check en (2) code-inspectie om de doc-claims te verifiëren.

Geverifieerd:
- **Diff-scope**: `git diff --name-only main...HEAD` toont uitsluitend `specs/README.md`,
  `specs/backend-functional-spec.md` en dit worklog — géén `.kt`/Dart/tests/build/deploy/infra.
  Voldoet aan AC "git diff raakt uitsluitend documentatiebestanden".
- **Drift 1 (Tavily §7.2)**: Tavily wordt in de code daadwerkelijk gebruikt door
  `EventDiscoveryPipeline` (§6.8) en `EventVideoDiscoveryPipeline` (§6.9), en niét in de
  RSS-pipeline (`rss/`-package bevat geen Tavily-referentie). Doc-correctie is correct.
- **Drift 2 (schedulers §9)**: alle 5 jobs kloppen met `@Scheduled` in de code:
  RSS `0 0 * * * *`, daily `0 0 6 * * *`, events `0 0 2 * * SUN`,
  event-video `0 0 3 * * SUN` (`EventVideoScheduler`, KAN-66) en podcast-transcript-worker
  `fixedDelayString` default `120000` (≈2 min, `PodcastTranscriptWorker`). Nieuw toegevoegde
  regels matchen 1-op-1.
- **Drift 3 (specs/README.md)**: `specs/e2e.md` én `specs/branch-commit-convention.md` bestaan
  daadwerkelijk; toevoeging aan bestandstabel + repostructuur-boom is correct.
- **Sectieverwijzingen** §6.8/§6.9 bestaan in de spec; KAN-65/KAN-66-labels consistent.

Resultaat: kleine, correcte documentatie-diff — geldige uitkomst. Geen bugs gevonden.
**Uitkomst: tested.**
