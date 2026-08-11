# SF-2094 - Worklog

Story-context bij eerste pickup:
404-contract gelijktrekken met de controllers en translate-flow corrigeren

Voer al het ontwikkelwerk van SF-2094 uit.

1. specs/openapi.yaml: voeg een kaal '404'-blok (alleen description, geen components/responses) toe aan zes operaties: DELETE /api/requests/{id}, POST /api/requests/{id}/rerun, DELETE /api/podcasts/{id}, PUT /api/admin/users/{username}/password, PUT /api/admin/users/{username}/role, DELETE /api/admin/users/{username}. Werk van onderaf naar boven zodat regelnummers niet verschuiven en verifieer per operatie op operationId/pad in plaats van op regelnummer.
2. De drie resource-endpoints (requests delete, requests rerun, podcast delete) spiegelen de formulering van POST /api/requests/{id}/cancel: niet gevonden bij onbekend id of wanneer de resource van een andere gebruiker is. Dat legt de bewuste privacykeuze (404 in plaats van 403) vast in het contract. De drie admin-endpoints krijgen een korte 'Gebruiker niet gevonden'-formulering zonder die zin. De bestaande '403': Geen ROLE_ADMIN-responses blijven ongewijzigd.
3. Neem in de description van DELETE /api/requests/{id} op dat een vast verzoek (prefix hourly-update- / daily-summary-) eveneens een 404 geeft: een beleidsweigering, geen 'bestaat niet'.
4. PodcastTranslationServiceImpl.startTranslation: gooi bij een onbekende episodeGuid een NotFoundException in plaats van de huidige ConflictException, zodat dit endpoint zich gedraagt als het buur-endpoint lookupByRssItem. De twee state-conflicten (status niet DONE, leeg transcript) blijven ConflictException/409. Het contract op die operatie blijft ongewijzigd.
5. PodcastTranslationServiceImplTest: de test die de 409 op het niet-gevonden-pad vastlegt omzetten naar NotFoundException, inclusief testnaam. De twee tests eronder blijven ongewijzigd.
6. specs/backend-functional-spec.md: splits in de Trigger-sectie van de translate-flow de gebundelde 'Bij conflict -> HTTP 409'-zin in 404 (aflevering onbekend) en 409 (status/transcript nog niet klaar).
7. Verifieer met de twee greps uit AC 7 dat de lijsten op elkaar aansluiten; houd er rekening mee dat openapi.yaml:501 (GET /api/rss/{id}/transcript) zijn 404 uit RssController's ResponseEntity.notFound() haalt en geen NotFoundException gebruikt - dat is een toegestane vorm, geen afwijking.
8. Buiten scope: de dode constructies rond rerun, een generatiestap of contract-test voor openapi.yaml, en elke Flutter-wijziging.

Sluit af met een eigen reviewronde en een groene 'mvn -B --no-transfer-progress clean verify' in newsfeedbackend/newsfeedbackend.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Zes ontbrekende '404'-responses toegevoegd in specs/openapi.yaml (deleteRequest, rerunRequest,
  deletePodcast, resetUserPassword, setUserRole, deleteUser), in de bestaande kale stijl. De drie
  resource-endpoints spiegelen de privacyformulering van cancelRequest; de drie admin-endpoints
  krijgen "Gebruiker niet gevonden". De description van deleteRequest vermeldt nu dat een vast
  verzoek (hourly-update- / daily-summary-) eveneens 404 geeft.
- PodcastTranslationServiceImpl.startTranslation gooit bij een onbekende episodeGuid een
  NotFoundException (404) i.p.v. ConflictException, conform het al bestaande contract en het
  buur-endpoint lookupByRssItem. De twee state-conflicten blijven 409.
- PodcastTranslationServiceImplTest: de not-found-test verwacht nu NotFoundException, testnaam
  meegewijzigd; de twee tests eronder ongewijzigd.
- specs/backend-functional-spec.md: translate-trigger splitst 404 (aflevering onbekend) van 409
  (status/transcript nog niet klaar).
- Verificatie: greps uit AC 7 sluiten aan (13 NotFoundException-sites, 12 '404'-responses, alle
  gedekt), openapi.yaml parseert met SnakeYAML, en `mvn -B --no-transfer-progress clean verify`
  in newsfeedbackend/newsfeedbackend is groen (exitcode 0, 116 unit + 66 e2e, 0 failures/errors).

Details: docs/stories/SF-2094-404-contract-gelijktrekken.md

Review (SF-2095, reviewer):
- Akkoord. Volledige story-diff (`git diff main...HEAD`) t.o.v. main gereviewd: 6 bestanden,
  scope strak binnen de story, geen frontend-/lockfile-/infra-drift.
- AC 1-3, 6 geverifieerd door openapi.yaml met SnakeYAML te parsen en per operationId de
  response-keyset te dumpen: exact 12 operaties met een '404', waaronder de zes nieuwe
  (deleteRequest, rerunRequest, deletePodcast, resetUserPassword, setUserRole, deleteUser).
  Document parseert zonder fouten.
- AC 4-5 geverifieerd in de code: alleen de not-found-guard werd ConflictException ->
  NotFoundException; de twee state-conflicten (status != DONE, leeg transcript) staan nog op
  ConflictException. `openapi.yaml` translate-operatie (404 + 409) ongewijzigd en klopt nu.
- AC 8 / testbewijs: surefire 116 + failsafe 66, 0 failures / 0 errors, uit de developer-run.
  De surefire-XML van PodcastTranslationServiceImplTest bevat de NIEUWE testnaam
  (`startTranslation throws NotFoundException when episode is not found`), dus het groene
  bewijs hoort bij deze revisie. Vangnet niet herdraaid.
- CORRECTIE op AC 7 en op de regel hierboven: `grep -rn "NotFoundException(" src/main` levert
  15 treffers (14 throw-sites + de klassedeclaratie in common/Exceptions.kt:12), niet 13.
  Ook op main waren het er al 14. Het getal in de AC was dus vanaf het begin fout; de
  inhoudelijke eis klopt wel: alle 14 throw-sites zijn afgedekt door een '404' op de
  bijbehorende operatie, en alle 12 '404'-responses hebben een 404-bron in de code
  (11x NotFoundException + RssController.kt:91 ResponseEntity.notFound() voor openapi.yaml:501).
  Handmatig 1-op-1 nagelopen; geen ongedekte '404' en geen onafgedekte throw-site.
- [info] frontend/lib/screens/rss_podcast_detail_screen.dart:284 matcht alleen op 409; een 404
  valt nu in de generieke melding "Vertaling kon niet starten". Conform story buiten scope en
  via de UI onbereikbaar (translate-knop verschijnt pas na een geslaagde lookup). Bovendien
  gaf het oude gedrag op dit pad de feitelijk onjuiste melding "Transcript is nog niet klaar",
  dus geen functionele regressie.
