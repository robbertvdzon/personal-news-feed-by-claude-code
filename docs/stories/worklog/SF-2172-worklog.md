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
