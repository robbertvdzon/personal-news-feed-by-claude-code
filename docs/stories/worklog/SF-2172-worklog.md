# SF-2172 - Worklog

Story-context bij eerste pickup:
Pas de twee openapi.yaml-huisregels toe op de overgeslagen gevallen

Uitsluitend documentatie; geen Kotlin/Dart/SQL, geen gedragswijziging. (1) Voeg in specs/openapi.yaml een inline enum toe naast de bestaande description bij EpisodeLookup.episodeStatus (9 waarden van PodcastEpisodeStatus uit podcast_source/PodcastEpisode.kt, declaratievolgorde), EpisodeLookup.translatedPodcastStatus en TranslationStart.status (8 waarden letterlijk uit het bestaande PodcastStatus-schema in hetzelfde bestand). translatedPodcastStatus houdt nullable: true en krijgt geen null in de enum-lijst (huisstijl van het bestand). Podcast.status en het PodcastStatus-schema blijven ongewijzigd. (2) Geef de 404-descriptions van getPodcast, getPodcastAudio, lookupEpisodeByRssItem en translatePodcastEpisode de eigenaarsreden met het concrete resourcetype, in de stijl van de bestaande gevallen op r. 720/739/756/824; bij getPodcastAudio beide bronnen benoemen (podcast onbekend/van andere gebruiker en mp3 nog niet beschikbaar); bij translatePodcastEpisode de formulering uit specs/backend-functional-spec.md:550 overnemen en de 409 ernaast ongemoeid laten. Alle vier blijven kale '404'-blokken met alleen een description. (3) Vul FAILED aan in de createPodcast-description, in dezelfde notatie als het PodcastStatus-schema. (4) Herformuleer het letterlijke citaat 'onbekend id, of de resource is van een andere gebruiker' als sjabloon op alle drie de plekken: docs/factory/technical-spec.md (foutcode-norm en codeconventie eigenaarscheck) en specs/backend-technical-spec.md; overige inhoud van die alinea's ongewijzigd. Historische verslagen onder docs/stories/** niet aanpassen. Houd de diff strikt additief binnen bestaande blokken: geen herindentatie, geen nieuwe/hernoemde schema's, operaties of $ref's - er is geen OpenAPI-validatie in CI. Er komt geen nieuwe test bij (niets asserteert op openapi.yaml). Sluit af met een zelfreview van de diff en werk het story-log bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **Deel 1 — inline enums.** `EpisodeLookup.episodeStatus` heeft nu een inline `enum` met de
  9 waarden van `PodcastEpisodeStatus` in declaratievolgorde (`podcast_source/PodcastEpisode.kt`);
  `EpisodeLookup.translatedPodcastStatus` en `TranslationStart.status` kregen elk de 8 waarden
  letterlijk uit het bestaande `PodcastStatus`-schema, zodat de lijsten niet uiteen kunnen lopen.
  Alle drie zijn in Kotlin een `String` gevuld met `.name`, dus een inline `enum` (geen `$ref`)
  is hier de huisregel uit `technical-spec.md`. Bestaande `description`s en `nullable: true` op
  `translatedPodcastStatus` bleven staan; `null` is bewust niet aan de enum-lijst toegevoegd
  (huisstijl van het bestand, zie Aannames). `Podcast.status` en het `PodcastStatus`-schema
  zijn niet aangeraakt — dat veld is in Kotlin een echt enum en gebruikt terecht een `$ref`.
  Block-lijst-notatie gekozen omdat de directe buur (`PodcastStatus`) die ook gebruikt.
