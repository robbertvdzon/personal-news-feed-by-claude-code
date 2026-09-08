# SF-2051 - [Audit] Annuleren van een verzoek: eigenaarscheck vóór het zetten van de cancel-vlag

## Story

[Audit] Annuleren van een verzoek: eigenaarscheck vóór het zetten van de cancel-vlag

<!-- refined-by-factory -->

## Samenvatting

Wie in de app op "annuleren" klikt bij een nieuwsverzoek, kan op dit moment ook verzoeken van andere gebruikers stopzetten. De app controleert namelijk pas ná het annuleren of het verzoek wel van jou is. De id's die daarvoor nodig zijn liggen niet geheim.

Deze story draait die volgorde om: eerst controleren, dan pas annuleren. Annuleren van je eigen verzoek blijft precies werken zoals nu. Probeer je een verzoek te annuleren dat niet bestaat of niet van jou is, dan krijg je netjes een "niet gevonden" terug — bewust zonder te verklappen of dat verzoek van iemand anders wel bestaat.

Als bijvangst verdwijnt een lek waarbij iemand met zelfverzonnen id's ongelimiteerd geheugen van de server kon vullen.

## Scope

Alleen de backend (`newsfeedbackend/`). Geen frontend-wijziging.

### 1. `RequestServiceImpl.cancel` (`request/domain/RequestServiceImpl.kt:59-66`)

- Doe eerst `get(username, id)`. Levert dat `null` op (verzoek bestaat niet of is niet van deze gebruiker), dan retourneert `cancel` `false` en wordt er **niets** in de `cancellation`-map geschreven.
- Pas ná die geslaagde check de cancel-vlag zetten; de bestaande PENDING/PROCESSING → CANCELLED-logica en de returnwaarde `true` blijven ongewijzigd.

### 2. `cancellation`-map per gebruiker keyen (`RequestServiceImpl.kt:29`)

- De `ConcurrentHashMap` is nu alleen op request-id gekeyed. Key hem voortaan per gebruiker (bijvoorbeeld `"$username/$id"` of een geneste map) — vorm vrij, mits gebruiker A's sleutel gebruiker B niet kan raken.
- Pas mee: `isCancelled` (`:135`) en het opruimen in `rerun` (`:70`).
- `isCancelled` staat níét op de `RequestService`-interface maar alleen op `RequestServiceImpl`, en heeft precies één caller: `AdhocOrchestrator.kt:61`, die `RequestServiceImpl` concreet injecteert (`:25`) en de username al in scope heeft (`process(username, requestId)`). Het uitbreiden van de signatuur naar `isCancelled(username, id)` is dus een afgebakende wijziging zonder interface-impact.

### 3. `RequestController.cancel` (`request/api/RequestController.kt:41-45`)

- Gebruik de retourwaarde: `204` bij `true`, `404` bij `false`. Volg het bestaande patroon van `delete`/`rerun` in dezelfde controller (`throw NotFoundException("request $id")`, wat via `GlobalExceptionHandler` een `404` + `{"error": …}` geeft).
- Bewust `404` en niet `403`: het antwoord mag niet verraden of een id van iemand anders bestaat.

### 4. Bestaande test die het oude gedrag vastpint (verplicht mee)

`src/test/.../e2e/RequestsE2eTest.kt`, test `onbekend request-id geeft 404 op delete en rerun` (regels ~193-199), asserteert vandaag expliciet `assertEquals(204, post("/api/requests/bestaat-niet/cancel", …).status)` met een commentaarblok dat dit "vastlegt als huidig gedrag (inconsistent met delete/rerun)". Die assertie én dat commentaar moeten omgezet worden naar `404`; anders wordt de build rood.

### 5. Contract- en documentatie-bijwerking

- `specs/openapi.yaml:727-739` (`operationId: cancelRequest`) documenteert alleen `'204'`. Voeg een `'404'`-respons toe, in dezelfde stijl als de bestaande `'404'`-blokken elders in het bestand (kale `description:`, er is geen gedeelde error-component).
- `specs/backend-functional-spec.md` §6.3 stap 8 (r225) en de "Annulering"-regel in de foutafhandelingssectie (r548) beschrijven annulering nu zonder eigenaarsregel. Vul aan dat annuleren alleen het eigen verzoek raakt en dat een onbekend of andermans id een `404` geeft.

