# SF-544 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope
Voer een documentatie-alignment-pass uit: controleer de volledige documentatieset tegen de actuele broncode en werk de documentatie bij waar deze afwijkt, ontbreekt of verouderd is. Doel is dat alle functionaliteit die in de code zit ook correct in de documentatie staat, en dat de documentatie geen functionaliteit beschrijft die niet (meer) bestaat.

In scope (documentatie die gecontroleerd/bijgewerkt mag worden):
- `README.md` en `runbook.md` (repo-root)
- `specs/` — `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `e2e.md`, `openapi.yaml`, `branch-commit-convention.md`
- `docs/factory/` — `README.md`, `deployment.md`, `development.md`, `functional-spec.md`, `technical-spec.md`, `secrets-local.md` en `agents/*`

Niet in scope:
- **Geen broncode-wijzigingen** (backend Kotlin, Flutter-frontends, build-/deployconfig, migraties). De reviewer keurt af bij elke code-aanpassing.
- `docs/stories/` is read-only input: gebruik het als context over waaróm functionaliteit bestaat, maar wijzig het niet.
- De code is leidend bij elke afwijking; bij twijfel wordt de documentatie aangepast aan de code, niet andersom.

Bekende aandachtspunten op basis van eerdere alignment-passes (verifiëren tegen huidige code, niet blind overnemen):
- Persistentie is PostgreSQL (Neon) + Flyway-migraties, géén JSON-bestanden op schijf.
- API-contract: gebruik `specs/openapi.yaml` als gezaghebbende bron; controleer dat beschreven endpoints overeenkomen met de controllers.
- Frontend-claims verifiëren via grep in de Flutter-screens: een aanwezig backend-endpoint betekent niet dat de frontend-functionaliteit bestaat.

## Acceptance criteria
- De documentatie in scope is gecontroleerd tegen de actuele broncode; geconstateerde afwijkingen tussen docs en code zijn in de documentatie rechtgezet (code blijft leidend).
- Functionaliteit die in de code aanwezig is maar in de documentatie ontbrak, is toegevoegd aan de juiste documentatiebron.
- Documentatie die functionaliteit beschrijft die niet (meer) in de code bestaat, is verwijderd of gecorrigeerd.
- Het API-contract in de documentatie is consistent met `specs/openapi.yaml` en de daadwerkelijke controllers.
- Frontend-functionaliteit die in de docs staat is geverifieerd aanwezig in de Flutter-screens (via grep), niet alleen op basis van een backend-endpoint.
- **Er zijn geen wijzigingen aan broncode**, aan `docs/stories/` of aan tests/build-/deployconfig; de git-diff bevat uitsluitend documentatiebestanden (plus het worklog).
- Een kleine of lege documentatie-diff is een geldige uitkomst wanneer de documentatie al in lijn is met de code; dit wordt dan expliciet als bevinding gemeld.
- Afwijkingen die niet veilig in documentatie op te lossen zijn (bv. wanneer correctie een code-wijziging zou vereisen) worden als melding/bevinding gerapporteerd in plaats van zelf hersteld.

## Aannames
- "Gehele documentatie" betreft de hierboven opgesomde in-scope documentatieset; er bestaat geen aparte `docs/adr/`-map (architectuurbesluiten leven als conventies in `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`).
- `docs/factory/` is reeds volledig ingevuld (SF-220); er hoeft géén apart acceptatiecriterium voor het aanvullen van verse factory-stubs te worden toegevoegd.
- Het bijwerken van het worklog (`docs/stories/worklog/SF-544-worklog.md`) telt niet als verboden code-/story-wijziging; dit is factory-administratie.
- Bij echte onduidelijkheid tijdens uitvoering (nightly, geen mens beschikbaar) geldt: code is leidend en niet-herstelbare afwijkingen worden gemeld in plaats van te wachten.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de diff doorgenomen. Hieronder de eindsamenvatting.

---

## Eindsamenvatting SF-544 — Documentatie in lijn brengen met de code

**Doel:** een documentatie-alignment-pass uitvoeren over de volledige in-scope documentatieset (root `README`/`runbook`, `specs/*`, `docs/factory/*`) en deze bijwerken waar zij afweek van de actuele broncode. Code is leidend; géén broncode-wijzigingen toegestaan.

### Wat is gebouwd / opgeleverd
Een kleine, gerichte documentatie-diff (2 docbestanden + worklog). De pass bestond uit drie parallelle audits (backend, frontend, factory/root), waarbij elke documentatie-claim via grep/find tegen de code is geverifieerd.

Twee feitelijke afwijkingen rechtgezet:
1. **`specs/backend-technical-spec.md` §6 (Custom Metrics)** — de metrics `newsfeed.event_videos.summary.count` en `…summary.duration` dragen in de code (`EventVideoSummaryPipeline.kt`) de labels `username` én `result`; de tabel vermeldde alleen `result`. Labels gecorrigeerd.
2. **`docs/factory/agents/developer.md`** — de instructie verwees naar een `ModuleStructureTest` die niet in de repo bestaat (in tegenspraak met `development.md` en `backend-technical-spec.md` §7). Herschreven naar: test toevoegen+draaien alleen als modulegrenzen wijzigen, anders `mvn test`.

### Belangrijkste keuzes
- **Code leidend bij elke afwijking**; bij twijfel is de doc aangepast aan de code.
- **Niet-veilig-in-docs-oplosbare afwijkingen gerapporteerd i.p.v. zelf hersteld** (zouden code-wijziging vereisen): `SettingsController.kt` zonder klasse-`@RequestMapping`, en enkele bestaande Spring Modulith-conventieovertredingen (cross-module imports, domeinmodellen als HTTP-response) — dit zijn architecturale refactors, geen doc-fixes.

### Geverifieerd al-conform (geen wijziging nodig)
- **API-contract**: alle `@RestController`-endpoints komen overeen met `specs/openapi.yaml`; geen ontbrekende/overtollige paths.
- **Persistentie**: PostgreSQL (Neon) + Flyway (V1..V15, V4 overgeslagen); geen JSON-op-schijf-claims.
- **AI-provider**: docs beschrijven correct OpenAI voor de app; resterende Claude/Anthropic-vermeldingen betreffen uitsluitend de software-factory zelf en zijn terecht.
- **Schedulers/crons, overige 12 metrics, testsuite (4 unit-tests), frontend-features** (via grep in Flutter-screens, incl. bevestiging dat er géén UI is voor event-preferences/denylist) — allemaal conform.

### Wat is getest (SF-546)
Docs-only story: geen browser-test nodig. Verificatie via code-inspectie en interne consistentiecheck:
- Diff bevat uitsluitend docs + worklog (geen `.kt`/Dart/tests/infra, geen `docs/stories/`-wijziging buiten worklog). ✓
- Metric-tags, het ontbreken van `ModuleStructureTest`, en een steekproef scheduler-crons geverifieerd tegen de broncode. ✓
- Uitkomst tester: kleine, correcte documentatie-diff; alle acceptatiecriteria gehaald → **tested**.

### Bewust niet gedaan
- Geen broncode-, test-, build-/deployconfig- of `docs/stories/`-wijzigingen (buiten worklog).
- Architecturale conventieovertredingen niet opgelost (buiten scope; gemeld als bevinding).
- Geen lokale YAML-validatie van `openapi.yaml` (geen validator op de runner); wel tab-check uitgevoerd, CI valideert structureel.

**Conclusie:** de in-scope documentatie is overwegend al in lijn met de code dankzij eerdere alignment-passes; twee concrete afwijkingen zijn rechtgezet. Een kleine doc-diff is hier de verwachte, geldige uitkomst.
