# SF-435 - nightly: ADR-naleving herstellen

## Story

nightly: ADR-naleving herstellen

<!-- refined-by-factory -->

## Scope

Verifieer of de backend- en frontendcode de vastgelegde architectuurbesluiten (ADR's) nog volgt en herstel veilige afwijkingen, **zonder functioneel gedrag te wijzigen**.

Deze repo bevat **geen** `docs/adr/`-map. De architectuurbesluiten zijn vastgelegd als conventies in:
- `specs/backend-technical-spec.md` — Spring Modulith-modulegrenzen en -modulelijst, gelaagde architectuur (API → Domain → Infrastructure), DTO-regels en -locaties, handmatig onderhouden OpenAPI-contract (geen code-generatie), Jackson `tools.jackson`, logging-conventies, WebSocket i.p.v. WebFlux.
- `docs/factory/technical-spec.md` — codeconventies: geen comments tenzij de WHY niet-vanzelfsprekend is, SLF4J-logniveaus, JWT/auth-regels (publieke vs. beveiligde paden, `/api/admin/**` = `ROLE_ADMIN`), Jackson-groupId, `@Value` met expliciete `@param:`-use-site-target op primary-constructor-`val`.

Behandel deze conventiebronnen als ADR-equivalent. Loop ze na tegen de code en breng de code waar veilig (puur mechanisch, gedrag-neutraal) in lijn.

Concrete aandachtspunten (niet uitputtend):
- Modulegrenzen: geen directe imports van interne (`domain`/`infrastructure`) klassen van andere modules; communicatie alleen via publieke service-interface of `ApplicationEventPublisher`.
- Lagenstructuur: controllers bevatten geen business logic en roepen nooit rechtstreeks repositories aan; domeinmodellen worden nooit direct als HTTP-response geserialiseerd; DTO's staan in `module/api/dto/`.
- Jackson-groupId conform spec (`tools.jackson`).
- `@Value` op primary-constructor-`val` heeft `@param:`-target conform de beschreven uitzonderingen.
- Geen overbodige inline comments die de "geen comments tenzij WHY"-conventie schenden (class-level KDoc valt hier expliciet buiten).
- OpenAPI-contract: geen OpenAPI Generator-plugin in `pom.xml`; controllers handgeschreven.

## Acceptance criteria

- De ADR-equivalente conventiebronnen (`specs/backend-technical-spec.md`, `docs/factory/technical-spec.md`) zijn nagelopen tegen de code en geconstateerde, veilig herstelbare afwijkingen zijn rechtgezet.
- Alle wijzigingen zijn **gedrag-neutraal**: geen functionele/observeerbare gedragswijziging (geen gewijzigde endpoints, responses, persistentie, scheduling of gedragsbepalende logoutput).
- Alle bestaande tests blijven slagen (`mvn test` in `newsfeedbackend/newsfeedbackend/`; bestaande Flutter-tests in `frontend/` en `frontend-reader/`).
- Bestaande tests worden **niet** aangepast om groen te blijven; ze blijven vangnet.
- Vereist naleving van een ADR/conventie een functionele of gedragswijziging, dan wordt die **niet** doorgevoerd: de run eindigt in error met (a) welke conventie/ADR het betreft, (b) welke code ervan afwijkt en (c) waarom herstel een gedragswijziging zou vereisen.
- Als er **geen** veilige afwijkingen zijn, is een lege of minimale diff een geldige uitkomst; dit wordt in het worklog vastgelegd.
- Het worklog (`docs/stories/worklog/SF-435-worklog.md`) bevat de uitgevoerde controle, de bevindingen en de rationale.

## Aannames

- "ADR's" = de conventiebronnen `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`, omdat er geen `docs/adr/`-map bestaat. De verwijzing naar `docs/adr/` in de story is illustratief, niet voorschrijvend.
- "Veilig herstellen" = puur mechanische, gedrag-neutrale aanpassingen (imports/zichtbaarheid, DTO-plaatsing, annotatie-targets, conventie-strijdige comments). Alles wat gedrag raakt valt onder de error-randvoorwaarde.
- Class-level KDoc valt buiten de "geen comments"-conventie (die geldt alleen voor inline comments) en wordt niet als afwijking behandeld.
- De Spring Modulith-modulegrenzen worden niet door een verify-test afgedwongen (`ApplicationModules.verify()` ontbreekt); controle gebeurt handmatig via spec en code-inspectie. Het toevoegen van zo'n verify-test valt buiten scope tenzij het gedrag-neutraal en zonder buildrisico kan (anders error/melding).
- Dit is een silent nightly job: bij echte onduidelijkheid eindigt de run in error i.p.v. te wachten op input.
- Scope omvat backend (`newsfeedbackend/`) én de twee Flutter-frontends (`frontend/`, `frontend-reader/`), maar de meeste ADR-equivalente conventies betreffen de backend.
- Bekende, eerder geconstateerde afwijkingen die buiten "veilig/mechanisch" vallen (o.a. feitelijke Jackson-groupId `com.fasterxml.jackson` vs. spec, cross-module imports van interne klassen, domeinmodellen direct geserialiseerd als HTTP-response) worden niet zelf hersteld maar als error/melding gedocumenteerd voor een aparte refactor-story.

## Eindsamenvatting

## Eindsamenvatting — SF-435: nightly: ADR-naleving herstellen

**Doel:** Controleren of backend- en frontendcode de vastgelegde architectuurbesluiten nog volgt en veilige, gedrag-neutrale afwijkingen herstellen. Omdat er geen `docs/adr/`-map bestaat, zijn de conventiebronnen `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md` als ADR-equivalent gehanteerd.

### Wat is gebouwd
Eén veilige, puur mechanische correctie: zes inline request-DTO's zijn uit hun controllers verplaatst naar de conventionele locatie `module/api/dto/`, conform backend-spec §4 (de `auth`-module volgde dit al als referentie).

| DTO | Naar |
|-----|------|
| `ChangePasswordRequest` | `auth/api/dto/AccountDtos.kt` |
| `ResetPasswordRequest`, `SetRoleRequest` | `admin/api/dto/AdminDtos.kt` |
| `AddEventPreferenceRequest`, `RemoveEventPreferenceRequest` | `settings/api/dto/SettingsDtos.kt` |
| `VideoSummaryRequest` | `events/api/dto/EventDtos.kt` |

Diff: 4 controllers aangepast, 4 nieuwe dto-bestanden, 1 nieuwe contract-test.

### Gemaakte keuzes
- **Gedrag-neutraliteit geborgd:** elke DTO werd alleen door zijn eigen controller gebruikt (geverifieerd via grep); Jackson `@RequestBody`-deserialisatie gaat op veldnaam, niet op package. Velden/types/endpoints/responses ongewijzigd; DTO's blijven binnen dezelfde Modulith-module.
- **Conform-punten zonder actie:** geen OpenAPI Generator-plugin (controllers handgeschreven), Jackson-groupId conform, `@Value`-`@param:`-targets correct (de twee kale `@Value` zijn de gedocumenteerde uitzonderingen), geen conventie-strijdige inline comments, controllers zonder business logic.

### Wat getest is
- Nieuwe `ApiRequestDtoContractTest` — Jackson round-trip per verplaatste DTO, borgt dat het wire-contract identiek blijft.
- `mvn test`: **BUILD SUCCESS**, 25/25 tests groen (was 19; +6 nieuw). Bestaande tests niet aangepast. `git diff --check` schoon. Reviewer en tester akkoord, geen blockers.

### Wat bewust niet is gedaan
- **Cross-module imports van interne klassen** (backend-spec §3) — afwijking, maar herstel vereist nieuwe service-interfaces/event-ontkoppeling = architecturale refactor. Gedocumenteerd en gedefereerd naar aparte refactor-story.
- **Domeinmodellen direct als HTTP-response** (backend-spec §4) — herstel zou response-DTO's + mapping vereisen en raakt het frontend-contract (`openapi.yaml` + Flutter). Niet gedrag-neutraal; gedefereerd.
- **Spring Modulith `ApplicationModules.verify()`-test** — niet toegevoegd; zou de build laten falen op bovenstaande punten (buiten scope).
- **Frontends** (`frontend/`, `frontend-reader/`) — ADR-equivalente conventies betreffen de backend; geen wijzigingen nodig.

Conform de scope-aanname zijn de gedrag-rakende afwijkingen met (a) conventie, (b) afwijkende code en (c) rationale gedocumenteerd in het worklog i.p.v. de nightly-run te laten falen.