### Buiten scope

- De vaste verzoeken `hourly-update-<username>` en `daily-summary-<username>` worden door `AdhocOrchestrator.process` (`:43-45`) al vroegtijdig uitgesloten via `isHourlyUpdate`/`isDailySummary`. Die tak was niet exploiteerbaar en moet dat blijven — gedrag ongewijzigd laten.
- `delete(username, id)` ruimt de cancel-map ook niet op. Dat is na deze story hooguit een klein, aan de eigenaar gebonden restje en geen aanvalspad meer; niet meenemen.
- De `/ws/requests`-endpoint staat op `permitAll` (`SecurityConfig.kt:35`) en verspreidt de id's ongeauthenticeerd. Dat blijft ongewijzigd — deze story dicht het misbruikpad, niet de id-lekkage.
- Rate limiting op `/api/requests/*` in het algemeen.

## Acceptance criteria

- Een test toont aan dat gebruiker B met het id van een verzoek van gebruiker A niets kan annuleren: de status van A's verzoek blijft ongewijzigd én er komt geen sleutel in de `cancellation`-map die A's lopende verwerking kan raken.
- Een test toont aan dat annuleren van een eigen lopend verzoek nog gewoon werkt: `204`, status wordt `CANCELLED`, en de orchestrator stopt bij het eerstvolgende controlepunt (het bestaande annuleer-scenario in `RequestsE2eTest` blijft groen).
- Een test toont aan dat `POST /api/requests/{onbekend-id}/cancel` een `404` geeft en geen sleutel achterlaat in de map. De bestaande `204`-assertie in `RequestsE2eTest` is hierop bijgewerkt.
- `specs/openapi.yaml` documenteert de `404` op `cancelRequest`; `specs/backend-functional-spec.md` beschrijft de eigenaarsregel.
- `mvn -B clean verify` is groen (workingDirectory `newsfeedbackend/newsfeedbackend`).

## Aannames

- **Geen frontend-wijziging nodig, geverifieerd.** `frontend/lib/providers/data_providers.dart:322-325` doet `try { await _api.post(...) } catch (_) {}` en zet de status optimistisch op `CANCELLED` vóór de call. Er is dus geen harde `204`-aanname; een `404` wordt stilzwijgend genegeerd. De app annuleert uitsluitend eigen verzoeken, dus dat pad wordt in de praktijk niet geraakt. Dat de optimistische status blijft staan bij een fout geldt identiek voor `delete` en `rerun` en is bestaand gedrag — geen scope. `frontend-reader` raakt requests niet.
- De keuze van de mapsleutelvorm (`"$username/$id"` versus geneste map) is aan de developer; de tests asserteren op waarneembaar gedrag (status en `isCancelled`), niet op de interne sleutelvorm.
- De eigenaarscheck loopt via het bestaande `get(username, id)` (dat `repo.load(username)` filtert) — er is geen aparte autorisatielaag nodig.
- Ongebonden geheugengroei wordt afgedekt als bijvangst van criterium 1 en 3 (geen sleutel zonder geldige eigenaar); expliciete rate limiting valt buiten deze story.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de volledige story-diff (`main...HEAD`, 8 bestanden) gelezen. Hieronder de eindsamenvatting.

---

# SF-2051 — Annuleren van een verzoek: eigenaarscheck vóór het zetten van de cancel-vlag

## Wat is gebouwd

Een gebruiker kon eerder met het id van een nieuwsverzoek van iemand anders die verwerking stopzetten, omdat de app de annuleervlag zette *voordat* werd gecontroleerd of het verzoek wel van de aanvrager was. Die volgorde is omgedraaid.

