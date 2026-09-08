# SF-2158 - [Audit] E2e-test toevoegen voor het herstel van vastgelopen verzoeken bij herstart

## Story

[Audit] E2e-test toevoegen voor het herstel van vastgelopen verzoeken bij herstart

<!-- refined-by-factory -->

## Scope

Voeg één nieuwe e2e-testklasse `RequestRecoveryE2eTest.kt` toe in `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/`, die erft van `E2eTestBase` en het bestaande harnas gebruikt (app op random poort, echte Postgres via Testcontainers). Geen nieuwe dependency, geen wijziging aan productiecode, geen wijziging aan `specs/`.

De klasse legt het opstartherstel vast dat `specs/backend-functional-spec.md` §6.6 en de "Restart-herstel"-regel in §foutafhandeling beloven: bij serverstart worden alle verzoeken met status `PENDING` of `PROCESSING` gereset naar `FAILED`. Implementatie: `RequestServiceImpl.resetStuck()` (`request/domain/RequestServiceImpl.kt:129-144`), aangeroepen door `RequestBootstrap` (`request/domain/RequestBootstrap.kt:28`).

Opzet per test:
- registreer een eigen gebruiker met `registerUser(...)`;
- zet met `requestService.upsert(username, request)` één of meer verzoeken in de gewenste begintoestand (id's in UUID-vorm, conform de rest van de suite);
- roep `requestService.resetStuck()` aan (de `RequestService`-bean wordt met `@Autowired` geïnjecteerd; beide methodes staan op de interface);
- lees het resultaat terug via HTTP met `GET /api/requests`, niet via de repository, zodat de serialisatie meeloopt.

Te dekken gevallen:

1. `PROCESSING` wordt `FAILED`, met een gevulde `completedAt` (`resetStuck` zet die op `Instant.now()`) — dat onderscheidt een echte reset van een verzoek dat al gefaald was.
2. `PENDING` wordt óók `FAILED`. Zet er een commentaarregel bij met de spec-verwijzing (§6.6), zodat een latere wijziging een bewuste keuze wordt.
3. Afgeronde verzoeken blijven met rust: een `DONE`- en een `CANCELLED`-verzoek houden hun status én hun oorspronkelijke `completedAt`. Neem de twee vaste verzoeken (`hourly-update-<user>` en `daily-summary-<user>`, door `ensureFixedRequests` aangemaakt met status `DONE`) expliciet mee; die moeten na de reset nog steeds `DONE` zijn.
4. De reset raakt alle gebruikers, niet alleen de aanroeper: registreer een tweede gebruiker met een eigen `PROCESSING`-verzoek en assert dat ook die op `FAILED` staat. Voeg een korte toelichting toe dat dit bewust gedrag is (startup-herstel, geen gebruikersactie).
5. Idempotentie: een tweede `resetStuck()` direct erna verandert niets — de statussen blijven `FAILED` en de `completedAt` van de eerste reset blijft staan.

Buiten scope, expliciet: de andere opstartlogica (`AdminBootstrap`, `PodcastStartupReset`, `PodcastBackfillRunner`), de cron-ingangen en `@SchedulerLock`, en alles aan de Flutter-kant.

## Acceptance criteria

1. `newsfeedbackend/newsfeedbackend/src/test/kotlin/com/vdzon/newsfeedbackend/e2e/RequestRecoveryE2eTest.kt` bestaat, erft van `E2eTestBase` en injecteert `RequestService` met `@Autowired`.
2. De klasse dekt de vijf hierboven genoemde gevallen; elk geval is een eigen `@Test` met een Nederlandse, gedragsbeschrijvende naam in de stijl van de bestaande e2e-suite.
3. Elke assertie op verzoekstatus en `completedAt` leest de waarden uit de respons van `GET /api/requests` (met het token van de betreffende gebruiker), niet uit de repository of de service.
4. Bij geval 2 staat een commentaarregel met de spec-verwijzing naar §6.6 van `specs/backend-functional-spec.md`.
5. Bij geval 4 staat een commentaarregel die uitlegt waarom de reset bewust over alle gebruikers loopt (`auth.listUsernames()`, startup-herstel).
6. Geen enkele test asserteert op de totale lijst of het totale aantal verzoeken over alle gebruikers heen; elke test filtert op de eigen, in die test geregistreerde gebruiker(s) en op de eigen request-id's.
7. Er wordt nergens een echt lopend verzoek met een latch in `PROCESSING` vastgehouden; begintoestanden worden uitsluitend via `requestService.upsert(...)` gezet.
8. `git diff` bevat geen wijzigingen in `src/main/`, in `pom.xml` of in `specs/`.
9. `mvn -B --no-transfer-progress clean verify` in `newsfeedbackend/newsfeedbackend` slaagt; de developer draait dit twee keer achter elkaar en zet de uitkomst van beide runs in het worklog. Is Docker in de uitvoeringsomgeving niet beschikbaar (dan is de failsafe/Testcontainers-fase niet lokaal draaibaar), dan wordt dat expliciet in het worklog gemeld, samen met de wél gedraaide `mvn clean test`-uitkomst; de bevestiging komt in dat geval van de CI-verificatie (`.factory/verification.yaml`, `backend-maven-verify`).
10. Legt een test een echt verschil met de specificatie bloot, dan staat dat expliciet in het worklog en is de assertie niet aangepast en de productiecode niet gerepareerd — dat is stof voor een aparte story.

## Aannames

- `ensureFixedRequests` draait via `UserRegisteredListener` op het `UserRegisteredEvent`; voor geval 3 wacht de test daarom eerst tot `GET /api/requests` de twee vaste verzoeken toont (zelfde patroon als `FixedRequestsE2eTest.registerUserWithFixedRequests`, regels 60-65) voordat de eigen verzoeken worden geseed en `resetStuck()` wordt aangeroepen.
- De vaste verzoeken worden aangemaakt met status `DONE` en `completedAt = null`. Geval 3 asserteert voor die twee dus op status `DONE` én op een afwezige/lege `completedAt`, niet op een gevulde waarde.
- `resetStuck()` wordt in de test rechtstreeks op de bean aangeroepen; de app wordt niet herstart. Dat dekt dezelfde codepad als `RequestBootstrap`, dat verder niets anders doet dan deze aanroep plus `ensureFixedRequests`.
- `RssScheduler.hourlyRefresh` gebruikt een hardcoded cron `0 0 * * * *` die (anders dan `app.podcast.recovery.cron`) in `E2eTestBase` niet is uitgezet. Een run precies over het hele uur kan het vaste `hourly-update-`-verzoek aanraken. Dit is een bestaand, in de suite al bekend residurisico (zie de toelichting in `RequestWebSocketE2eTest`, rond regel 100); de test beperkt de blootstelling door direct na `resetStuck()` te asserteren en per id te filteren. Er wordt geen nieuwe cron-property toegevoegd — dat valt buiten scope.
- De e2e-suite draait met `reuseForks=false` en `forkCount=1`, dus er lopen geen e2e-klassen parallel; wel kunnen gebruikers van eerder gedraaide tests in dezelfde JVM/database aanwezig zijn. Daarom de per-gebruiker-afbakening uit acceptatiecriterium 6.
- Er is geen wijziging nodig aan `specs/openapi.yaml`: `GET /api/requests` en de velden `status` en `completedAt` zijn daar al beschreven; deze story voegt alleen dekking toe.
- Frontend blijft ongemoeid: dit is puur startup-herstel aan de serverkant zonder UI-oppervlak.

## Eindsamenvatting

## Eindsamenvatting SF-2158

**Wat is gebouwd**
Eén nieuwe backend-e2e-testklasse: `newsfeedbackend/.../e2e/RequestRecoveryE2eTest.kt` (211 regels, 5 tests). Die legt vast wat `specs/backend-functional-spec.md` §6.6 belooft: bij het opstarten van de server worden alle verzoeken die nog in `PENDING` of `PROCESSING` staan omgezet naar `FAILED`, zodat er na een herstart geen "eeuwig bezig"-verzoeken achterblijven. Verder is alleen het worklog toegevoegd — géén productiecode, géén `pom.xml`, géén `specs/`.

**Gedekte gevallen**
1. `PROCESSING` → `FAILED` met gevulde `completedAt` (beginsituatie eerst vastgelegd, zodat bewezen is dat de reset die waarde zette).
2. `PENDING` → `FAILED`, met commentaarregel naar spec §6.6.
3. Afgeronde verzoeken blijven ongemoeid: eigen `DONE`/`CANCELLED` houden status én `completedAt`; de twee vaste verzoeken (`hourly-update-…`, `daily-summary-…`) blijven `DONE`.
4. De reset raakt álle gebruikers, niet alleen de aanroeper — met commentaar dat dit bewust startup-gedrag is.
5. Idempotentie: een tweede reset verandert niets.

**Keuzes**
- Begintoestanden uitsluitend via `requestService.upsert(...)`; geen latch of echt lopend verzoek (voorkomt races met de orkestrator).
- Alle asserties lezen terug via `GET /api/requests` met het token van de betreffende gebruiker, dus inclusief serialisatie.
- Elke test registreert een eigen gebruiker en filtert op eigen request-id's; nergens een assertie op totalen. Nodig omdat `resetStuck()` databasebreed werkt en `ensureFixedRequests` asynchroon rijen kan overschrijven.
- Geen spec-afwijking gevonden — er is niets afgezwakt of "gerepareerd".

**Testresultaat**
`mvn clean verify` meermaals volledig groen (116 unit + 76 e2e, 0 failures/errors), zowel bij developer als bij tester (laatste run 04:31 min). De vijf nieuwe tests draaien in ~12 s. Aanvullend op de preview bevestigd dat een nieuw account precies de twee vaste `DONE`-verzoeken met lege `completedAt` krijgt. Review en test: akkoord, geen blockers.

**Bewust niet gedaan**
Andere opstartlogica (`AdminBootstrap`, podcast-reset/backfill), cron-ingangen en `@SchedulerLock`, en de Flutter-kant blijven buiten scope. Eén losstaand infrastructuurpunt is gesignaleerd maar niet opgelost: de Testcontainers-"Ryuk"-handshake faalt af en toe op de bouwomgeving, waardoor een willekeurige e2e-klasse in zijn geheel kan omvallen. Dat vraagt een `pom.xml`-wijziging en botst met de scope-afspraak van deze story — apart oppakken. Ook de reviewersuggestie (extra `completedAt`-assertie op `hourly-update-…`) is bewust niet doorgevoerd: niet blokkerend en niet in strijd met een acceptatiecriterium.