- **Deel 2 — reden bij vier eigenaarscheck-404's.** `getPodcast`, `getPodcastAudio`,
  `lookupEpisodeByRssItem` en `translatePodcastEpisode` noemen nu de eigenaarsreden met het
  concrete resourcetype, in de stijl van de bestaande gevallen (r. 720/739/756/824). Bij
  `getPodcastAudio` zijn beide bronnen benoemd (podcast onbekend/van een andere gebruiker én
  mp3 nog niet aanwezig), zodat de bestaande betekenis niet verdwijnt. Bij
  `translatePodcastEpisode` is de formulering uit `specs/backend-functional-spec.md:550`
  overgenomen (verraadt niet of de guid van iemand anders is); de 409 ernaast is ongewijzigd.
  Alle vier blijven kale `'404'`-blokken met alleen een `description`.
- **Deel 3 — FAILED in de prozalijst.** De `createPodcast`-`description` eindigt nu op
  `DONE/FAILED`, gelijk aan het `PodcastStatus`-schema en aan `PodcastService.kt:15-16`.
- **Deel 4 — de norm als sjabloon.** De drie plekken die de 404-reden als letterlijke tekst
  citeerden (`docs/factory/technical-spec.md` r. 65 en 96, `specs/backend-technical-spec.md`
  r. 545) zeggen nu "onbekend id, of de/het \<resource\> is van een andere gebruiker" met de
  toelichting dat `\<resource\>` per operatie het concrete type is. Zo lijkt de praktijk niet
  langer van de norm af te wijken. Overige inhoud van die alinea's is ongewijzigd; historische
  verslagen onder `docs/stories/**` zijn bewust niet aangepast.

Verificatie:
- `specs/openapi.yaml` geparsed met SnakeYAML 2.5 (wegwerp-Java-klasse in /tmp, opgeruimd):
  parse ok, 46 paths, 35 `$ref`'s allemaal resolvend, geen dangling refs. Gecontroleerd dat
  `Podcast.status` nog `$ref: '#/components/schemas/PodcastStatus'` is en dat het
  `PodcastStatus`-schema zijn oorspronkelijke 8 waarden houdt.
- `grep -rn "of de resource is van een andere gebruiker" docs/factory specs` → 0 treffers.
- `git diff --stat`: alleen `docs/factory/technical-spec.md`, `specs/backend-technical-spec.md`
  en `specs/openapi.yaml`; nul bestanden onder `newsfeedbackend/`, `frontend/`,
  `frontend-reader/`, `deploy/` of `e2e/`. De `openapi.yaml`-diff is puur additief binnen
  bestaande blokken plus vijf gewijzigde `description`-regels — geen herindentatie, geen
  nieuwe/hernoemde schema's, operaties of `$ref`'s.
- Vangnet `mvn -B --no-transfer-progress clean verify` (`newsfeedbackend/newsfeedbackend`):
  exit 0, 129 unit + 77 e2e, 0 failures/errors, 5:00 min. `grep -icE 'warning|deprecat|self-attach'`
  op de log geeft 1 hit: de bekende Nederlandse `[Podcast]`-logregel met het woord "warnings"
  erin — geen regressie.
- `flutter test` in `frontend/` 37 groen, in `frontend-reader/` 2 groen. De kale
  `frontend-reader/pubspec.lock`-drift van `flutter test` is teruggezet
  (`git checkout -- frontend-reader/pubspec.lock`); `pubspec.yaml` wijzigde niet.
- Geen nieuwe test toegevoegd: er is geen unit- of e2e-test die op `openapi.yaml` asserteert
  en deze story wijzigt geen gedrag (zie Aannames in de story).

