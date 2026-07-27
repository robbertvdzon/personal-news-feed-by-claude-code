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
