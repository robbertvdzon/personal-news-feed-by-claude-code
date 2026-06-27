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