Review (SF-2173, ronde 1):
- Volledige story-diff `main...HEAD` beoordeeld. Scope klopt: alleen `specs/openapi.yaml`,
  `docs/factory/technical-spec.md`, `specs/backend-technical-spec.md` + dit worklog; nul
  bestanden onder `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `deploy/`, `e2e/`.
- AC1-3 zelf nagerekend met SnakeYAML: bestand parset (46 paths); `EpisodeLookup.episodeStatus`
  heeft de 9 `PodcastEpisodeStatus`-waarden in declaratievolgorde (`PodcastEpisode.kt:78-88`),
  `EpisodeLookup.translatedPodcastStatus` en `TranslationStart.status` elk de 8 waarden in de
  volgorde van het `PodcastStatus`-schema; `nullable: true` en de descriptions staan er nog,
  `Podcast.status` is nog `$ref` en `PodcastStatus` zelf is ongewijzigd.
- AC4-5: de vier 404's blijven kale blokken met alleen een `description` en noemen het concrete
  resourcetype; `getPodcastAudio` benoemt beide bronnen, wat overeenkomt met de twee
  gefilterde lookups in `PodcastController.kt:56-57`. AC6: `DONE/FAILED` gelijk aan :1911.
- AC7: `grep "of de resource is van een andere gebruiker"` geeft 0 treffers in de living docs.
- AC10: harness-bewijs `backend-maven-verify` exit 0; `testedTreeSha 0696562…` is gelijk aan
  `git rev-parse HEAD^{tree}`, dus het bewijs hoort bij deze revisie. Geen codewijziging.
- [suggestie] In de drie herformuleringen staat de sjabloonplaatshouder een tweede keer binnen
  backticks (`` `\<resource\>` ``). Binnen een code-span is `\` geen escape, dus daar renderen de
  backslashes letterlijk. Cosmetisch; op te lossen door in de code-span `<resource>` te schrijven.

Test (SF-2174, story-brede test):
- **Scope/AC9.** `git diff --name-only main...HEAD` = `docs/factory/technical-spec.md`,
  `specs/backend-technical-spec.md`, `specs/openapi.yaml` + dit worklog; 0 bestanden onder
  `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `deploy/`, `e2e/`.
- **AC1-3, 8.** `specs/openapi.yaml` geparsed met js-yaml (YAML 1.2): geldig, openapi 3.1.0,
  46 paths, 33 schemas, 35 `$ref`'s allemaal resolvend (incl. `components/parameters/ItemId`
  en `/Username`). Semantische diff main->HEAD op de geparste boom geeft **exact 8**
  verschillen: 5 `description`s (createPodcast + 4x404) en de 3 nieuwe `enum`-lijsten.
  Path- en schema-sleutels zijn identiek aan main -> geen structurele/indentatie-wijziging,
  geen nieuwe of hernoemde schema's, operaties of `$ref`'s. `Podcast.status` is nog
  `$ref: '#/components/schemas/PodcastStatus'`; `PodcastStatus.enum` ongewijzigd (8 waarden).
  `episodeStatus` heeft de 9 waarden in exact de declaratievolgorde van
  `PodcastEpisode.kt:78-88`; `translatedPodcastStatus` (met `nullable: true`) en
  `TranslationStart.status` elk de 8 waarden in de volgorde van het `PodcastStatus`-schema;
  alle drie de bestaande `description`s staan er nog.
- **AC4-5.** Alle 12 `'404'`-blokken opgesomd uit de geparste boom: de vier gewijzigde
  operaties hebben nog uitsluitend de sleutel `description` (kaal blok) en noemen het
  concrete resourcetype; `getPodcastAudio` benoemt beide bronnen (podcast onbekend/andere
  gebruiker en nog geen MP3). Formulering ligt in lijn met de bestaande gevallen op
  r. 720/739/756/824. De 409 bij `translatePodcastEpisode` is onaangeroerd.
- **AC6.** `openapi.yaml:781` luidt nu `... -> GENERATING_AUDIO -> DONE/FAILED`, inhoudelijk
  gelijk aan het `PodcastStatus`-schema (r. 1911).
- **AC7.** `grep -n "of de resource is van een andere gebruiker" docs/factory/technical-spec.md
  specs/backend-technical-spec.md` -> 0 treffers; op alle drie de plekken staat de
  sjabloonformulering met toelichting.
