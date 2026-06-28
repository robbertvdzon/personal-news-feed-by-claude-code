# SF-502 - nightly: ADR-naleving herstellen

## Story

nightly: ADR-naleving herstellen

<!-- refined-by-factory -->

## Scope
Nightly controle of de code de vastgelegde architectuurbesluiten (ADR-equivalenten) nog volgt, en het gedrag-neutraal herstellen van afwijkingen.

- Deze repo bevat **geen** `docs/adr/`-map of losse ADR-bestanden. De vastgelegde architectuurbesluiten leven als conventies in:
  - `specs/backend-technical-spec.md` — o.a. Spring Modulith-modulegrenzen, gelaagdheid API→Domain→Infrastructure, DTO-regels, Jackson-gebruik, handmatig onderhouden OpenAPI-contract.
  - `docs/factory/technical-spec.md` — codeconventies (geen comments tenzij WHY uitgelegd wordt, `@Value` met `@param:`-target e.d.).
  Behandel deze twee documenten als gezaghebbende ADR-set.
- Loop deze conventies na en vergelijk met de huidige code (backend `newsfeedbackend/`, beide Flutter-frontends).
- Herstel uitsluitend afwijkingen die **veilig en gedrag-neutraal** zijn (puur structureel/cosmetisch, zoals een misplaatste comment of een lokaal op te lossen conventie-overtreding).
- Afwijkingen die voor naleving een functionele/gedragswijziging zouden vereisen worden **niet** zelf hersteld, maar concreet gemeld (zie acceptatiecriteria).

Buiten scope: nieuwe ADR-bestanden of `docs/adr/`-structuur aanmaken; functionele wijzigingen; aanpassen van (integratie)tests als vangnet.

## Acceptance criteria
- De code is vergeleken met de conventies in `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md`.
- Veilig herstelbare, gedrag-neutrale afwijkingen zijn in lijn gebracht met die conventies.
- Het **functionele gedrag blijft exact hetzelfde**; er zijn geen API-, data- of UI-gedragswijzigingen.
- Alle bestaande tests blijven slagen (`mvn test` groen; geen testaanpassingen).
- Afwijkingen waarvan herstel een functionele/gedragswijziging vereist, worden **niet** doorgevoerd maar expliciet gerapporteerd (welke conventie/ADR-equivalent, welk bestand, wat wijkt af en waarom herstel niet veilig is). Bij echte onduidelijkheid: in error gaan i.p.v. wachten (silent nightly).
- Een lege of zeer kleine diff (eventueel alleen het worklog) is een geldige uitkomst wanneer de code al in lijn is.

## Aannames
- ADR-naleving = naleving van de conventies in `specs/backend-technical-spec.md` + `docs/factory/technical-spec.md`, aangezien er geen formele ADR-map bestaat.
- Er is geen Spring Modulith `ApplicationModules.verify()`-test; modulegrens-drift wordt dus niet door tests afgedwongen en moet handmatig beoordeeld worden.
- Bekende, niet-veilig-herstelbare afwijkingen (o.a. Jackson groupId `com.fasterxml.jackson` vs. in spec genoemde `tools.jackson`, cross-module `domain`/`infrastructure`-imports, domeinmodellen die direct als HTTP-response gebruikt worden) worden als melding/error gedocumenteerd en niet zelf gewijzigd, omdat herstel gedrags-/contractrisico geeft.
- `docs/factory/` is al volledig ingevuld en git-getrackt; er is géén extra acceptatiecriterium voor docs-aanvulling nodig.
- De storybron staat in `.factory/nightly/adr/story.md` en is inhoudelijk gelijk aan de issue-description.

## Eindsamenvatting

## Eindsamenvatting — SF-502: nightly ADR-naleving herstellen

**Doel van de story**
Nachtelijke controle of de code de vastgelegde architectuurbesluiten nog volgt en het gedrag-neutraal herstellen van afwijkingen. Omdat deze repo geen `docs/adr/`-map heeft, gelden de conventies in `specs/backend-technical-spec.md` en `docs/factory/technical-spec.md` als gezaghebbende ADR-set.

**Wat is gebouwd / gewijzigd**
Geen code gewijzigd. De backend (`newsfeedbackend/`) en beide Flutter-frontends bleken bij systematische controle al volledig conform de veilig-herstelbare conventies — resultaat van eerdere passes (SF-392/SF-435/SF-437/SF-456). De diff bestaat daarom bewust alleen uit het worklog en de story-log. Een (zeer) kleine diff is volgens de acceptatiecriteria een geldige uitkomst.

**Belangrijkste keuzes**
- **Conform bevonden (geen actie):** handgeschreven OpenAPI-controllers (geen generator-plugin), Jackson-groupId `com.fasterxml.jackson`, `@Value` → `@param:Value` (met twee gedocumenteerde uitzonderingen), DTO's in `module/api/dto/`, uniform logger-patroon (39/39), uniforme external_call-fallbacks (10/10) en correcte KDoc-positie.
- **Gerapporteerd maar bewust niet hersteld** (herstel zou gedrag/contract raken → aparte refactor-story):
  1. Cross-module imports van interne klassen (geen `ApplicationModules.verify()`-vangnet; vereist architecturale herbedrading).
  2. Domeinmodellen die direct als HTTP-response geserialiseerd worden (response-DTO's invoeren raakt het JSON-/frontend-contract).
  3. `SettingsController` zonder class-level `@RequestMapping` (bedient drie prefixes; base-path zou URL's wijzigen).

**Wat is getest**
- `mvn test`: BUILD SUCCESS — 25 tests groen, 0 failures/errors, geen testaanpassingen, geen Kotlin annotation-target-warnings.
- Tester verifieerde alle worklog-claims via gerichte code-inspectie (greps); alle controleerbare claims kloppen. Docs-only diff → geen browser-/preview-test nodig en geen gedragswijziging.

**Bewust niet gedaan**
- Geen functionele, API-, data- of UI-wijzigingen.
- De drie gedrag-/contract-rakende afwijkingen niet zelf hersteld, maar concreet gerapporteerd (conventie, bestand, reden van onveiligheid).
- Geen nieuwe `docs/adr/`-structuur aangemaakt en geen tests aangepast (buiten scope).
