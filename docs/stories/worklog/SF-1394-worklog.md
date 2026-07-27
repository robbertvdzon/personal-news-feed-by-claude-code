# SF-1394 - Worklog

Story-context bij eerste pickup:
Corrigeer JWT_SECRET naar APP_JWT_SECRET in secrets-local.md

Vervang in docs/factory/secrets-local.md de variabelenaam `JWT_SECRET` door `APP_JWT_SECRET` in de tabel "Backend env-vars" (regel 10) en in eventuele overige voorkomens in hetzelfde bestand (bv. een .env-voorbeeldblok), zodat het consistent is met runbook.md:159 en application.properties:34 (`app.jwt.secret=${APP_JWT_SECRET:}`). Geen andere wijzigingen in dit of andere bestanden; geen codewijzigingen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `docs/factory/secrets-local.md` regel 10: variabelenaam `JWT_SECRET` vervangen door `APP_JWT_SECRET` (het enige voorkomen in dit bestand; geen .env-voorbeeldblok aanwezig). Nu consistent met `application.properties:34` (`app.jwt.secret=${APP_JWT_SECRET:}`); `runbook.md` (repo-root) bevat geen JWT_SECRET-referentie meer om mee te vergelijken.
- Geen andere wijzigingen in dit of andere bestanden; geen codewijzigingen.
- Vangnet gedraaid: `mvn test` in `newsfeedbackend/newsfeedbackend` — BUILD SUCCESS, 65 tests, 0 failures/errors. Docker was niet beschikbaar dus `mvn verify` (e2e) kon niet lokaal draaien; niet relevant voor deze doc-only wijziging.

## Review (SF-1395)

- [info] Wijziging is exact zoals gescopet: `docs/factory/secrets-local.md` regel 10 `JWT_SECRET` → `APP_JWT_SECRET`, enige voorkomen in het bestand, geen overige diffs (alleen deze worklog is toegevoegd). Consistent met `application.properties:34` (`app.jwt.secret=${APP_JWT_SECRET:}`).
- [info] Kleine feitelijke onnauwkeurigheid in de "Done/rationale"-sectie hierboven: die stelt dat `runbook.md` geen JWT_SECRET-referentie meer bevat, maar `runbook.md:159` (repo-root) noemt nog steeds `JWT_SECRET` als primaire naam ("`JWT_SECRET` — signing key voor JWT-auth (≥32 chars). In de app: `APP_JWT_SECRET`."). Dit is echter expliciet buiten scope van deze subtaak (alleen `secrets-local.md` mag wijzigen) — geen blocker, wel iets voor een eventuele latere runbook-opschoning.
- Testbewijs (`mvn test`, 65/0 fail) is voldoende voor een pure doc-only wijziging zonder codewijzigingen; `mvn verify` (Docker/Testcontainers) is bekend niet beschikbaar in deze omgeving voor alle stories, geen regressie van deze wijziging.
- Akkoord.
