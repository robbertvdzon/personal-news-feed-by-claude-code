# SF-392 - Worklog

Story-context bij eerste pickup:
Consistentie-pass: afwijkende patronen gladstrijken

Voer een begrensde, gedrag-behoudende consistentie-pass uit over de codebase. Bepaal eerst de dominante norm uit docs/factory/technical-spec.md en specs/* (naamgeving, modulith-laag/pakketindeling api+dto/domain/infrastructure, error-handling, SLF4J-logniveaus INFO/DEBUG/WARN/ERROR, @param:Value-use-site-target, Jackson-groupId tools.jackson, comment-conventie). Spoor afwijkingen op in de Kotlin-backend (newsfeedbackend/newsfeedbackend/) en beide Flutter-frontends (frontend/, frontend-reader/) en trek ze per categorie consequent gelijk met de norm. Voer uitsluitend aantoonbaar gedrag-neutrale wijzigingen door: raak geen publieke contracten, endpoints, payloads, volgorde, timing of logica aan; wijzig specs/openapi.yaml, migraties of deploy-config niet; houd module-grenzen en publieke zichtbaarheid intact (alleen binnen-module herschikken). Pas GEEN (integratie)tests aan: zou een wijziging een test breken of een testaanpassing vereisen, voer die wijziging dan niet door en ga per nightly-conventie in error i.p.v. de test te wijzigen. Bij gerede twijfel of gedrag verandert: wijziging weglaten. Verifieer als onderdeel van het ontwikkelwerk: bouw de backend (mvn -DskipTests package), draai unit-tests (mvn test) en laat beide frontends bouwen; doe een zelfreview op gedrag-neutraliteit. Werk het worklog bij met de doorgevoerde categorieën en bewust niet-aangeraakte twijfelgevallen. Een kleine of (vrijwel) lege diff is een geldige uitkomst.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## Aanpak

De codebase is in eerdere nightly-passes al sterk geconsolideerd. Per genoemde
categorie eerst de dominante/gedocumenteerde norm bepaald en daarna gericht naar
afwijkingen gezocht. Alleen aantoonbaar gedrag-neutrale, dominant-genormeerde
afwijkingen zijn gelijkgetrokken; al het twijfelachtige is bewust overgeslagen
(AC #6 — liever minder en zeker).

## Doorgevoerde consistentie-correcties

1. **Log-message consistentie (dominante norm 8:2).** De copy-paste-fallback in
   de `catch`-tak van de `external_call`-logging stond in 8 infrastructuur-clients
   in het Engels (`could not log external_call: {}`) en in 2 clients afwijkend in
   het Nederlands. Gelijkgetrokken naar de dominante Engelse vorm:
   - `events/infrastructure/YouTubeTranscriptClient.kt`
   - `events/infrastructure/VideoAudioDownloader.kt`

   Dit is een WARN-regel in een zelden geraakt fout-pad (alleen als het wegschrijven
   van de external_call-rij zelf faalt); geen logica-, payload-, contract- of
   volgorde-wijziging. Geen testdekking op deze logtekst (backend-suite bevat geen
   log-asserts), dus gedrag-neutraal.

## Geverifieerde categorieën die al conform de norm zijn (geen wijziging nodig)

- **`@param:Value` use-site-target:** alle `@Value` op constructor-properties
  gebruiken al `@param:Value`. De 2 resterende `@Value` zonder target zijn de
  gedocumenteerde uitzonderingen (`@Bean`-methodeparameter in `PodcastAsyncConfig`,
  plain constructor-param zonder val/var in `PodcastTranscriptWorker`).
- **Modulith laag-/pakketindeling:** alle `*Controller` staan onder `api/`, alle
  `*Repository` onder `infrastructure/`, alle `*ServiceImpl` onder `domain/`;
  publieke service-interfaces/events/cross-module-hooks op module-root. 100% conform.
- **Logger-declaratie:** overal identiek `private val log = LoggerFactory.getLogger(javaClass)`.
- **KDoc-positie:** KDoc staat overal vóór de Spring-stereotype-annotatie; geen
  afwijkingen.
- **Log-niveaus:** consistent — INFO voor succes/job-afronding, WARN voor
  herstelbare fouten, ERROR voor fatale, DEBUG op enkele plekken; geen duidelijke
  mechanische afwijking gevonden.
- **Comment-conventie:** aangetroffen comments verklaren steeds de WHY; geen
  redundante "wat"-comments om te verwijderen.

## Bewust NIET aangeraakte twijfelgevallen

- **Jackson groupId (`tools.jackson` vs `com.fasterxml.jackson`):** de code gebruikt
  overal `com.fasterxml.jackson` en compileert/test zo groen. De spec-conventie
  `tools.jackson` is documentatie-drift; migreren is een risicovolle dependency-/
  package-wijziging (geen veilige mechanische fix) — overgeslagen.
- **Bestaande cross-module imports (modulith-overtredingen):** o.a.
  `common/admin -> auth.infrastructure.UserRepository`, `events.domain ->`
  `request.infrastructure.TavilyClient`. Dit zijn architecturale refactors, geen
  mechanische gladstrijk-acties; buiten scope en gedragsrisico — overgeslagen.
- **DTO-locatie:** alleen `auth` heeft een `api/dto/`-map; overige modules
  definiëren DTO's elders/inline. Herschikken raakt imports/serialisatie en is
  geen veilige mechanische fix — overgeslagen.
- **Taal-mix in overige logteksten** (Nederlands vs Engels door elkaar): er is geen
  aantoonbaar dominante norm en het is observeerbare output; breed herschrijven is
  oordeels-/risicogevoelig — overgeslagen. Alleen het exact-identieke,
  dominant-genormeerde `external_call`-regeltje is wél gladgestreken (zie boven).
- **Frontend (`frontend/` vs `frontend-reader/`):** `frontend-reader` is een
  bewust minimale, platte reader-app; `frontend` is de volwaardige app met
  api/models/providers/screens/widgets. De platte structuur is passend bij de
  schaal van de reader-app; herstructureren zou een architectuur-herontwerp met
  brede import-wijzigingen zijn — buiten scope.

## Verificatie

- `mvn -DskipTests package` in `newsfeedbackend/newsfeedbackend/` -> BUILD SUCCESS.
- `mvn test` -> BUILD SUCCESS, 19 tests groen (0 failures/errors).
- Geen (integratie)tests aangepast (AC #4).
- Flutter: geen wijzigingen in de frontends; `flutter`/`dart` ontbreken op de
  runner (alleen in .devcontainer), build/analyze wordt door CI gevalideerd.

## Nieuwe/aangepaste tests

Geen nieuwe unit-test toegevoegd: de enige codewijziging is een logtekst in een
fout-pad. Een test die op exacte logtekst assert zou bros zijn en past niet bij het
testpatroon van deze repo (de bestaande suite heeft geen log-asserts). Bestaande
tests dekken de compilatie/regressie van de geraakte klassen af en blijven groen.

## Review (reviewer)

Volledige story-diff t.o.v. `main` beoordeeld (`git diff main...HEAD`): 2 Kotlin-regels
+ worklog. Bevindingen:

- [info] Dominante norm geverifieerd: na de wijziging 10× Engelse `could not log
  external_call`-WARN en 0× Nederlandse variant — beide outliers correct
  gelijkgetrokken. Gedrag-neutraal (WARN-tekst in zelden geraakt fout-pad, geen
  log-asserts in de suite).
- [info] Scope-grenzen gerespecteerd: geen wijziging aan openapi/migraties/deploy/tests;
  module-grenzen en publieke zichtbaarheid intact (alleen binnen-module tekstwijziging).
- [info] Worklog bevat geen JSON-artefacten en eindigt schoon; bewust niet-aangeraakte
  twijfelgevallen zijn conform AC #6/#7 gedocumenteerd.

Akkoord — kleine, veilige, gedrag-behoudende consistentie-pass conform de story.

## Test (SF-394, tester)

Story-brede verificatie t.o.v. `main` (`git diff main...HEAD`): 2 Kotlin-regels + worklog.

- [pass] **Scope/AC #1,#4,#5:** alleen worklog + 2 log-string-literals gewijzigd; geen
  wijziging aan openapi, migraties, deploy-config of (integratie)tests. Module-grenzen
  en publieke zichtbaarheid intact (alleen binnen-module tekstwijziging).
- [pass] **Gedrag-neutraliteit/AC #1:** enige codewijziging is een WARN-tekst in een
  `catch`-tak (zelden geraakt fout-pad: alleen als wegschrijven van de external_call-rij
  faalt). Geen logica-, payload-, contract-, volgorde- of timing-effect; geen log-asserts
  in de suite.
- [pass] **Norm/AC #2:** geverifieerd 10× Engelse `could not log external_call` en 0×
  Nederlandse variant in `src/main/kotlin` — beide outliers correct gelijkgetrokken naar
  de dominante (8:2) norm.
- [pass] **Build/AC #3,#5:** `mvn -DskipTests test-compile` (JDK 21) → EXIT 0.
  `git diff --check` schoon (geen whitespace-issues).
- [info] **Frontend:** story bevat geen frontend-wijzigingen → geen browser-/preview-test
  vereist (de WARN-logregel is sowieso niet UI-observeerbaar). Beide Flutter-apps
  ongewijzigd.

Uitkomst: **tested** — kleine, veilige, gedrag-behoudende consistentie-pass; alle
acceptatiecriteria voldaan.
