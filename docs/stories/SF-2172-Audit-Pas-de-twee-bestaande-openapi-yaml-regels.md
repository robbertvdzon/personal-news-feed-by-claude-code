# SF-2172 - [Audit] Pas de twee bestaande openapi.yaml-regels toe op de gevallen die eerdere stories hebben overgeslagen

## Story

[Audit] Pas de twee bestaande openapi.yaml-regels toe op de gevallen die eerdere stories hebben overgeslagen

<!-- refined-by-factory -->

## Scope

Uitsluitend documentatie. Te wijzigen bestanden: `specs/openapi.yaml`, `docs/factory/technical-spec.md`, `specs/backend-technical-spec.md`. Geen Kotlin, geen Dart, geen SQL, geen deploy-manifest. Geen gedragswijziging.

### Deel 1 — inline `enum` voor drie String-velden

Regel: `docs/factory/technical-spec.md:69-70` (SF-2130). Een gesloten waardenverzameling hoort als inline `enum` in het schema; juist bij een veld dat in Kotlin een `String` is (een `$ref` naar het enum-schema is daar verboden). Alle drie de velden zijn in Kotlin `String` en worden gevuld met `.name` van een Kotlin-enum.

1. `EpisodeLookup.episodeStatus` (openapi.yaml:1938-1940) — bron `PodcastEpisodeStatus` (`podcast_source/PodcastEpisode.kt:78-88`), 9 waarden: PENDING, SUMMARIZING_FROM_NOTES, NEEDS_TRANSCRIPT, SHOW_NOTES_DONE, DOWNLOADING, TRANSCRIBING, SUMMARIZING, DONE, FAILED.
2. `EpisodeLookup.translatedPodcastStatus` (openapi.yaml:1953-1956) — bron `PodcastStatus`.
3. `TranslationStart.status` (openapi.yaml:1970-1972) — bron `PodcastStatus`.

Voor 2 en 3 worden de acht waarden letterlijk overgenomen uit het bestaande `PodcastStatus`-schema (openapi.yaml:1909-1916: PENDING, DETERMINING_TOPICS, GENERATING_SCRIPT, GENERATING_AUDIO, TRANSLATING, TTS_GENERATING, DONE, FAILED), zodat de lijsten niet uiteen kunnen lopen.

Randvoorwaarden:
- De bestaande `description` blijft staan; de `enum` komt erbij.
- `translatedPodcastStatus` behoudt zijn bestaande `nullable: true`; er wordt géén `null` aan de enum-lijst toegevoegd (zie Aannames).
- `Podcast.status` (openapi.yaml:1838-1839) wordt NIET aangeraakt: dat veld is in Kotlin een echt enum en gebruikt terecht een `$ref`.

### Deel 2 — de reden bij vier eigenaarscheck-404's

Regel: `docs/factory/technical-spec.md:65` en `specs/backend-technical-spec.md:545` (SF-2094). Een 404 die uit een per-gebruiker gefilterde lookup komt, krijgt de reden in de `description`, anders is de privacykeuze onzichtbaar voor wie alleen het contract leest.

| operatie | openapi-regel | huidige tekst | bron |
|---|---|---|---|
| `getPodcast` | 811-812 | "Niet gevonden" | `podcast/api/PodcastController.kt:36` |
| `getPodcastAudio` | 885-886 | "Audio nog niet beschikbaar" | `PodcastController.kt:56-57` (twee gefilterde lookups) |
| `lookupEpisodeByRssItem` | 913-914 | "Geen podcast-aflevering gevonden voor dit rss-item" | `podcast/api/PodcastTranslationController.kt:41-43` |
| `translatePodcastEpisode` | 949-950 | "Aflevering niet gevonden" | `podcast/domain/PodcastTranslationServiceImpl.kt:58-59` |

Stijl: volg de vier bestaande gevallen (openapi.yaml:720, :739, :756, :824), die het woord "resource" vervangen door het concrete type — bijvoorbeeld "(onbekend id, of de podcast is van een andere gebruiker)". Blijf bij een kaal `'404'`-blok met alleen een `description`; er komt geen gedeelde `components/responses`-component.

Specifiek:
- Bij `getPodcastAudio` zijn er twee bronnen voor de 404 — de podcast-row (`service.get`) en het ontbrekende mp3-bestand (`service.audioBytes`). Benoem beide; de bestaande betekenis "audio nog niet beschikbaar" mag daarbij niet verdwijnen.
- Bij `translatePodcastEpisode` wordt de formulering overgenomen uit `specs/backend-functional-spec.md:550` (404 = aflevering bestaat niet vóór deze gebruiker, en verraadt niet of de guid van iemand anders is). De bestaande 409-`description` op :951-954 blijft ongewijzigd — 409 blijft gereserveerd voor state-conflicten.

### Deel 3 — ontbrekende waarde in een prozalijst

