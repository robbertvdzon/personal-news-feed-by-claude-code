# SF-1394 - [Audit] Documentatie: secrets-local.md - JWT_SECRET hernoemen naar APP_JWT_SECRET

## Story

[Audit] Documentatie: secrets-local.md - JWT_SECRET hernoemen naar APP_JWT_SECRET

<!-- refined-by-factory -->

## Samenvatting
De documentatie voor lokale secrets noemt de verkeerde omgevingsvariabele voor het JWT-signing-secret. Een developer die de doc volgt, zet `JWT_SECRET` in zijn `.env`, maar de applicatie leest alleen `APP_JWT_SECRET`. Zonder die variabele genereert de backend stilzwijgend een tijdelijk secret, waardoor bestaande JWT-tokens bij elke herstart ongeldig worden zonder foutmelding. Dit voorstel corrigeert alleen de documentatie, zodat deze weer overeenkomt met de code en met de reeds correcte vermelding in het runbook.

## Scope
- Wijzig in `docs/factory/secrets-local.md`, tabel "Backend env-vars" (regel 10): vervang de variabelenaam `JWT_SECRET` door `APP_JWT_SECRET`.
- Geen andere wijzigingen in dit bestand of elders (geen codewijzigingen, geen wijzigingen aan `runbook.md` of `application.properties`).

## Acceptance criteria
- `docs/factory/secrets-local.md` bevat nergens meer `JWT_SECRET` als env-var-naam.
- De tabel "Backend env-vars" gebruikt `APP_JWT_SECRET`, identiek aan de naam die `runbook.md:159` en `application.properties:34` (`app.jwt.secret=${APP_JWT_SECRET:}`) al gebruiken.
- Er zijn geen overige wijzigingen (geen codewijzigingen).

## Aannames
- De rest van de rij (doel-omschrijving en verkrijgingsmethode) blijft ongewijzigd; alleen de variabelenaam wordt gecorrigeerd.

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
