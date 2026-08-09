# SF-2051 - Worklog

Story-context bij eerste pickup:
Eigenaarscheck vóór cancel-vlag, per-gebruiker cancel-map, 404 op cancel

Backend-only (newsfeedbackend/newsfeedbackend).

1. RequestServiceImpl.cancel (request/domain/RequestServiceImpl.kt:59-66): doe EERST get(username, id). Is het resultaat null (verzoek bestaat niet of is niet van deze gebruiker), retourneer dan false ZONDER iets in de cancellation-map te schrijven. Pas na een geslaagde check de cancel-vlag zetten; de bestaande PENDING/PROCESSING -> CANCELLED-logica en returnwaarde true blijven ongewijzigd.

2. Key de cancellation-ConcurrentHashMap (:29) per gebruiker in plaats van alleen op request-id (bijv. "$username/$id" of een geneste map; vorm vrij, mits gebruiker A's sleutel gebruiker B niet kan raken). Pas isCancelled (:135) en het opruimen in rerun (:70) consequent aan op dezelfde sleutelvorm.

3. AdhocOrchestrator (:61) is de ENIGE caller van isCancelled; isCancelled staat niet op de RequestService-interface en de orchestrator injecteert RequestServiceImpl concreet en heeft username al in scope via process(username, requestId). Breid de signatuur uit naar isCancelled(username, id). Laat de vroege return voor isHourlyUpdate/isDailySummary (:43-45) ONGEWIJZIGD.

4. RequestController.cancel (request/api/RequestController.kt:41-45): gebruik de retourwaarde: 204 bij true, 404 bij false via throw NotFoundException("request $id"), volgens het bestaande patroon van delete/rerun in dezelfde controller (GlobalExceptionHandler maakt er 404 + {"error": ...} van). Bewust 404 en niet 403, zodat het antwoord niet verraadt of een id van iemand anders bestaat.

5. VERPLICHT mee: src/test/kotlin/.../e2e/RequestsE2eTest.kt, test 'onbekend request-id geeft 404 op delete en rerun' (regels ~193-199) asserteert vandaag assertEquals(204, post("/api/requests/bestaat-niet/cancel", ...).status) met een commentaarblok dat dit als huidig gedrag vastlegt. Zet die assertie EN dat commentaar om naar 404, anders wordt de build rood.

6. Tests schrijven hoort bij deze subtaak. Dek af: (a) gebruiker B kan met het id van een verzoek van gebruiker A niets annuleren - A's status blijft ongewijzigd en er komt geen sleutel in de map die A's lopende verwerking kan raken; (b) annuleren van een eigen lopend verzoek werkt nog: 204, status CANCELLED, orchestrator stopt bij het eerstvolgende controlepunt (het bestaande annuleer-scenario in RequestsE2eTest blijft groen); (c) POST /api/requests/{onbekend-id}/cancel geeft 404 en laat geen sleutel achter in de map. Asserteer op waarneembaar gedrag (status, isCancelled) waar mogelijk; de interne sleutelvorm is geen contract.

7. Contract en docs: voeg in specs/openapi.yaml bij operationId cancelRequest (~727-739) een '404'-respons toe in de stijl van de bestaande 404-blokken in dat bestand (kale description:, er is geen gedeelde error-component). Vul in specs/backend-functional-spec.md §6.3 stap 8 (~r225) en de 'Annulering'-regel in de foutafhandelingssectie (~r548) aan dat annuleren alleen het eigen verzoek raakt en dat een onbekend of andermans id een 404 geeft.

Buiten scope: frontend (geverifieerd: data_providers.dart:322-325 slikt elke status via catch (_) {}), opruimen van de cancel-map in delete, de permitAll op /ws/requests, en rate limiting.

Sluit af met een eigen reviewstap over de diff en draai mvn -B clean verify in newsfeedbackend/newsfeedbackend; die moet groen zijn.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `RequestServiceImpl.cancel`: eerst `get(username, id)`, pas daarna de cancel-vlag. Bij
  `null` (onbekend id of andermans verzoek) direct `false` en géén schrijfactie in de map —
  dat sluit meteen de ongebonden geheugengroei via zelfverzonnen id's.
- De `cancellation`-map is per gebruiker gekeyed via een private `cancelKey(username, id)`
  = `"$username/$id"`. `rerun` ruimt met dezelfde sleutel op; `isCancelled` heeft nu de
  signatuur `isCancelled(username, id)`. Enige caller `AdhocOrchestrator:61` geeft de
  username mee die daar al in scope is; de vroege return voor hourly/daily is ongewijzigd.
- `RequestController.cancel` gebruikt de returnwaarde: `204` bij `true`, anders
  `NotFoundException("request $id")` → `404` (bewust geen `403`: het antwoord mag niet
  verraden of een andermans id bestaat), identiek aan `delete`/`rerun`.
- Tests (zelf geschreven):
  - Nieuw `request/domain/RequestServiceImplCancelTest.kt` (6 unit-tests): eigen verzoek →
    `CANCELLED` + vlag; andere gebruiker → `false`, lege map, vlag van de eigenaar blijft
    uit, geen enkele `upsert`; onbekend id → `false` + lege map; hetzelfde id bij twee
    gebruikers raakt elkaar niet; afgerond verzoek wordt niet overschreven; `rerun` ruimt
    de vlag op.
  - `RequestsE2eTest`: nieuwe test `een andere gebruiker kan een lopend verzoek niet
    annuleren` (aanvaller krijgt `404`, geen sleutel op `/$id`, status blijft `PROCESSING`
    en de eigenaar loopt gewoon door naar `DONE` met 2 feed-items). De bestaande
    `204`-assertie op `bestaat-niet/cancel` staat nu op `404` — inclusief het commentaar —
    plus een assertie dat er geen vlag achterblijft.
- Docs/contract: `specs/openapi.yaml` `cancelRequest` heeft een `'404'`-respons (kale
  `description:`, stijl van de overige 404-blokken); `specs/backend-functional-spec.md`
  §6.3 stap 8 en de "Annulering"-regel in de foutafhandeling beschrijven de eigenaarsregel.
- Vangnet: `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend` → BUILD SUCCESS
  (116 unit, was 110; 66 e2e, was 65; 0 failures/errors, ~3:28). `mvn -B clean test`
  eveneens groen met 0 `[WARNING]`-regels.
