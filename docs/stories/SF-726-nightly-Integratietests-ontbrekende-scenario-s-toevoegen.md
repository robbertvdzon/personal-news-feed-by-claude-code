# SF-726 - nightly: Integratietests: ontbrekende scenario's toevoegen

## Story

nightly: Integratietests: ontbrekende scenario's toevoegen

<!-- refined-by-factory -->

## Scope
Breng de markdown-e2e-suite (`e2e/scenarios/`) op volledige dekking van de functionele scenario's die de Flutter-app ondersteunt, en houd de bijbehorende documentatie consistent. De "integratietest"-laag van deze repo is de browser-e2e-suite; backend-integratietests (Cucumber/WireMock/`@SpringBootTest`) vallen buiten scope omdat de infrastructuur ontbreekt en in de factory-omgeving (geen Postgres/`PNF_DATABASE_URL`, geen CI die `mvn test` draait) niet betrouwbaar uitvoerbaar is.

In scope:
- Inventariseer de functionele scenario's per Flutter-scherm (`frontend/lib/screens/`) en vergelijk met de bestaande tien scenario's.
- Voeg ontbrekende e2e-scenario's of ontbrekende sub-flows binnen bestaande scenario's toe en verbeter bestaande scripts waar ze niet meer kloppen met het scherm.
- Werk `e2e/readme.md` en `specs/e2e.md` bij zodat de scenario-opsommingen overeenkomen met de daadwerkelijke `e2e/scenarios/`-inhoud.

Buiten scope:
- Geen wijzigingen aan productiecode (alleen tests/scenario's/docs).
- Geen nieuwe backend-integratietest-infra (Cucumber/WireMock/Testcontainers/H2/`*IT.kt`).
- `frontend-reader/` (geen schermen) en adhoc (geen frontend-scherm) blijven buiten de browser-e2e.

## Acceptance criteria
- Elk functioneel Flutter-scherm is gedekt door een e2e-scenario; eventueel ongedekte sub-flows binnen een scherm zijn toegevoegd of expliciet als bewust niet-getest verantwoord in het scenario.
- Nieuwe/aangepaste scenario's volgen de bestaande stijl (NL, vrije-tekst, Doel/Voorwaarden/Stappen/Verwacht resultaat/Faal-condities) en zijn niet-destructief (destructieve acties worden alleen bekeken/geannuleerd; admin-scenario blijft graceful ⏭ Skipped zonder `ROLE_ADMIN`).
- `e2e/readme.md` en `specs/e2e.md` noemen exact dezelfde scenario-bestanden als aanwezig in `e2e/scenarios/`.
- Geen functioneel gedrag van productiecode gewijzigd; `mvn test` blijft groen (vereist `PNF_DATABASE_URL`).
- Als een toe te voegen test duidelijk buggy gedrag zou "bevriezen": niet toevoegen, maar in error gaan met een concrete notitie.
- Een kleine of lege diff is een geldige uitkomst wanneer de dekking al volledig blijkt; dat wordt dan in het worklog onderbouwd.

## Aannames
- De e2e-suite (browser-scenario's) is de bedoelde "integratietest"-laag van deze repo; het optuigen van backend-integratietests valt buiten deze story vanwege ontbrekende, in de factory niet-uitvoerbare infrastructuur.
- De suite is sinds SF-642 al uitgebreid met `admin` en `api-log`; de dekking is daarmee vermoedelijk grotendeels compleet, dus de verwachte diff is klein (aanvullingen/verfijningen) in plaats van een grote nieuwe set scenario's.
- `frontend-reader/` (geen schermen) en adhoc-verwerking (geen frontend-scherm) vallen buiten de browser-e2e-dekking.
- `docs/factory/` is compleet (SF-220) → geen docs-aanvulling-acceptatiecriterium nodig.

## Eindsamenvatting

Op basis van `.task.md` en het worklog (SF-726) is de story volledig afgerond. Hieronder de eindsamenvatting voor de PO.

## Eindsamenvatting — SF-726: Integratietests: ontbrekende scenario's toevoegen

**Wat is gebouwd**
- De markdown-e2e-suite (`e2e/scenarios/`) is op volledige dekking gebracht van de functionele Flutter-schermen. Na inventarisatie van alle 16 schermbestanden bleek precies één functioneel scherm ongedekt: `RssPodcastDetailScreen` (detailscherm voor RSS-podcast-afleveringen).
- Nieuw scenario `e2e/scenarios/rss-podcast-scenario.md` toegevoegd in de bestaande NL-stijl (Doel/Voorwaarden/Stappen/Verwacht resultaat/Graceful degradation/Faal-condities), inclusief de eigen sub-flows: lange Samenvatting (Markdown), Key takeaways, lazy-loaded Ruw transcript en de vertaal-flow.
- Bestaande scenario's verfijnd met cross-references om overlap te voorkomen: `rss-scenario.md` (kies expliciet een artikel; podcasts routeren naar het nieuwe scenario) en `podcast-scenario.md` (afbakening AI-podcasts vs. RSS-podcast-afleveringen).
- Documentatie consistent gemaakt: `e2e/readme.md` en `specs/e2e.md` noemen nu exact dezelfde 11 scenario-bestanden als aanwezig in `e2e/scenarios/`.

**Gemaakte keuzes**
- De browser-e2e-suite is behandeld als de "integratietest"-laag van de repo; backend-integratietest-infra (Cucumber/WireMock/Testcontainers) is bewust niet opgezet wegens ontbrekende, in de factory niet-uitvoerbare infrastructuur (geen Postgres/CI).
- Niet-destructief gehouden: de kostengenererende vertaal-actie wordt alleen bekeken en in de bevestigingsdialoog geannuleerd; admin-scenario blijft graceful ⏭ Skipped zonder `ROLE_ADMIN`.
- UI-feiten zijn 1-op-1 geverifieerd tegen `rss_podcast_detail_screen.dart` + `models.dart` om te voorkomen dat buggy gedrag wordt "bevroren".

**Wat is getest**
- Aard van de wijziging: uitsluitend e2e-scenario's, doc-lijsten en worklog — geen productiecode. Verificatie via code-inspectie (geen dart-binary op de runner; web-preview is een auth-canvas-SPA).
- Doc/bestand-consistentie ✅ (11 bestanden matchen exact in scenarios + readme + spec), scherm-dekking ✅ (alle functionele schermen gedekt, de gap is gedicht), scenario klopt met de code ✅, niet-destructief ✅, geen productiecode geraakt ✅. Conclusie tester: voldoet aan alle acceptance criteria → tested.

**Bewust niet gedaan (gemeld i.p.v. gefixt)**
- Backend-integratietest-infra: buiten scope.
- Bestaande doc-drift Anthropic→OpenAI (`PNF_ANTHROPIC_API_KEY` / `[Anthropic]`-loglijnen) en `data/users.json` (repo draait inmiddels PostgreSQL/Neon): backend-/env-drift, geen scherm-mismatch — buiten scope gelaten en apart gerapporteerd voor een eventuele doc-story.
- `frontend-reader/` en adhoc-verwerking (geen schermen) vallen per definitie buiten de browser-e2e-dekking.

**Diff-omvang:** klein en gericht (5 inhoudelijke bestanden: 1 nieuw scenario, 2 verfijnde scenario's, 2 doc-lijsten) — conform de verwachte kleine diff uit de refinement.
