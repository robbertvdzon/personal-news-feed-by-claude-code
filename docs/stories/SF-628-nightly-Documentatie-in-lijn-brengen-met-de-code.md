# SF-628 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope
Nightly documentatie-alignment: controleer de gehele documentatieset tegen de actuele broncode en breng de docs in lijn waar ze afwijken. **De code is leidend en mag niet worden gewijzigd** — alleen documentatie.

In scope (documentatie die mag worden aangepast):
- `README.md`
- `runbook.md`
- `specs/*` — `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `e2e.md`, `openapi.yaml`, `branch-commit-convention.md`
- `docs/factory/*` incl. `agents/*`

Read-only input (niet aanpassen, wel gebruiken als context over het waaróm van functionaliteit):
- `docs/stories/`

Uit scope:
- Alle broncode (backend `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `deploy/`, `e2e/`-scenario's, config, migraties).

Aanpak: vergelijk de beschreven functionaliteit, endpoints, persistence, AI-providers/modellen, configuratie en repostructuur met de code; gebruik `specs/openapi.yaml` als gezaghebbende bron voor het API-contract en controleer frontend-claims via grep in de Flutter-screens (backend-endpoint aanwezig ≠ frontend geïmplementeerd). Werk afwijkingen bij zodat alle in de code aanwezige functionaliteit ook gedocumenteerd is, en verwijder/verbeter documentatie die niet (meer) met de code overeenkomt.

## Acceptance criteria
- Er zijn **geen broncode-wijzigingen**: de diff bevat uitsluitend wijzigingen in documentatiebestanden (en het worklog). Elke code-wijziging is reden tot afkeuren.
- De documentatie beschrijft de actuele functionaliteit van de code: in de code aanwezige functionaliteit staat ook in de docs, en docs die niet met de code kloppen zijn rechtgezet of verwijderd.
- `specs/openapi.yaml` blijft consistent met de daadwerkelijke API-endpoints in de backend.
- Gedocumenteerde frontend-functionaliteit is geverifieerd aanwezig in de Flutter-screens, niet enkel afgeleid uit het bestaan van een backend-endpoint.
- `docs/stories/` is niet gewijzigd.
- Een kleine of lege documentatie-diff is een geldige uitkomst wanneer docs en code al in lijn zijn; gevonden maar bewust niet-gewijzigde drift wordt concreet gemeld.

## Aannames
- Dit is een herhaling van eerdere nightly docs-alignment-passes (SF-319/SF-327/SF-406/SF-477/SF-544); de eerdere brede correcties (persistence → PostgreSQL/Neon + Flyway, AI-provider/modeldefaults, spec-bestandsnamen, aangevulde openapi-endpoints, verwijderde onjuiste frontend-claims) zijn al doorgevoerd, dus de verwachte diff is klein tot leeg.
- `docs/factory/` is compleet en git-getrackt (SF-220); er hoeft géén apart acceptatiecriterium voor het aanvullen van stub-docs te worden toegevoegd.
- Bekende, niet-veilig-eenzijdig-te-herstellen afwijkingen tussen code en spec (bijv. Jackson `com.fasterxml.jackson` vs spec `tools.jackson`, cross-module domain/infrastructure-imports, domeinmodellen direct als HTTP-response) worden gemeld in het worklog, niet als "code aanpassen" of als doc-herschrijving die het werkelijke codegedrag verbloemt.
- Het worklog (`docs/stories/worklog/SF-628-worklog.md`) telt niet als broncode-wijziging.

## Eindsamenvatting

Ik heb alle context: `.task.md`, het worklog en de diff. De story is een nightly docs-alignment-pass die uitkwam op een worklog-only diff. Hier de PO-samenvatting.

---

## Eindsamenvatting SF-628 — Nightly: documentatie in lijn brengen met de code

### Wat is gebouwd
Niets nieuws aan code of documentatie. Dit was een nachtelijke controle-pass die de volledige documentatieset (`README.md`, `runbook.md`, `specs/*` incl. `openapi.yaml`, `docs/factory/*` incl. `agents/*`) heeft vergeleken met de actuele broncode. **Conclusie: docs en code zijn al volledig in lijn.** De enige wijziging in de diff is het worklog-bestand (1 bestand, +124 regels). Geen broncode-wijzigingen, geen wijzigingen in `docs/stories/`.

### Waarom een lege docs-diff
Sinds de vorige convergentie (SF-586) is er geen broncode gewijzigd — de enige tussenliggende commit (SF-608) raakte alleen `docs/stories/`. De brede correcties uit eerdere passes (PostgreSQL/Neon + Flyway, OpenAI-modeldefaults, spec-bestandsnamen, openapi-endpoints, verwijderde onjuiste frontend-claims) waren al doorgevoerd. Een kleine/lege diff is hier een geldige uitkomst.

### Geverifieerd in lijn (code is leidend)
- **API-contract:** alle 13 `@RestController`-paden staan in `specs/openapi.yaml`; geen ontbrekende of overtollige endpoints.
- **Persistence:** PostgreSQL (Neon) + Flyway, migraties V1..V15 (historisch gat op V4 is bestaand). Geen JSON-op-schijf-restanten meer in de specs.
- **AI-providers:** OpenAI met defaults gpt-5.4-mini / gpt-5.4 / gpt-5.4-nano / gpt-4o-mini-transcribe, pricing via `app.ai.pricing`. Geen Anthropic in de app-docs.
- **Frontend-claims:** `event_preferences`/`event_denylist` zijn correct als backend-only gedocumenteerd (grep in Flutter-screens = 0 treffers).
- **Jackson** (`com.fasterxml.jackson`), **frontend base-URL** en de **afwezige Spring Modulith verify-test** zijn allemaal correct beschreven.

### Bewust niet gedaan (gemelde drift, niet gewijzigd)
Drie architecturale code-eigenschappen blijven afwijken van de spec maar zijn niet veilig eenzijdig in docs te herstellen zonder het echte codegedrag te verbloemen, en de code mag niet wijzigen:
1. Cross-module interne imports (Spring Modulith-conventie), niet door verify-test afgedwongen.
2. Domeinmodellen direct als HTTP-response i.p.v. response-DTO's.
3. `SettingsController` zonder klasse-`@RequestMapping` (bedient drie prefixes).

Deze zijn concreet in het worklog gemeld, niet weggeschreven.

### Getest
Geen build/test nodig (geen code- en geen doc-inhoudswijziging). Verificatie via code-inspectie en grep-vergelijking. Reviewer en tester hebben de claims onafhankelijk steekproefsgewijs bevestigd; resultaat: **tested-ok**, alle acceptatiecriteria gehaald.
