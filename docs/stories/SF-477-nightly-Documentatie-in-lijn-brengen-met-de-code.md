# SF-477 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope

Voer een documentatie-alignment-pass uit: controleer of de volledige documentatieset nog klopt met de huidige broncode en werk de documentatie waar nodig bij. **Er mag geen broncode worden gewijzigd** — alleen documentatiebestanden.

In scope (documentatie die gecontroleerd/bijgewerkt mag worden):
- `README.md` en `runbook.md` (repo-root)
- `specs/`: `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `e2e.md`, `openapi.yaml`, `branch-commit-convention.md`
- `docs/factory/`: `README.md`, `deployment.md`, `development.md`, `functional-spec.md`, `technical-spec.md`, `secrets-local.md` en `agents/*` (developer, refiner, reviewer, summarizer, tester)

Read-only input (raadplegen, niet wijzigen):
- `docs/stories/` — gebruik als context over waaróm functionaliteit bestaat.
- De volledige broncode (backend `newsfeedbackend/`, frontends `frontend/` en `frontend-reader/`, deploy-config).

Out of scope:
- Elke wijziging aan broncode, build-config, deploy-config, migraties of tests.
- Het toevoegen van een "docs aanvullen"-criterium: `docs/factory/` is reeds volledig ingevuld en git-getrackt, dus dit is niet nodig.

## Acceptance criteria

- De documentatie beschrijft alle functionaliteit die in de code aanwezig is; geconstateerde drift tussen code en docs is rechtgetrokken met de code als leidende bron.
- Bekende aandachtspunten zijn expliciet gecontroleerd, waaronder: persistentie (PostgreSQL/Neon + Flyway-migraties, niet "JSON-bestanden op schijf"), AI-provider/modelconfiguratie, de API-endpoints in `specs/openapi.yaml` t.o.v. de daadwerkelijke controllers, en spec-bestandsnamen die in `specs/README.md` worden genoemd.
- Frontend-claims in de documentatie zijn geverifieerd tegen de daadwerkelijke Flutter-screens (een backend-endpoint alleen is onvoldoende bewijs dat een feature in de UI bestaat).
- Er zijn géén wijzigingen aan broncode, build-, deploy- of testbestanden; `git diff` raakt uitsluitend documentatiebestanden (en eventueel het worklog).
- Een lege of kleine documentatie-diff is een geldige uitkomst wanneer de docs al kloppen; in dat geval wordt dat als bevinding gerapporteerd.
- Niet-veilig-zelf-te-herstellen architectuurafwijkingen worden als melding/bevinding gedocumenteerd in plaats van eigenhandig in de code gewijzigd.

## Aannames

- "Gehele documentatie" omvat de hierboven genoemde documentatieset; `docs/stories/` is read-only input en valt buiten de te wijzigen set.
- De code is altijd leidend bij geconstateerde verschillen; bij twijfel wordt de discrepantie gerapporteerd in plaats van de code aan te passen.
- De reviewer keurt de story af zodra er een broncode-wijziging in de diff zit; de tester hoeft niets te testen als de diff alleen documentatie bevat.
- `docs/factory/` bestaat al en is volledig ingevuld, dus er wordt geen acceptatiecriterium toegevoegd voor het aanvullen van factory-docs.

## Eindsamenvatting

Ik heb `.task.md`, de factory-docs, het worklog en de volledige story-diff doorgenomen. Hier de eindsamenvatting.

---

## Eindsamenvatting — SF-477: Documentatie in lijn brengen met de code

**Type:** nightly documentatie-alignment-pass (docs-only). **Resultaat:** kleine, correcte documentatie-diff — een geldige uitkomst.

### Wat is gebouwd / gewijzigd
Een controle van de volledige documentatieset tegen de huidige broncode (code is leidend). Er is **geen broncode gewijzigd**; de diff raakt uitsluitend documentatie:

1. **`specs/backend-functional-spec.md` §7.2 (Tavily)** — Stond foutief als "alleen voor ad-hoc verzoeken". Gecorrigeerd: Tavily wordt óók gebruikt voor event-discovery en event-video-discovery (§6.8/§6.9), maar niét voor de uurlijkse RSS-pipeline.
2. **`specs/backend-functional-spec.md` §9 (Geplande taken)** — Tabel miste twee in de code aanwezige jobs: wekelijkse event-video-discovery (`0 0 3 * * SUN`, KAN-66) en de podcast-transcript-worker (`fixedDelay`, ~2 min). Toegevoegd.
3. **`specs/README.md`** — `branch-commit-convention.md` ontbrak in de bestandstabel en de repostructuur-boom (waar ook `e2e.md` ontbrak). Aangevuld.

### Belangrijkste keuzes
- Bekende aandachtspunten expliciet geverifieerd en **correct bevonden (geen drift)**: persistentie (PostgreSQL/Neon + Flyway, géén "JSON-op-schijf"), AI-provider/modelconfig (OpenAID-defaults matchen `application.properties`, geen Anthropic-restanten), alle 13 controller-paden vs. `openapi.yaml`, TTS-config, en de frontend-claim dat er bewust géén Settings-UI voor event-preferences/denylist bestaat.
- Architectuurafwijkingen die geen mechanische doc-fix zijn (o.a. `SettingsController` zonder klasse-`@RequestMapping`, `com.fasterxml.jackson` i.p.v. `tools.jackson`, ontbrekende Spring Modulith `verify()`-test) zijn **als bevinding gemeld en bewust niet aangepast** — code blijft leidend in een docs-only story.

### Wat is getest (SF-479)
Docs-only PR → geen browser/preview-test nodig (conform de geldende werkwijze). Het testwerk bestond uit (1) diff-scope-check en (2) code-inspectie van de doc-claims:
- Diff-scope bevestigd: alleen `specs/README.md`, `specs/backend-functional-spec.md` + worklog — geen code/tests/build/deploy.
- Alle drie de correcties 1-op-1 tegen de code geverifieerd (Tavily-gebruik, alle 5 scheduler-cron's, bestaan van `e2e.md` en `branch-commit-convention.md`).
- **Uitkomst: getest, geen bugs gevonden.**

### Bewust niet gedaan
- Geen broncode-, build-, deploy- of testwijzigingen (out of scope).
- Geen migratie van `com.fasterxml.jackson` of toevoegen van een Modulith-verify-test (risicovolle code-changes, alleen gemeld).
- `mvn test` / Flutter `analyze` niet gedraaid — niet relevant zonder code-wijziging.
