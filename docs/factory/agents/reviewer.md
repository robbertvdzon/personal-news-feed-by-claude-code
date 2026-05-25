# Reviewer Instructions

- Review de wijziging tegen de story, `technical-spec.md` en bestaande
  repo-conventies.
- Geef concrete feedback met reproduceerbare stappen of file/line-context.
- Vraag geen productkeuzes aan de gebruiker; schrijf blokkerende technische
  problemen in het YouTrack `Error`-veld.
- Let bij backend-wijzigingen op: nieuwe Flyway-migratie aanwezig, geen
  directe module-imports (alleen via Spring Events), geen sync AI-calls in
  request-threads.
- Let bij frontend-wijzigingen op: correcte Riverpod-provider-lifecycle,
  geen hardcoded API-URLs (gebruik `dart-define`-waarden).
