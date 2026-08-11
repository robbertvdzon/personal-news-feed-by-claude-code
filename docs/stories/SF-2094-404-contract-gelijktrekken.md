# SF-2094 - [Audit] [Audit] Foutcodes in openapi.yaml gelijktrekken met de controllers (zes ontbrekende 404's, één 404 die 409 is)

## Story

[Audit] [Audit] Foutcodes in openapi.yaml gelijktrekken met de controllers (zes ontbrekende 404's, één 404 die 409 is)

<!-- refined-by-factory -->

## Scope

Contract (`specs/openapi.yaml`) en backendcode gelijktrekken op 404-responses. `specs/openapi.yaml` is de source of truth voor de API (`specs/backend-technical-spec.md` §8, `docs/factory/technical-spec.md`); er is geen generatiestap en geen contract-test, dus drift is stil.

**1. Zes ontbrekende `'404'`-responses toevoegen in `specs/openapi.yaml`.** Volg de bestaande stijl in dat bestand: een kaal `'404':` met alleen een `description:` (geen gedeelde error-component; zie `openapi.yaml:501`, `:741`, `:797`, `:869`, `:897`).

| Operatie | openapi.yaml | Bron in code |
|---|---|---|
| `DELETE /api/requests/{id}` | 697-710 (nu alleen `'204'`) | `RequestController.kt:33` |
| `POST /api/requests/{id}/rerun` | 711-726 (nu alleen `'200'`) | `RequestServiceImpl.kt:80` |
| `DELETE /api/podcasts/{id}` | 799-808 (nu alleen `'204'`) | `PodcastController.kt:40` |
| `PUT /api/admin/users/{username}/password` | 1032-1054 | `AuthServiceImpl.kt:77` |
| `PUT /api/admin/users/{username}/role` | 1055-1077 | `AdminServiceImpl.kt:38` (en `AuthServiceImpl.kt:85`) |
| `DELETE /api/admin/users/{username}` | 1078-1096 | `AdminServiceImpl.kt:52` |

- Voor de drie resource-endpoints (requests delete/rerun, podcast delete) dezelfde formulering als het al correcte `POST /api/requests/{id}/cancel` (`openapi.yaml:741-742`): *"… niet gevonden (onbekend id, of de resource is van een andere gebruiker)"*. Dit is geen cosmetiek: 404 in plaats van 403 is een bewuste privacykeuze (comment op `RequestController.kt:43-44`, eigenaarscheck-norm in `docs/factory/technical-spec.md`) en die reden staat nu alleen in de code.
- Voor de drie admin-endpoints volstaat een formulering in de trant van "Gebruiker niet gevonden".
- Neem in de `description` van `DELETE /api/requests/{id}` (nu 702-704) mee dat een vast verzoek (prefix `hourly-update-` / `daily-summary-`) óók een 404 geeft — dat is een beleidsweigering, geen "bestaat niet" (`RequestServiceImpl.kt:63`).

**2. De omgekeerde afwijking rechtzetten.** `POST /api/podcast-source/{episodeGuid}/translate` documenteert `'404': Aflevering niet gevonden` (`openapi.yaml:933-934`), maar `PodcastTranslationServiceImpl.kt:58` gooit voor precies dat geval een `ConflictException` (409) — dezelfde code die het contract reserveert voor de twee state-conflicten eronder (`:61`, `:66`). Kies de kant van het contract: maak er een `NotFoundException` van, zodat dit endpoint zich gedraagt als het buur-endpoint `lookupByRssItem` (`PodcastTranslationController.kt:43`). Dit is de enige van de 13 `NotFoundException`-plekken waar de backend van zijn eigen patroon afwijkt.

Mee te wijzigen bij punt 2:
- `PodcastTranslationServiceImplTest.kt:118-129` (`startTranslation throws ConflictException when episode is not found`) legt de 409 expliciet vast — omzetten naar `NotFoundException` inclusief testnaam. De twee tests eronder (`:132`, `:145`, status-niet-DONE en leeg transcript) blijven 409 en blijven ongewijzigd.
- `specs/backend-functional-spec.md:332` bundelt "de bron-aflevering bestaat voor deze user" en de status-precondities onder één zin "Bij conflict → HTTP 409". Splits: onbekende aflevering → 404, status/transcript-conflict → 409.

**Buiten scope**
- De dode constructies rond rerun (`RequestServiceImpl.kt:80` gooit zelf, waardoor `RequestController.kt:39` onbereikbaar is) — al vastgelegd door de consistency-audit, eigen story.
- Een generatiestap of contract-test voor `openapi.yaml`.
- Frontend: `rss_podcast_detail_screen.dart:281-289` matcht alleen op 409 en valt bij elke andere status terug op de generieke melding "Vertaling kon niet starten". De niet-gevonden-tak is via de UI onbereikbaar (de translate-knop verschijnt pas na een geslaagde lookup), dus geen Flutter-wijziging. `specs/frontend-spec.md:175` beschrijft alleen het 409-pad en blijft daarmee correct.

## Acceptance criteria

1. In `specs/openapi.yaml` heeft elk van de zes bovengenoemde operaties een `'404'`-response met een `description`, in de bestaande kale stijl van dat bestand.
2. De drie resource-endpoints (requests delete, requests rerun, podcast delete) noemen in hun 404-description expliciet dat een resource van een andere gebruiker óók 404 geeft (spiegel van `cancelRequest`), zodat de privacykeuze in het contract staat en niet alleen in de code.
3. De `description` van `DELETE /api/requests/{id}` vermeldt dat een vast verzoek (`hourly-update-` / `daily-summary-`) een 404 geeft.
4. `PodcastTranslationServiceImpl.startTranslation` gooit bij een onbekende `episodeGuid` een `NotFoundException` (→ HTTP 404); de twee state-conflicten blijven `ConflictException` (→ HTTP 409). Het contract op `openapi.yaml:933-938` blijft ongewijzigd en klopt daarna.
5. `PodcastTranslationServiceImplTest` verwacht op het niet-gevonden-pad een `NotFoundException`; de testnaam is meegewijzigd. De overige tests in die klasse blijven ongewijzigd.
6. `specs/backend-functional-spec.md` §"Trigger" van de translate-flow onderscheidt 404 (aflevering onbekend) van 409 (status/transcript nog niet klaar).
7. Verificatiegrep sluit aan: `grep -rn "NotFoundException(" src/main` levert 13 sites (12 throw-sites + de klassedeclaratie in `common/Exceptions.kt:12`); elke throw-site die vanaf een endpoint bereikbaar is, heeft een `'404'` op de bijbehorende operatie in `openapi.yaml`, en elke `'404'` in `openapi.yaml` correspondeert met een 404-bron in de code — dat is een `NotFoundException` óf de expliciete `ResponseEntity.notFound()` op `RssController.kt:91` (openapi.yaml:501). Geen enkele `'404'` blijft ongedekt.
8. `mvn -B --no-transfer-progress clean verify` (in `newsfeedbackend/newsfeedbackend`, conform `.factory/verification.yaml`) is groen.

## Aannames

- De storytekst stelt dat de 13 `NotFoundException`-sites en de 6 bestaande `'404'`-declaraties "niet overlappen"; dat is onjuist en niet leidend. Vier bestaande 404's corresponderen al correct: `openapi.yaml:741` ↔ `RequestController.kt:45`, `:797` ↔ `PodcastController.kt:36`, `:869` ↔ `PodcastController.kt:56/57`, `:897` ↔ `PodcastTranslationController.kt:43`. Die blijven ongewijzigd. Alleen `:501` (andere 404-bron, zie AC 7) en `:933` (deze story) wijken af.
- `AuthServiceImpl.kt:85` (`setRole`) is een tweede 404-bron op hetzelfde role-endpoint; `AdminServiceImpl.kt:38` slaat er in de praktijk al op aan. Eén `'404'` op die operatie dekt beide — geen aparte behandeling, geen opschoning van de dubbele check (dat is dood-code-werk voor een eigen story).
- De admin-404's beschrijven een echt onbekende gebruiker; daar speelt de privacy-motivatie van de resource-endpoints niet, dus die endpoints krijgen een kortere description zonder de "van een andere gebruiker"-zin.
- De `'403': Geen ROLE_ADMIN`-responses op de admin-operaties blijven ongewijzigd; alleen `'404'` wordt toegevoegd.
- Er wordt geen gedeelde `components/responses/NotFound` geïntroduceerd — `openapi.yaml` kent dat patroon vandaag niet en dat zou een grotere, niet gevraagde herstructurering zijn.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de volledige story-diff (`git diff main...HEAD`) gelezen. Hier is de eindsamenvatting.

## Eindsamenvatting SF-2094 — 404-contract gelijktrekken met de controllers

**Wat is gebouwd**

1. **Zes ontbrekende `'404'`-responses toegevoegd in `specs/openapi.yaml`** (`deleteRequest`, `rerunRequest`, `deletePodcast`, `resetUserPassword`, `setUserRole`, `deleteUser`), in de bestaande kale stijl van dat bestand (alleen een `description`, geen gedeelde error-component).
   - De drie resource-endpoints spiegelen de formulering van het al correcte `cancelRequest`: *"onbekend id, of de resource is van een andere gebruiker"*. Daarmee staat de bewuste privacykeuze (404 in plaats van 403) nu in het contract en niet alleen in een code-comment.
   - De drie admin-endpoints kregen de kortere formulering "Gebruiker niet gevonden"; de bestaande `'403': Geen ROLE_ADMIN` bleef ongewijzigd.
   - De `description` van `DELETE /api/requests/{id}` vermeldt nu dat een vast verzoek (`hourly-update-` / `daily-summary-`) óók 404 geeft — een beleidsweigering, niet "bestaat niet".
2. **De omgekeerde afwijking rechtgezet:** `PodcastTranslationServiceImpl.startTranslation` gooide bij een onbekende `episodeGuid` een `ConflictException` (409) terwijl het contract al 404 documenteerde. Dat is nu een `NotFoundException` (404), gelijk aan het buur-endpoint `lookupByRssItem`. De twee state-conflicten (status ≠ `DONE`, leeg transcript) blijven bewust 409.
3. **Documentatie bijgewerkt:** `specs/backend-functional-spec.md` splitst in de translate-trigger nu 404 (aflevering onbekend) van 409 (status/transcript nog niet klaar); die stonden eerder onder één zin gebundeld.

Totale omvang: 4 inhoudelijke bestanden (openapi.yaml, één service, één test, één spec) + story-log en worklog. Geen frontend-, infra- of lockfile-wijzigingen.

**Belangrijkste keuzes**

- **Kant van het contract gekozen** bij de translate-afwijking: de code is aangepast, niet het contract — 404 past bij het patroon van alle 12 andere `NotFoundException`-plekken.
- **Geen gedeelde `components/responses/NotFound`** geïntroduceerd; `openapi.yaml` kent dat patroon vandaag niet en dat zou een niet-gevraagde herstructurering zijn.
- **Eén `'404'` op het role-endpoint** dekt beide 404-bronnen (`AdminServiceImpl` en `AuthServiceImpl`); de dubbele check is niet opgeschoond.
- **Correctie op acceptatiecriterium 7:** de story noemde 13 `NotFoundException`-treffers, het zijn er 15 (14 throw-sites + de klassedeclaratie) — óók al op `main`. Het getal in de AC was vanaf het begin fout; de inhoudelijke eis klopt wel en is handmatig 1-op-1 nagelopen: alle 14 throw-sites zijn gedekt door een `'404'`, en alle 12 `'404'`-responses hebben een bron in de code (11× `NotFoundException` + de expliciete `ResponseEntity.notFound()` op `RssController`).

**Wat is getest**

- `mvn -B --no-transfer-progress clean verify` opnieuw gedraaid door de tester op deze revisie: **BUILD SUCCESS**, 116 unit- + 66 e2e-tests, 0 failures / 0 errors.
- `specs/openapi.yaml` machinaal geparsed (SnakeYAML én js-yaml): parseert schoon, OpenAPI 3.1.0, 46 paths, exact 12 operaties met een `'404'` waaronder de zes nieuwe.
- **Live bewezen op de preview-omgeving:** `POST /api/podcast-source/{onbekende-guid}/translate` geeft nu **404** (was 409 op main) — de kernwijziging. Ook geverifieerd: 404 op delete/rerun van een onbekend verzoek en een onbekende podcast, 404 op de vaste verzoeken (`daily-summary-` / `hourly-update-`), en 404 (geen 403) wanneer een tweede gebruiker het verzoek van iemand anders probeert te verwijderen of te rerunnen. Testaccounts zijn na afloop opgeruimd.

**Bewust niet gedaan**

- **Geen frontend-wijziging.** `rss_podcast_detail_screen.dart` matcht alleen op 409 en toont bij een 404 de generieke melding "Vertaling kon niet starten". Die tak is via de UI onbereikbaar (de vertaalknop verschijnt pas na een geslaagde lookup), en het oude gedrag gaf op dit pad juist de feitelijk onjuiste melding "Transcript is nog niet klaar" — dus geen functionele regressie.
- **Geen contract-test of generatiestap** voor `openapi.yaml`; drift blijft daarmee handmatig te bewaken.
- **De dode constructies rond `rerun`** (onbereikbare tak in `RequestController`) zijn niet opgeruimd — die staan al in een eigen story.
- De admin-404's zijn niet live bewezen (geen admin-account op de preview); die zijn gedekt door de bestaande `AdminE2eTest` (10 groen). De 409-tak van translate is live niet te forceren en blijft gedekt door de twee ongewijzigde unit-tests.