`openapi.yaml:781` (createPodcast) schrijft het statusverloop als PENDING → DETERMINING_TOPICS → GENERATING_SCRIPT → GENERATING_AUDIO → DONE en mist FAILED. Bron `podcast/PodcastService.kt:15-16` en het `PodcastStatus`-schema in hetzelfde bestand (:1905) zeggen beide `→ DONE/FAILED`; `errorMessage` (:1895) verwijst naar status=FAILED. Vul FAILED aan in dezelfde notatie als :1905.

### Deel 4 — de norm zelf als sjabloon

Drie plekken citeren de 404-reden als de letterlijke tekst "onbekend id, of de resource is van een andere gebruiker", terwijl de praktijk — terecht — het concrete resourcetype invult:
- `docs/factory/technical-spec.md:65`
- `docs/factory/technical-spec.md:96` (codeconventie eigenaarscheck)
- `specs/backend-technical-spec.md:545`

Herformuleer alle drie als sjabloon (bijv. "onbekend id, of de/het \<resource\> is van een andere gebruiker", met "\<resource\>" ingevuld per operatie), zodat een lezer niet denkt dat de bestaande gevallen van de norm afwijken. Betekenis en overige inhoud van die alinea's blijven ongewijzigd.

## Acceptance criteria

1. `EpisodeLookup.episodeStatus` heeft een inline `enum` met exact de 9 waarden van `PodcastEpisodeStatus`, in dezelfde volgorde als de Kotlin-declaratie; de bestaande `description` staat er nog.
2. `EpisodeLookup.translatedPodcastStatus` en `TranslationStart.status` hebben elk een inline `enum` met exact de 8 waarden uit het bestaande `PodcastStatus`-schema, in dezelfde volgorde; beide behouden hun bestaande `description` en `translatedPodcastStatus` behoudt `nullable: true`.
3. `Podcast.status` gebruikt nog steeds `$ref: '#/components/schemas/PodcastStatus'` en heeft geen inline enum gekregen; het `PodcastStatus`-schema zelf is ongewijzigd.
4. De 404-`description` van `getPodcast`, `getPodcastAudio`, `lookupEpisodeByRssItem` en `translatePodcastEpisode` noemt elk de eigenaarsreden met het concrete resourcetype, in de stijl van openapi.yaml:720/:739/:756/:824. Alle vier blijven kale `'404'`-blokken met alleen een `description`.
5. De 404 van `getPodcastAudio` benoemt beide bronnen (de podcast bestaat niet/is van een andere gebruiker, én de mp3 is er nog niet).
6. De createPodcast-`description` (openapi.yaml:781) bevat FAILED en luidt inhoudelijk gelijk aan :1905.
7. `grep -n "of de resource is van een andere gebruiker" docs/factory/technical-spec.md specs/backend-technical-spec.md` geeft nul treffers; op alle drie de plekken (technical-spec.md:65 en :96, backend-technical-spec.md:545) staat een sjabloonformulering. De rest van die alinea's is inhoudelijk ongewijzigd.
8. `specs/openapi.yaml` blijft geldige YAML/OpenAPI 3.1: de diff bevat uitsluitend toevoegingen binnen bestaande schema-/response-blokken en gewijzigde `description`-regels, geen structurele of indentatiewijzigingen, en geen nieuwe of hernoemde schema's, operaties of `$ref`'s.
9. `git diff --stat` raakt uitsluitend `specs/openapi.yaml`, `docs/factory/technical-spec.md` en `specs/backend-technical-spec.md` — nul bestanden onder `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `deploy/` of `e2e/`.
10. `mvn -B clean test` (in `newsfeedbackend/newsfeedbackend`) en `flutter test` (in `frontend/`) blijven groen; dit volgt vanzelf omdat er geen code wijzigt.

## Aannames

- **Nullable + enum.** Het bestand is OpenAPI 3.1.0 maar gebruikt overal het 3.0-sleutelwoord `nullable: true`. Voor `translatedPodcastStatus` volgen we die bestaande huisstijl: `nullable: true` blijft staan en `null` wordt NIET aan de enum-lijst toegevoegd. Consistentie met de rest van het bestand weegt hier zwaarder dan strikte 3.1-correctheid; dat opruimen is een aparte story.
- **Volgorde en vorm van de enum-lijsten.** De waarden staan in de volgorde van de Kotlin-declaratie respectievelijk van het bestaande `PodcastStatus`-schema. De schrijfwijze (block-lijst met `-` versus inline `[A, B]`) volgt wat lokaal in het schema het meest leesbaar is; beide vormen komen al in het bestand voor.
- **Geen validatie-tooling.** Geen enkele GitHub-workflow en `.factory/verification.yaml` valideert `specs/openapi.yaml`, en `python3` in de agent-container heeft geen `pyyaml`. AC 8 is daarom diff-/leesbaarheidsgebaseerd, niet scriptgebaseerd.
- **Geen client-generatie.** Er wordt nergens in de repo een client uit `openapi.yaml` gegenereerd; de Flutter-modellen zijn handgeschreven. Een `enum` toevoegen breekt dus geen build en heeft nul frontend-impact.
- **Scope-uitbreiding norm.** `docs/factory/technical-spec.md:96` en `specs/backend-technical-spec.md:545` staan niet in de oorspronkelijke storytekst, maar bevatten letterlijk hetzelfde citaat als :65. Ze horen in dezelfde diff, anders blijft de audit half af.
- **Historische story-verslagen buiten scope.** Treffers op de oude formulering in `docs/stories/**` zijn afgesloten verslagen en worden niet herschreven.
- **Geen testwijziging.** Er is geen unit- of e2e-test die op `openapi.yaml` asserteert; er komt dus geen nieuwe test bij.

## Eindsamenvatting

Alles gelezen: `.task.md`, de story-diff en het volledige worklog (developer, reviewer, tester).

## Eindsamenvatting SF-2172 — [Audit] Twee bestaande openapi.yaml-regels toepassen op overgeslagen gevallen

**Wat is opgeleverd (documentatie-only, geen gedragswijziging)**

Vier onderdelen, verdeeld over drie bestanden (`specs/openapi.yaml`, `docs/factory/technical-spec.md`, `specs/backend-technical-spec.md`):

1. **Inline `enum` bij drie statusvelden** — `EpisodeLookup.episodeStatus` kreeg de 9 waarden van `PodcastEpisodeStatus` in Kotlin-declaratievolgorde; `EpisodeLookup.translatedPodcastStatus` en `TranslationStart.status` elk de 8 waarden letterlijk uit het bestaande `PodcastStatus`-schema, zodat de lijsten niet uiteen kunnen lopen. Bestaande `description`s bleven staan, `nullable: true` bleef staan.
2. **Reden bij vier eigenaarscheck-404's** — `getPodcast`, `getPodcastAudio`, `lookupEpisodeByRssItem` en `translatePodcastEpisode` benoemen nu het concrete resourcetype en de privacyreden. Bij `getPodcastAudio` zijn beide bronnen genoemd (podcast onbekend/andermans én mp3 nog niet aanwezig), bij `translatePodcastEpisode` is de formulering uit de functional spec overgenomen.
3. **Ontbrekende waarde in prozalijst** — de `createPodcast`-beschrijving eindigt nu op `DONE/FAILED`, gelijk aan het `PodcastStatus`-schema.
4. **De norm zelf als sjabloon** — de drie plekken die de 404-reden als letterlijke tekst citeerden, zijn herschreven als sjabloon ("de/het \<resource\>"), zodat de praktijk niet langer van de norm lijkt af te wijken.

**Bewuste keuzes**

- `Podcast.status` en het `PodcastStatus`-schema zijn niet aangeraakt: dat veld is in Kotlin een echt enum en gebruikt terecht een `$ref`. De inline-enum-regel geldt alleen voor `String`-velden.
- Geen `null` toegevoegd aan de enum van `translatedPodcastStatus`: het bestand gebruikt overal het 3.0-sleutelwoord `nullable: true`; consistentie met de rest van het bestand woog zwaarder. Dat opruimen is een aparte story.
- Geen gedeelde `components/responses`-component; de 404's blijven kale blokken, conform het bestaande patroon.
- Scope-uitbreiding: twee extra plekken met hetzelfde citaat (`technical-spec.md:96`, `backend-technical-spec.md:545`) zijn meegenomen, anders bleef de audit half af. Historische story-verslagen onder `docs/stories/**` zijn bewust niet herschreven.

**Getest**

- `specs/openapi.yaml` geparsed (SnakeYAML door developer/reviewer, js-yaml door tester): geldige OpenAPI 3.1.0, 46 paths, 33 schemas, 35 `$ref`'s allemaal resolvend.
- Semantische diff main→HEAD op de geparste boom: **exact 8** verschillen — 5 descriptions en 3 nieuwe enum-lijsten. Geen structurele of indentatiewijziging, geen nieuwe/hernoemde schema's, operaties of `$ref`'s.
- Alle 10 acceptatiecriteria nagelopen; `grep` op de oude letterlijke formulering geeft 0 treffers in de living docs.
- Live contractcheck op de preview met een wegwerp-account: de vier gedocumenteerde 404's zijn echt gedrag (podcast, audio, rss-item-lookup, translate), controle-call `GET /api/podcasts` → 200. Account daarna opgeruimd.
- Vangnet: `mvn clean verify` exit 0 (129 unit + 77 e2e, 0 failures), `flutter test` 37 groen. Nul bestanden geraakt onder `newsfeedbackend/`, `frontend/`, `frontend-reader/`, `deploy/`, `e2e/`.

**Bewust niet gedaan**

- Geen nieuwe test: er is geen unit- of e2e-test die op `openapi.yaml` asserteert en de story wijzigt geen gedrag.
- Geen OpenAPI-validatie in CI toegevoegd (staat niet in scope; AC 8 is daarom diff-gebaseerd geverifieerd).
- **Openstaand cosmetisch punt:** in de drie herformuleringen staat de plaatshouder een tweede keer binnen backticks (`` `\<resource\>` ``); binnen een code-span is `\` geen escape, dus daar renderen de backslashes letterlijk. Raakt geen acceptatiecriterium, is door reviewer en tester als niet-blokkerend bestempeld en niet gefixt.
