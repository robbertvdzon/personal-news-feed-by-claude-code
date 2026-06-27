# SF-392 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope

Een begrensde, **gedrag-behoudende** consistentie-pass over de codebase: zoek plekken die afwijken van de elders in de codebase gehanteerde norm en trek ze gelijk. Geen functionele wijzigingen.

In scope (norm = bestaande, dominante conventie zoals vastgelegd in `docs/factory/technical-spec.md` en `specs/*`, en zoals het mérendeel van de code het al doet):
- **Backend** (Kotlin, `newsfeedbackend/newsfeedbackend/`):
  - Naamgeving en pakket-/laagindeling conform de modulith-conventie (`api/` + `dto`, `domain/`, `infrastructure/`); afwijkende plaatsing of naamgeving binnen een module gelijktrekken zonder publieke contracten of module-grenzen te wijzigen.
  - Logging-niveaus conform de afgesproken SLF4J-conventie (INFO job-start/einde, DEBUG externe API-calls, WARN herstelbaar, ERROR niet-herstelbaar).
  - Error-handling: dezelfde soort fout op één manier afhandelen waar er nu meerdere stijlen door elkaar lopen.
  - `@Value`-use-site-target (`@param:Value`) en Jackson-groupId (`tools.jackson`) conform de gedocumenteerde conventie.
  - Comment-conventie (geen comments tenzij de WHY niet-vanzelfsprekend is) toepassen waar duidelijk afgeweken wordt.
- **Frontend** (Dart, `frontend/` én `frontend-reader/`): afwijkende naamgeving/structuur t.o.v. het dominante patroon in dezelfde laag (bijv. providers, screens, widgets) gelijktrekken.

Buiten scope:
- Elke wijziging die waarneembaar gedrag verandert (API-respons, payload-vorm, volgorde, timing, logica).
- Wijzigen van `specs/openapi.yaml`-contract of het toevoegen/verwijderen van endpoints, velden of statuscodes.
- Wijzigen van bestaande tests (zie acceptatiecriteria); nieuwe abstracties, dependency-upgrades of architectuur-herontwerp.
- Pure documentatie-alignment (dat is SF-319/SF-327-werk).

## Acceptance criteria

1. De wijzigingen zijn uitsluitend consistentie-/conventie-correcties; **functioneel gedrag blijft exact identiek** (geen wijziging in API-contract, payloads, logica of side-effects).
2. Gewijzigde code is in lijn gebracht met de aantoonbaar dominante norm in de codebase / de conventies uit `docs/factory/technical-spec.md`; per type afwijking wordt consequent één stijl gekozen.
3. Alle bestaande backend-unit-tests blijven slagen (`mvn test` in `newsfeedbackend/newsfeedbackend/`); beide Flutter-frontends blijven bouwen.
4. Integratietests (het vangnet) worden **niet** aangepast. Als een wijziging een integratietest zou breken of een testaanpassing vereist, wordt die wijziging niet doorgevoerd en gaat de developer per nightly-conventie in error i.p.v. de test aan te passen.
5. De backend-build slaagt (`mvn -DskipTests package`) en, voor zover module-grenzen geraakt worden, blijven die intact (geen nieuwe cross-module toegang tot interne klassen).
6. Bij gerede twijfel of een wijziging gedrag verandert, wordt de wijziging achterwege gelaten (of de developer gaat in error) — liever minder en zeker dan breed en risicovol.
7. Het worklog bevat een korte opsomming van de doorgevoerde consistentie-categorieën en de bewust niet-aangeraakte twijfelgevallen.

## Aannames

- "De norm" = de conventie die het merendeel van de codebase al volgt, dan wel expliciet is vastgelegd in `docs/factory/technical-spec.md` en `specs/*`; bij conflict is gedocumenteerde conventie leidend.
- "De code" omvat zowel de Kotlin-backend als beide Flutter-frontends (`frontend/` en `frontend-reader/`), conform de twee-buildsystemen-context van eerdere nightly-jobs.
- Dit is bewust een **begrensde** pass: niet elke denkbare afwijking hoeft in één story weggewerkt te worden; een kleine, veilige, gefocuste diff is een geldige uitkomst (ook een (vrijwel) lege diff als er niets veilig gelijk te trekken valt).
- Geen wijzigingen aan database-migraties, deploy-config of het OpenAPI-contract — die vallen buiten "afwijkende patronen gladstrijken".
- Als consistentiewerk een endpoint/contract zou raken, wordt het niet gedaan (gedragsrisico).

## Eindsamenvatting

## Eindsamenvatting — SF-392: Consistentie-pass: afwijkende patronen gladstrijken

**Wat is gebouwd**
Een begrensde, gedrag-behoudende consistentie-pass over de codebase. De codebase bleek door eerdere nightly-passes al sterk geconsolideerd, dus de uitkomst is bewust een kleine, veilige diff: **2 gewijzigde Kotlin-regels + worklog**.

De enige codewijziging: de fallback-WARN-tekst in de `catch`-tak van de `external_call`-logging is in 2 outlier-clients (`YouTubeTranscriptClient.kt`, `VideoAudioDownloader.kt`) van het Nederlands naar de dominante Engelse vorm (`could not log external_call: {}`) gebracht. Daarmee 10× de genormeerde Engelse variant, 0× de Nederlandse — conform de aantoonbaar dominante 8:2-norm.

**Gemaakte keuzes**
- Per categorie eerst de dominante/gedocumenteerde norm bepaald, daarna pas gericht naar afwijkingen gezocht; alleen aantoonbaar gedrag-neutrale correcties doorgevoerd (AC #6: liever minder en zeker).
- Geverifieerd al-conforme categorieën (geen wijziging nodig): `@param:Value` use-site-targets, modulith laag-/pakketindeling (Controller→api/, Repository→infrastructure/, ServiceImpl→domain/), logger-declaratie, KDoc-positie, log-niveaus en comment-conventie.

**Wat getest is**
- `mvn -DskipTests package` → BUILD SUCCESS; `mvn test` → 19 tests groen (0 failures/errors).
- Tester bevestigde story-breed: scope-grenzen intact (geen wijziging aan openapi, migraties, deploy-config of (integratie)tests), gedrag-neutraal (zelden geraakt fout-pad, geen log-asserts in de suite), `mvn test-compile` exit 0, `git diff --check` schoon.
- Frontends ongewijzigd → geen browser-/preview-test vereist. Flutter-builds worden door CI gevalideerd (toolchain ontbreekt op de runner).
- Reviewer akkoord: kleine, veilige, gedrag-behoudende pass.

**Bewust niet gedaan (risico/buiten scope)**
- **Jackson groupId** (`tools.jackson` vs `com.fasterxml.jackson`): documentatie-drift; migreren is een risicovolle dependency-wijziging, geen mechanische fix.
- **Cross-module imports** (modulith-overtredingen, o.a. `common/admin → auth.infrastructure.UserRepository`): architecturale refactors, gedragsrisico.
- **DTO-locatie**: alleen `auth` heeft `api/dto/`; herschikken raakt imports/serialisatie.
- **Overige taal-mix in logteksten**: geen aantoonbaar dominante norm, observeerbare output.
- **Frontend `frontend-reader` vs `frontend`**: bewust minimale reader-app; herstructureren = architectuur-herontwerp, buiten scope.

Geen nieuwe tests toegevoegd: een assert op exacte logtekst zou bros zijn en past niet bij het testpatroon van deze repo; bestaande tests dekken de regressie van de geraakte klassen.