- **Eigenaarscheck eerst** (`RequestServiceImpl.cancel`): eerst `get(username, id)`. Levert dat niets op (onbekend id óf andermans verzoek), dan `false` en er wordt **niets** in de annuleer-administratie geschreven. Pas daarna wordt de vlag gezet; de bestaande PENDING/PROCESSING → CANCELLED-logica is ongewijzigd.
- **Annuleervlaggen per gebruiker**: de `cancellation`-map wordt gekeyed via `"$username/$id"` (private `cancelKey`). `rerun` ruimt met dezelfde sleutel op; `isCancelled` heeft nu de signatuur `isCancelled(username, id)`. De enige caller, `AdhocOrchestrator`, geeft de al aanwezige username mee — geen interface-impact.
- **404 op cancel** (`RequestController.cancel`): de retourwaarde wordt nu gebruikt — `204` bij succes, anders `NotFoundException` → `404`, net als `delete`/`rerun`.
- **Contract & spec**: `specs/openapi.yaml` documenteert de `404` op `cancelRequest`; `specs/backend-functional-spec.md` §6.3 en de foutafhandelingssectie beschrijven de eigenaarsregel.

## Gemaakte keuzes

- **Bewust `404` en niet `403`**: het antwoord mag niet verraden of een id van een andere gebruiker bestaat.
- **Sleutelvorm `"$username/$id"`** in plaats van een geneste map — de story liet de vorm vrij; dit was de kleinste wijziging.
- **Bijvangst meegenomen**: doordat er geen sleutel meer wordt geschreven zonder geldige eigenaar, is ook het pad dicht waarmee iemand met zelfverzonnen id's ongelimiteerd servergeheugen kon vullen.
- **Geen frontend-wijziging**: vooraf geverifieerd (de app negeert de statuscode) en door de tester bevestigd.

## Wat is getest

- **6 nieuwe unit-tests** (`RequestServiceImplCancelTest`): eigen verzoek annuleren werkt; een andere gebruiker krijgt `false`, lege map, geen upsert en de vlag van de eigenaar blijft uit; onbekend id idem; hetzelfde id bij twee gebruikers raakt elkaar niet; afgerond verzoek wordt niet overschreven; `rerun` ruimt de vlag op.
- **E2e**: nieuwe test waarin een aanvaller een lopend verzoek probeert te annuleren → `404`, geen sleutel, status blijft `PROCESSING`, eigenaar loopt door naar `DONE`. De bestaande `204`-assertie op een onbekend id is (inclusief commentaar) omgezet naar `404`.
- **Vangnet**: `mvn -B clean verify` groen — 116 unit-tests (was 110), 66 e2e-tests (was 65), 0 failures/errors.
- **Live op preview** `pnf-pr-218` (image `sha-fa84d4a`) met wegwerp-accounts: kruis-gebruiker annuleren → `404` en A's verzoek onaangetast; andermans vaste `daily-summary` → `404`; onbekend id → `404`; eigen verzoek → `204` + `CANCELLED`; `rerun` daarna → `200` en netjes naar `DONE` (bewijst dat de per-gebruiker vlag correct wordt opgeruimd); ongeauthenticeerd → `403` ongewijzigd. Testaccounts opgeruimd.
- **Review**: alle 7 story-punten aantoonbaar geïmplementeerd, geen blockers.

## Bewust niet gedaan

- Frontend ongewijzigd (annuleren is via de UI momenteel niet eens bereikbaar).
- Het opruimen van de annuleervlag in `delete` — hooguit een aan de eigenaar gebonden restje, geen aanvalspad meer.
- De `permitAll` op `/ws/requests`, waardoor request-id's ongeauthenticeerd lekken: deze story dicht het misbruikpad, niet de id-lekkage.
- Rate limiting op `/api/requests/*`.
- Het gedrag voor de vaste verzoeken (`hourly-update-*`, `daily-summary-*`) is expliciet ongewijzigd gelaten.

## Aandachtspunten voor later

- De e2e-asserties gebruiken `keys.none { it.endsWith("/$id") }` en leunen daarmee op de interne sleutelvorm; stapt iemand later over op een geneste map, dan wordt die assertie stil triviaal-waar.
- Annuleren van een eigen, al afgerond verzoek zet nog steeds een vlag (en geeft `204`). Onschadelijk, maar niet opgeruimd.

<!-- deploy-summary:start -->
Het stopzetten van een nieuwsverzoek werkt nu alleen nog voor je eigen verzoeken. Voorheen kon iemand anders in theorie jouw lopende verzoek onderbreken; dat kan niet meer. Voor jouw eigen gebruik verandert er niets aan hoe de app werkt.
<!-- deploy-summary:end -->
