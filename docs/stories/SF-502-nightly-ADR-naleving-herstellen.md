# SF-502 — nightly: ADR-naleving herstellen

Story-log voor de PR. Subtaak SF-504 (developer): ADR-naleving controleren, veilig + gedrag-neutraal herstellen, niet-veilige afwijkingen rapporteren.

## Stappenplan
- [x] Issue + factory-docs lezen (`.task.md`, `docs/factory/*`, gezaghebbende specs)
- [x] ADR-equivalente conventies uit `specs/backend-technical-spec.md` + `docs/factory/technical-spec.md` als checklist nalopen tegen de code
- [x] Veilig herstelbare, gedrag-neutrale afwijkingen herstellen
- [x] Niet-veilige (gedrag-/contract-rakende) afwijkingen rapporteren i.p.v. wijzigen
- [x] Bestaande tests draaien (`mvn test`) zonder testaanpassingen
- [x] Zelf-review op gedrag-neutraliteit

## Wat gedaan en waarom
De code is systematisch vergeleken met de twee gezaghebbende conventiebronnen (er is geen `docs/adr/`-map). Geverifieerd via gerichte greps en een groene build: OpenAPI-contract handgeschreven, Jackson-groupId `com.fasterxml.jackson`, `@Value` → `@param:Value` (met de twee gedocumenteerde uitzonderingen), DTO's in `module/api/dto/`, uniform logger-patroon (39/39), uniforme external_call-fallbacks (10/10) en KDoc vóór de stereotype-annotatie zijn **allemaal conform**.

Er was daardoor **geen veilige, gedrag-neutrale afwijking om te herstellen** — de codebase is al geconvergeerd door eerdere passes (SF-392/SF-435/SF-437/SF-456). Conform de acceptatiecriteria is een (zeer) kleine diff — hier alleen het worklog en deze story-log — een geldige uitkomst.

Drie bekende, niet-veilig herstelbare afwijkingen zijn **gerapporteerd, niet gewijzigd** (herstel zou gedrag/contract raken): cross-module imports van interne klassen, domeinmodellen direct als HTTP-response, en `SettingsController` zonder class-level `@RequestMapping` (bedient drie prefixes). Detail + onderbouwing staan in `docs/stories/worklog/SF-502-worklog.md`.

## Resultaat
- `mvn test`: BUILD SUCCESS, 25 tests groen, geen testaanpassingen, geen annotation-target-warnings.
- Geen functionele, API-, data- of UI-wijzigingen.