- **Live contractcheck op de preview** (`https://pnf-pr-230.vdzonsoftware.nl`, image sha
  `30ef4c6`): met een wegwerp-user zijn de vier gedocumenteerde 404's echt gedrag -
  `GET /api/podcasts/{onbekend}` -> 404, `GET /api/podcasts/{onbekend}/audio` -> 404,
  `GET /api/podcast-source/by-rss-item/{onbekend}` -> 404,
  `POST /api/podcast-source/{onbekende-guid}/translate` -> 404; `GET /api/podcasts` -> 200 als
  controle. Account opgeruimd met `DELETE /api/account/me` (200), herlogin daarna 401.
  Modus: wegwerp-account (fallback) omdat TESTER_USERNAME/TESTER_PASSWORD leeg zijn en het
  namespace-secret voor deze SA Forbidden is. Geen browser-screenshots: de story raakt geen
  frontend-code en heeft geen UI-oppervlak.
- **AC10 / vangnet.** `mvn -B --no-transfer-progress clean verify` in
  `newsfeedbackend/newsfeedbackend`: **exit 0**, BUILD SUCCESS in 05:38, 129 unit + 77 e2e,
  0 failures / 0 errors over alle 35 reportbestanden, `failsafe-summary.xml` 77/0/0/0,
  `grep -c '^\[WARNING\]'` = 0. `flutter test` in `frontend/`: 37 groen, exit 0, geen
  lockfile-drift (`git status` schoon).
- **Opmerking (niet-blokkerend, cosmetisch).** De reviewer-suggestie over
  `\<resource\>` binnen een code-span staat nog open: binnen backticks is `\` geen
  escape, dus daar renderen de backslashes letterlijk terwijl dezelfde plaatshouder in de
  omringende tekst als `<resource>` rendert. Raakt geen AC.

Documentatie (SF-2176):
- **Norm vs. praktijk gelijkgetrokken.** `docs/factory/technical-spec.md` § API-contract zei nog
  dat een `String`-veld dat met `enum.name` gevuld wordt "`type: string` met een `description`
  over de herkomst" is — zonder inline `enum`, precies het gat dat deze story dichtte. Die zin
  benoemt nu dat het `$ref`-verbod over het *type* gaat en de inline `enum` over de
  *waardenverzameling*, met de drie velden uit deze story en de uitzondering `Podcast.status`.
- **`specs/backend-technical-spec.md` §8** kreeg dezelfde nuance in het SF-2073-bullet
  ("Types die strenger beloven") en het SF-2130-bullet ("gesloten waardenverzameling":
  afdwingen op de invoer óf alleen produceren via `.name` telt allebei), plus een nieuw blok
  **"Een huisregel geldt niet met terugwerkende kracht (SF-2172)"** met de uitkomst van deze
  opruimronde: de drie inline enums (incl. `nullable`-huisstijl), de vier 404-descriptions
  (met de nieuwe regel: één `'404'` met twee bronnen benoemt ze allebei) en de prozalijst van
  `createPodcast`, met de greps om de volgende ronde meteen repo-breed te doen.
- **`docs/onboarding-senior-developer.md`** review-checklist "API-wijziging?": de enum-clausule
  dekte alleen invoervalidatie en dus niet de gevallen van deze story.
- **Cosmetische reviewer-/testeropmerking opgelost:** de plaatshouder binnen een code-span is nu
  `` `<resource>` `` in plaats van `` `\<resource\>` `` (drie plekken). De backslashes in de
  omringende platte tekst blijven staan — daar zijn ze wél nodig.
- Niet geraakt: `specs/openapi.yaml` (de story is dit contract), `specs/backend-functional-spec.md`
  (§6.5 :334 en §10 :550 beschrijven de translate-404/409 al correct; de audio- en podcast-404's
  zijn contractdetail zonder functionele alinea), `specs/frontend-spec.md` (nul UI-oppervlak;
  de 404-tak van de lookup is via de UI onbereikbaar), `README.md`/`runbook.md`/`deploy/`/`e2e/`
  (nul verwijzingen naar deze descriptions of statuslijsten), en de historische verslagen onder
  `docs/stories/**`.
