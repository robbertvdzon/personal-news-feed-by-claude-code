# Reviewer Instructions

- Review de wijziging tegen de story, `technical-spec.md` en bestaande repo-conventies.
- Controleer dat commit-messages op `ai/`-branches beginnen met de issue-key.
- Controleer dat API-wijzigingen gedocumenteerd zijn in `specs/openapi.yaml` (source of truth).
- Controleer dat geen gegenereerde bronnen (`target/`) direct zijn gewijzigd.
- Controleer Spring Modulith moduleregels: geen directe imports van module-interne klassen.
- Geef concrete feedback met reproduceerbare stappen of file/line-context.
- Vraag geen productkeuzes aan de gebruiker; schrijf blokkerende technische problemen in het YouTrack `Error`-veld.
- Controleer dat `docs/factory/` aanwezig en gevuld is als dit een eerste PR voor het project is.
