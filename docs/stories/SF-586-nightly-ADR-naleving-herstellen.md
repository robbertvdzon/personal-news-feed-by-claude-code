# SF-586 - nightly: ADR-naleving herstellen

## Story

nightly: ADR-naleving herstellen

<!-- refined-by-factory -->

## Scope
Nightly gedrag-neutrale controle of de code de vastgelegde architectuurbesluiten nog volgt, en herstel van afwijkingen die zónder functionele wijziging veilig kunnen.

Deze repo bevat **geen** `docs/adr/`-map en geen losse ADR-bestanden. De vastgelegde architectuurbesluiten leven als conventies in:
- `specs/backend-technical-spec.md` — Spring Modulith-modulegrenzen, laagindeling API → Domain → Infrastructure, DTO-regels, Jackson-gebruik, handmatig onderhouden OpenAPI-contract.
- `docs/factory/technical-spec.md` — codeconventies (geen comments tenzij WHY, `@param:`-target bij `@Value`, logging-niveaus).

Behandel deze twee documenten als ADR-equivalent en vergelijk de backend-Kotlincode (`newsfeedbackend/newsfeedbackend/`) en frontends (`frontend/`, `frontend-reader/`) hiermee.

Buiten scope: het optuigen van nieuwe ADR-bestanden of doc-wijzigingen (dat is de docs-alignment-familie SF-319/SF-544), en het aanpassen van integratietests/e2e-suite.

## Acceptance criteria
- De code is vergeleken met de ADR-equivalente conventies in `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`.
- Alleen afwijkingen die **gedrag-neutraal** hersteld kunnen worden, zijn aangepast; het functionele gedrag blijft exact gelijk.
- Bestaande tests blijven slagen (`mvn test` groen; vereist `PNF_DATABASE_URL`). Integratietests en e2e-suite worden niet gewijzigd.
- Afwijkingen waarvan herstel een functionele/gedragswijziging zou vergen, worden **niet** zelf doorgevoerd maar concreet gemeld (welke ADR/conventie, wat wijkt af, waarom niet veilig) — de agent gaat hiervoor in error i.p.v. te raden.
- Een lege of kleine diff (bijv. alleen het worklog) is een geldige uitkomst; dit is geen tekortkoming.

## Aannames
- "ADR's" verwijst naar de conventie-documenten hierboven; er bestaat bewust geen `docs/adr/`-map en die hoeft niet aangemaakt te worden.
- Bekende, structurele afwijkingen worden als gemeld/error behandeld, niet zelf hersteld (zou gedrag of brede signatuur raken):
  - Jackson groupId `com.fasterxml.jackson` in code vs. `tools.jackson` in de spec.
  - Cross-module imports tussen `domain` en `infrastructure` over modulegrenzen.
  - Domeinmodellen die direct als HTTP-response worden teruggegeven i.p.v. via DTO's.
- Er is geen Spring Modulith `ApplicationModules.verify()`-test, dus modulegrens-drift wordt niet automatisch door tests gevangen; controle is handmatig.
- Geen docs-aanvulling-acceptatiecriterium nodig: `docs/factory/` is volledig ingevuld (SF-220).

## Eindsamenvatting

## Eindsamenvatting — SF-586: nightly ADR-naleving herstellen

**Doel van de story**
Nachtelijke, gedrag-neutrale controle of de code de vastgelegde architectuurbesluiten nog volgt, en het veilig herstellen van afwijkingen die zónder functionele wijziging hersteld kunnen worden. Omdat deze repo geen aparte `docs/adr/`-map heeft, zijn de conventies in `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md` als ADR-equivalent behandeld.

**Wat is gebouwd / gewijzigd**
- Geen productiecode gewijzigd. De diff bestaat uitsluitend uit het worklog (`docs/stories/worklog/SF-586-worklog.md`). Dit is per acceptatiecriterium een geldige uitkomst.
- Reden: een volledige grep-gebaseerde audit toont dat de codebase al volledig conform is met de veilig-herstelbare conventies. Er was niets gedrag-neutraal te corrigeren.

**Belangrijkste keuzes**
- De 8 toetsbare ADR-besluiten zijn gedestilleerd (modulegrenzen, laagindeling API→Domain→Infrastructure, DTO-regels, Jackson-groupId, handmatig OpenAPI-contract, comment-/`@param:Value`-/logging-conventies) en stuk voor stuk geverifieerd.
- Bekende structurele afwijkingen zijn bewust **gemeld in plaats van hersteld**, omdat herstel gedrag of brede signaturen zou raken (geen blinde gok, geen risicovolle refactor):
  1. Jackson `com.fasterxml.jackson` — sinds SF-502 geen afwijking meer (spec is hierop aligned); migratie naar `tools.jackson` blijft risicovol en is niet gedaan.
  2. Cross-module interne imports tussen modules (modulegrens-drift) — herstel = architecturale refactor.
  3. Domeinmodellen die direct als HTTP-response dienen — herstel vereist response-DTO's + mapping (contractrisico).
  4. `SettingsController` zonder klasse-`@RequestMapping` (bedient 3 URL-prefixes) — base-path toevoegen zou URLs wijzigen.

**Wat is getest**
- Grep-audit over `newsfeedbackend/.../src/main/kotlin`, bevestigd door de tester (SF-589):
  - `@param:Value`: 21× conform; precies 2 kale `@Value`, beide gedocumenteerde uitzonderingen (`@Bean`-methodeparameter en plain constructor-parameter).
  - Logger: 39/39 exact `private val log = LoggerFactory.getLogger(javaClass)`.
  - external_call-fallback: 10/10 op WARN-niveau.
  - Geen `openapi-generator`-plugin; geen inline `data class` in `*Controller.kt`.
- `mvn test` is niet apart gedraaid: er is geen productiecode gewijzigd, dus het gedrag is identiek aan `main` en de bestaande suite blijft ongewijzigd. Op de preview is niets functioneels te toetsen (worklog-only diff).

**Bewust niet gedaan**
- Geen nieuwe ADR-bestanden, geen doc-wijzigingen (valt onder docs-alignment-familie SF-319/SF-544).
- Geen integratietests/e2e-suite aangepast.
- Geen `ApplicationModules.verify()`-test toegevoegd (zou de build laten falen op de bekende, geaccepteerde modulegrens-drift).
- De vier structurele afwijkingen hierboven zijn niet zelf hersteld (gedrag-/signatuurrakend).

**Uitkomst:** ADR-conformiteit bevestigd, gedrag-neutrale diff = enkel worklog, structurele afwijkingen concreet gemeld. Geen openstaande vragen voor de PO.
